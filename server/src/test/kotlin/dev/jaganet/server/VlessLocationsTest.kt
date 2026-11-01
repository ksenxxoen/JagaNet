package dev.jaganet.server

import dev.jaganet.api.NodeReq
import dev.jaganet.api.Protocols
import dev.jaganet.api.TunnelProvisionReq
import dev.jaganet.api.Vless
import dev.jaganet.server.services.NodePeerCounter
import dev.jaganet.server.services.NodeRegisterReq
import dev.jaganet.server.services.NodeReportReq
import dev.jaganet.server.services.VlessSubscription
import dev.jaganet.server.services.XrayBalancer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Issue #41: one UUID on every VLESS node, a subscription with every location, the balancer. */
class VlessLocationsTest {
    private val pbk = "_No2ExH-ajHLTIY4xDz1EjOQ6izyfCBAueJM73gZbh0"

    private fun settings(host: String) = Vless.NodeSettings(
        host = host, publicKey = pbk, shortIds = listOf("a1b2c3d4"), sni = host,
        transports = listOf(Vless.Transport("tcp", 443), Vless.Transport("xhttp", 8443, path = "/p")),
    )

    private class Node(val id: String, val token: String, val host: String)

    /** A VLESS node made as the owner does it, with its agent registered. */
    private suspend fun Harness.node(cc: String, host: String, sortOrder: Int? = null, displayName: String? = null, role: String? = null): Node {
        val res = services.nodes.create(
            NodeReq("VLESS $cc", "City", cc, maxPeers = 100, protocol = "vless", vless = settings(host), displayName = displayName, sortOrder = sortOrder, cascadeRole = role),
        )
        val token = res.command.substringAfter("NODE_TOKEN=").substringBefore(' ')
        services.nodes.register(token, NodeRegisterReq("203.0.113.${(10..250).random()}", protocol = "vless"))
        return Node(res.node.id, token, host)
    }

    private suspend fun Harness.update(n: Node, change: (NodeReq) -> NodeReq) {
        val a = services.nodes.list().single { it.id == n.id }
        services.nodes.update(n.id, change(NodeReq(a.name, a.city, a.countryCode, a.maxPeers, a.active, a.hostname, countryName = a.countryName)))
    }

    private suspend fun Harness.uuidOf(userId: String) = ctx.db.run { it.one("SELECT uuid FROM vless_keys WHERE user_id=?::uuid", userId)!!.str("uuid") }
    private suspend fun Harness.tokenOf(userId: String) = ctx.db.run { it.one("SELECT link_token FROM vless_keys WHERE user_id=?::uuid", userId)!!.str("link_token") }
    private suspend fun Harness.peersOf(n: Node) = services.nodes.peers(n.token, null).peers.map { it.publicKey }

    private fun names(base64: String) = String(Base64.getDecoder().decode(base64)).split("\n").map { java.net.URLDecoder.decode(it.substringAfter('#'), "UTF-8") }

    private suspend fun Harness.sub(token: String, query: String = "", ua: String? = null): HttpResponse =
        b.createClient {}.get("/k/$token/vless$query") { ua?.let { header("User-Agent", it) } }

    @Test fun `the UUID is on every active VLESS node, a new node gets everyone, a switched off one loses them`() = harness {
        val fi = node("FI", "fi.example.com")
        val nl = node("NL", "nl.example.com")
        val a = signIn("a@example.com")
        val b2 = signIn("b@example.com")
        assertEquals(2, services.vlessKeys.sync().created)
        val ua = uuidOf(a.session.user.id)
        val ub = uuidOf(b2.session.user.id)
        // Each user has one tunnel (its home) and a replica on the other node: both agents get both users.
        assertEquals(listOf(ua, ub).sorted(), peersOf(fi))
        assertEquals(listOf(ua, ub).sorted(), peersOf(nl))
        assertEquals(2, ctx.db.run { it.one("SELECT count(*)::int AS n FROM tunnels WHERE protocol='vless'")!!.int("n") })
        assertEquals(2, ctx.db.run { it.one("SELECT count(*)::int AS n FROM vless_replicas")!!.int("n") })

        // A third node: everyone is on it as soon as the owner creates it, before the next timer run.
        val de = node("DE", "de.example.com")
        assertEquals(listOf(ua, ub).sorted(), peersOf(de))
        assertEquals(3, names(sub(tokenOf(a.session.user.id)).bodyAsText()).size / 2)

        // Switched off: its agent drops the replicas at once (only keys whose home it is stay until
        // the timer moves them), and it leaves the subscription.
        update(nl) { it.copy(active = false) }
        val homes = ctx.db.run { s -> s.query("SELECT peer_key FROM tunnels WHERE server_id=? ORDER BY peer_key", nl.id).map { it.str("peer_key") } }
        assertEquals(homes, peersOf(nl))
        // The timer moves keys whose home was there; afterwards nobody is on the switched off node.
        services.vlessKeys.sync()
        assertEquals(emptyList(), peersOf(nl))
        assertEquals(listOf(ua, ub).sorted(), peersOf(fi))
        assertEquals(listOf(ua, ub).sorted(), peersOf(de))
        val list = names(sub(tokenOf(a.session.user.id)).bodyAsText())
        assertTrue(list.none { "Нидерланды" in it }, list.toString())
        assertEquals(4, list.size)
        // Same UUID everywhere, still.
        assertEquals(ua, uuidOf(a.session.user.id))

        // Back on: everyone again.
        update(nl) { it.copy(active = true) }
        assertEquals(listOf(ua, ub).sorted(), peersOf(nl))
    }

    @Test fun `ended access drops the UUID from every node`() = harness(mapOf("FREE_DAYS" to "3")) {
        val fi = node("FI", "fi.example.com")
        val nl = node("NL", "nl.example.com")
        val u = signIn("u@example.com")
        services.vlessKeys.sync()
        val uuid = uuidOf(u.session.user.id)
        assertEquals(listOf(uuid), peersOf(fi))
        assertEquals(listOf(uuid), peersOf(nl))
        ctx.db.run { it.exec("UPDATE users SET created_at=? WHERE id=?::uuid", clock.minus(Duration.ofDays(10)), u.session.user.id) }
        assertEquals(1, services.vlessKeys.sync().disabled)
        assertEquals(emptyList(), peersOf(fi))
        assertEquals(emptyList(), peersOf(nl))
        assertEquals(0, ctx.db.run { it.one("SELECT count(*)::int AS n FROM vless_replicas")!!.int("n") })
        buy(u.api)
        services.vlessKeys.sync()
        assertEquals(listOf(uuid), peersOf(fi))
        assertEquals(listOf(uuid), peersOf(nl))
    }

    @Test fun `the subscription lists every location in the owner's order, with backups and display names`() = harness(mapOf("PUBLIC_URL" to "https://boop.example")) {
        val fi = node("FI", "fi.example.com", sortOrder = 2)
        node("NL", "nl.example.com", sortOrder = 1, displayName = "Амстердам")
        val hidden = node("DE", "de.example.com", sortOrder = 0)
        update(hidden) { it.copy(inSubscription = false) }
        val u = signIn("u@example.com")
        services.vlessKeys.sync()
        val uuid = uuidOf(u.session.user.id)
        val token = tokenOf(u.session.user.id)
        val res = sub(token, ua = "Happ/3.1.0")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(listOf("🇳🇱 Амстердам", "🇳🇱 Амстердам (запасной)", "🇫🇮 Финляндия", "🇫🇮 Финляндия (запасной)"), names(res.bodyAsText()))
        val links = String(Base64.getDecoder().decode(res.bodyAsText())).split("\n")
        assertTrue(links.all { it.startsWith("vless://$uuid@") }, links.toString())
        assertTrue(links[0].contains("@nl.example.com:443?") && "type=tcp" in links[0] && "flow=xtls-rprx-vision" in links[0])
        assertTrue(links[1].contains("@nl.example.com:8443?") && "type=xhttp" in links[1])
        // Hidden: not in the subscription, nobody's UUID on it, not in the apps' list.
        assertEquals(emptyList(), peersOf(hidden))
        assertTrue(u.api.servers(listOf("amneziawg", "vless")).servers.none { it.id == hidden.id })
        // Nothing about protocols in the names.
        assertTrue(names(res.bodyAsText()).none { "vless" in it.lowercase() || "протокол" in it.lowercase() })

        // The apps' list: every VLESS location with flag data and its own name, in the same order, ping by TCP.
        val vlessServers = u.api.servers(listOf("amneziawg", "vless")).servers.filter { Protocols.VLESS in it.protocols }
        assertEquals(listOf("Амстердам", "Финляндия"), vlessServers.map { it.countryName })
        assertEquals(listOf("NL", "FI"), vlessServers.map { it.countryCode })
        assertTrue(vlessServers.all { it.ping?.method == "tcp" && it.ping?.port == 443 })
        // Old apps (no supportedProtocols) never see them.
        assertTrue(u.api.servers().servers.none { Protocols.VLESS in it.protocols })

        // The key page shows every location; the website's key has all links.
        val page = b.createClient {}.get("/k/$token").bodyAsText()
        assertTrue("🇫🇮 Финляндия (запасной)" in page && "🇳🇱 Амстердам" in page, page)
        assertEquals(links, u.api.keys(listOf("vless")).vless!!.links)
        assertEquals("🇳🇱 Амстердам", u.api.keys(listOf("vless")).vless!!.name)
        // ?lang=de names the backups in German (the country names are the owner's, in Russian).
        assertEquals("🇫🇮 Финляндия (Reserve)", names(sub(token, "?lang=de").bodyAsText())[3])
        assertTrue(fi.id.isNotEmpty())
    }

    @Test fun `subscription headers carry the month's traffic, the limit, the end of access and support`() = harness(mapOf("PUBLIC_URL" to "https://boop.example", "SUPPORT_URL" to "https://t.me/boop_support")) {
        val fi = node("FI", "fi.example.com")
        val nl = node("NL", "nl.example.com")
        val u = signIn("u@example.com")
        services.vlessKeys.sync()
        val uuid = uuidOf(u.session.user.id)
        // Traffic on the replica node (not the key's home) is the user's too.
        val home = ctx.db.run { it.one("SELECT t.server_id FROM tunnels t JOIN vless_keys k ON k.device_id = t.device_id")!!.str("server_id") }
        val other = if (home == fi.id) nl else fi
        services.nodes.report(other.token, NodeReportReq("vless", listOf(NodePeerCounter(uuid, rx = 1_000, tx = 50_000, lastHandshake = clock.epochSecond))))
        services.traffic.collect()
        val res = sub(tokenOf(u.session.user.id))
        // Free plan: 10 GB a month; what the node received is the user's upload.
        assertEquals("upload=1000; download=50000; total=10000000000; expire=0", res.headers["subscription-userinfo"])
        assertEquals("12", res.headers["profile-update-interval"])
        assertEquals("https://t.me/boop_support", res.headers["support-url"])
        assertEquals("https://boop.example/k/${tokenOf(u.session.user.id)}", res.headers["profile-web-page-url"])
        assertEquals("base64:" + Base64.getEncoder().encodeToString("Boop VPN".toByteArray()), res.headers["profile-title"])
        assertEquals(other.id, ctx.db.run { it.one("SELECT server_id FROM traffic_samples")!!.str("server_id") })
        // The replica keeps its own counters: the same report again adds nothing.
        services.nodes.report(other.token, NodeReportReq("vless", listOf(NodePeerCounter(uuid, rx = 1_000, tx = 50_000, lastHandshake = clock.epochSecond))))
        services.traffic.collect()
        assertEquals("upload=1000; download=50000; total=10000000000; expire=0", sub(tokenOf(u.session.user.id)).headers["subscription-userinfo"])
        // A paid plan: no limit, the end of access as a Unix time.
        buy(u.api)
        val paid = sub(tokenOf(u.session.user.id)).headers["subscription-userinfo"]!!
        assertTrue(Regex("^upload=1000; download=50000; total=0; expire=\\d{10}$").matches(paid), paid)
    }

    @Test fun `sing-box and Clash Meta profiles with an Auto group, chosen by format or User-Agent`() = harness {
        node("FI", "fi.example.com", sortOrder = 1)
        node("NL", "nl.example.com", sortOrder = 2)
        val u = signIn("u@example.com")
        services.vlessKeys.sync()
        val uuid = uuidOf(u.session.user.id)
        val token = tokenOf(u.session.user.id)

        for (res in listOf(sub(token, "?format=singbox"), sub(token, ua = "SFA/1.12.4 (Android 14)"), sub(token, ua = "sing-box 1.12.0"))) {
            assertEquals(HttpStatusCode.OK, res.status)
            assertTrue(res.headers["Content-Type"]!!.startsWith("application/json"))
            assertTrue(res.headers["subscription-userinfo"]!!.startsWith("upload=0; download=0;"))
            val doc = Json.parseToJsonElement(res.bodyAsText()).jsonObject
            val outbounds = doc["outbounds"]!!.jsonArray.map { it.jsonObject }
            val select = outbounds.first()
            assertEquals("selector", select["type"]!!.jsonPrimitive.content)
            assertEquals(listOf("Авто (лучший)", "🇫🇮 Финляндия", "🇳🇱 Нидерланды"), select["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content })
            val auto = outbounds.single { it["type"]!!.jsonPrimitive.content == "urltest" }
            assertEquals("Авто (лучший)", auto["tag"]!!.jsonPrimitive.content)
            assertEquals(listOf("🇫🇮 Финляндия", "🇳🇱 Нидерланды"), auto["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content })
            // Vision only (sing-box has no XHTTP), each with Reality and the user's UUID.
            val vless = outbounds.filter { it["type"]!!.jsonPrimitive.content == "vless" }
            assertEquals(2, vless.size)
            for (o in vless) {
                assertEquals(uuid, o["uuid"]!!.jsonPrimitive.content)
                assertEquals("xtls-rprx-vision", o["flow"]!!.jsonPrimitive.content)
                assertEquals(443, o["server_port"]!!.jsonPrimitive.content.toInt())
                val tls = o["tls"]!!.jsonObject
                assertEquals(pbk, tls["reality"]!!.jsonObject["public_key"]!!.jsonPrimitive.content)
                assertEquals("a1b2c3d4", tls["reality"]!!.jsonObject["short_id"]!!.jsonPrimitive.content)
            }
            // Every outbound a group names exists.
            val tags = outbounds.map { it["tag"]!!.jsonPrimitive.content }.toSet()
            assertTrue(outbounds.flatMap { (it["outbounds"] as? JsonArray).orEmpty().map { e -> e.jsonPrimitive.content } }.all { it in tags })
            assertEquals("Boop VPN", doc["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
        }

        for (res in listOf(sub(token, "?format=clash"), sub(token, ua = "ClashMetaForAndroid/2.11.5.Meta"), sub(token, ua = "mihomo/1.19.12"), sub(token, ua = "FlClash/v0.8 clash-verge"))) {
            assertEquals(HttpStatusCode.OK, res.status)
            assertTrue(res.headers["Content-Type"]!!.startsWith("text/yaml"))
            val y = res.bodyAsText()
            assertTrue("proxies:\n  - name: \"🇫🇮 Финляндия\"\n    type: vless\n    server: \"fi.example.com\"\n    port: 443\n    uuid: \"$uuid\"" in y, y)
            assertTrue("    reality-opts:\n      public-key: \"$pbk\"\n      short-id: \"a1b2c3d4\"" in y, y)
            assertTrue("  - name: \"Авто (лучший)\"\n    type: url-test\n    proxies: [\"🇫🇮 Финляндия\", \"🇳🇱 Нидерланды\"]" in y, y)
            assertTrue("    type: select\n    proxies: [\"Авто (лучший)\", \"🇫🇮 Финляндия\", \"🇳🇱 Нидерланды\"]" in y, y)
            assertTrue(y.trimEnd().endsWith("- \"MATCH,Boop VPN\""), y)
            assertFalse("xhttp" in y)
        }

        // Happ, v2RayTun, v2rayNG, Hiddify and unknown clients: base64 with XHTTP too; ?format=base64 wins over the User-Agent.
        for (res in listOf(sub(token, ua = "Happ/3.1"), sub(token, ua = "v2rayNG/1.9"), sub(token, ua = "v2RayTun/5"), sub(token, ua = "HiddifyNext/2.5.7 (android) like ClashMeta v2ray sing-box"), sub(token), sub(token, "?format=base64", ua = "mihomo/1.19"))) {
            assertTrue(res.headers["Content-Type"]!!.startsWith("text/plain"))
            assertEquals(4, String(Base64.getDecoder().decode(res.bodyAsText())).split("\n").size)
        }
        assertEquals(VlessSubscription.Format.SINGBOX, VlessSubscription.format("sing-box", "Happ"))
        assertEquals(VlessSubscription.Format.BASE64, VlessSubscription.format(null, null))
    }

    @Test fun `one VLESS node gives the same subscription as before`() = harness {
        val fi = node("FI", "fi.example.com")
        val u = signIn("u@example.com")
        services.vlessKeys.sync()
        val uuid = uuidOf(u.session.user.id)
        val lines = String(Base64.getDecoder().decode(sub(tokenOf(u.session.user.id)).bodyAsText())).split("\n")
        assertEquals(Vless.links(settings(fi.host).params(uuid), "x").map { it.substringBefore('#') }, lines.map { it.substringBefore('#') })
        assertEquals(listOf("🇫🇮 Финляндия", "🇫🇮 Финляндия (запасной)"), lines.map { java.net.URLDecoder.decode(it.substringAfter('#'), "UTF-8") })
        assertEquals(0, ctx.db.run { it.one("SELECT count(*)::int AS n FROM vless_replicas")!!.int("n") })
        assertEquals(listOf(uuid), peersOf(fi))
    }

    @Test fun `the balancer fragment for a cascade entry, the entry's user on the exits, Auto first`() = harness(mapOf("VLESS_BALANCER" to "1")) {
        val entry = node("RU", "entry.example.com", role = "entry")
        val x1 = node("FI", "x1.example.com", role = "exit")
        val x2 = node("NL", "x2.example.com", role = "exit")
        update(x2) { it.copy(inSubscription = false) }
        val cascadeUuid = ctx.db.run { it.one("SELECT cascade_uuid FROM servers WHERE id=?", entry.id)!!.str("cascade_uuid") }
        assertTrue(Vless.isUuid(cascadeUuid))

        val f = services.nodes.balancer(entry.id)
        val outbounds = f["outbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(XrayBalancer.PREFIX + x1.id, XrayBalancer.PREFIX + x2.id).sorted(), outbounds.map { it["tag"]!!.jsonPrimitive.content }.sorted())
        for (o in outbounds) {
            val vnext = o["settings"]!!.jsonObject["vnext"]!!.jsonArray.single().jsonObject
            assertEquals(443, vnext["port"]!!.jsonPrimitive.content.toInt())
            val user = vnext["users"]!!.jsonArray.single().jsonObject
            assertEquals(cascadeUuid, user["id"]!!.jsonPrimitive.content)
            assertEquals("xtls-rprx-vision", user["flow"]!!.jsonPrimitive.content)
            val rs = o["streamSettings"]!!.jsonObject["realitySettings"]!!.jsonObject
            assertEquals(pbk, rs["publicKey"]!!.jsonPrimitive.content)
            assertFalse("privateKey" in rs)
        }
        val routing = f["routing"]!!.jsonObject
        val balancer = routing["balancers"]!!.jsonArray.single().jsonObject
        assertEquals("leastPing", balancer["strategy"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(listOf(XrayBalancer.PREFIX), balancer["selector"]!!.jsonArray.map { it.jsonPrimitive.content })
        val rule = routing["rules"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("vision", "xhttp"), rule["inboundTag"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(XrayBalancer.BALANCER, rule["balancerTag"]!!.jsonPrimitive.content)
        assertEquals(listOf(XrayBalancer.PREFIX), f["observatory"]!!.jsonObject["subjectSelector"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse("privateKey" in f.toString())

        // The exits' agents add the entry's own user, the entry's agent does not.
        assertTrue(cascadeUuid in peersOf(x1))
        assertTrue(cascadeUuid in peersOf(x2))
        assertFalse(cascadeUuid in peersOf(entry))

        // The subscription: "Auto" (the entry) first, then the listed exits; the hidden exit is not there.
        val u = signIn("u@example.com")
        services.vlessKeys.sync()
        assertEquals(listOf("⚡ Авто", "⚡ Авто (запасной)", "🇫🇮 Финляндия", "🇫🇮 Финляндия (запасной)"), names(sub(tokenOf(u.session.user.id)).bodyAsText()))
        assertEquals("Авто", u.api.servers(listOf("vless")).servers.first { it.id == entry.id }.countryName)

        // An entry no more: its user leaves the exits; a node that is not an entry has no fragment.
        update(entry) { it.copy(cascadeRole = "") }
        assertFalse(cascadeUuid in peersOf(x1))
        assertEquals("BAD_REQUEST", runCatching { services.nodes.balancer(entry.id) }.exceptionOrNull()?.let { (it as AppError).code.name })
        // Through the admin API.
        val owner = signIn("owner@test.dev")
        update(entry) { it.copy(cascadeRole = "entry") }
        val raw = b.createClient {}.get("/v1/admin/nodes/${entry.id}/balancer") { header("Authorization", "Bearer ${owner.session.token}") }
        assertEquals(HttpStatusCode.OK, raw.status)
        assertTrue(Json.parseToJsonElement(raw.bodyAsText()).jsonObject["observatory"] is JsonObject)
        assertEquals(HttpStatusCode.Forbidden, b.createClient {}.get("/v1/admin/nodes/${entry.id}/balancer") { header("Authorization", "Bearer ${u.session.token}") }.status)
        assertTrue(owner.api.adminNodes().vlessBalancer)
        assertEquals("entry", owner.api.adminNodes().nodes.single { it.id == entry.id }.cascadeRole)
    }

    @Test fun `without VLESS_BALANCER cascade marks change nothing for users`() = harness {
        val entry = node("RU", "entry.example.com", role = "entry")
        val x1 = node("FI", "x1.example.com", role = "exit")
        val cascadeUuid = ctx.db.run { it.one("SELECT cascade_uuid FROM servers WHERE id=?", entry.id)!!.str("cascade_uuid") }
        assertFalse(cascadeUuid in peersOf(x1))
        assertEquals("NOT_FOUND", runCatching { services.nodes.balancer(entry.id) }.exceptionOrNull()?.let { (it as AppError).code.name })
        val u = signIn("u@example.com")
        services.vlessKeys.sync()
        assertTrue(names(sub(tokenOf(u.session.user.id)).bodyAsText()).none { "Авто" in it })
    }

    @Test fun `VLESS devices of the apps are on every node too, and a new provision replaces them`() = harness {
        val fi = node("FI", "fi.example.com")
        val nl = node("NL", "nl.example.com")
        val u = signIn("u@example.com")
        val cfg = u.api.provisionTunnel(TunnelProvisionReq(Protocols.VLESS, serverId = fi.id))
        val uuid = Protocols.decode<Vless.ServerParams>(cfg.params).uuid
        assertTrue(uuid in peersOf(fi) && uuid in peersOf(nl))
        val again = Protocols.decode<Vless.ServerParams>(u.api.provisionTunnel(TunnelProvisionReq(Protocols.VLESS, serverId = nl.id)).params).uuid
        assertFalse(uuid in peersOf(fi) || uuid in peersOf(nl))
        assertTrue(again in peersOf(fi) && again in peersOf(nl))
        // Moving the device's home keeps one copy per node.
        services.tunnels.moveDevice(u.session.deviceId, fi.id)
        assertEquals(listOf(again), peersOf(fi).filter { it == again })
        assertEquals(listOf(again), peersOf(nl).filter { it == again })
    }
}
