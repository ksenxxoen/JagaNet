package dev.jaganet.server

import dev.jaganet.api.NodeReq
import dev.jaganet.api.Vless
import dev.jaganet.server.services.DohServer
import dev.jaganet.server.services.NodeRegisterReq
import dev.jaganet.server.services.SubscriptionOptions
import dev.jaganet.server.services.VlessSubscription
import dev.jaganet.server.services.VlessSubscription.Format
import dev.jaganet.server.services.XrayBalancer
import dev.jaganet.server.services.XrayConfigs
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #59: DNS and the QUIC block in every client's subscription; the UDP 443 block in the entry's fragment. */
class VlessSubscriptionClientsTest {
    private val pbk = "_No2ExH-ajHLTIY4xDz1EjOQ6izyfCBAueJM73gZbh0"

    private fun settings(host: String) = Vless.NodeSettings(
        host = host, publicKey = pbk, shortIds = listOf("a1b2c3d4"), sni = host,
        transports = listOf(Vless.Transport("tcp", 443), Vless.Transport("xhttp", 8443, path = "/p")),
    )

    private suspend fun Harness.node(cc: String, host: String, sortOrder: Int) {
        val res = services.nodes.create(NodeReq("VLESS $cc", "City", cc, maxPeers = 100, protocol = "vless", vless = settings(host), sortOrder = sortOrder))
        val token = res.command.substringAfter("NODE_TOKEN=").substringBefore(' ')
        services.nodes.register(token, NodeRegisterReq("203.0.113.${(10..250).random()}", protocol = "vless"))
    }

    /** Two locations (Vision and XHTTP each) and a user with a VLESS key; returns the key's token and UUID. */
    private suspend fun Harness.twoLocations(): Pair<String, String> {
        node("FI", "fi.example.com", 1)
        node("NL", "nl.example.com", 2)
        val u = signIn("u@example.com")
        services.vlessKeys.sync()
        return ctx.db.run { it.one("SELECT link_token, uuid FROM vless_keys WHERE user_id=?::uuid", u.session.user.id)!! }.let { it.str("link_token") to it.str("uuid") }
    }

    private suspend fun Harness.sub(token: String, query: String = "", ua: String? = null): HttpResponse =
        b.createClient {}.get("/k/$token/vless$query") { ua?.let { header("User-Agent", it) } }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.arr(k: String) = this[k]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.strs(k: String) = this[k]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test fun `client and format by User-Agent and parameter`() {
        val on = SubscriptionOptions()
        val off = on.copy(xrayJson = false)
        assertEquals(Format.XRAY_JSON, VlessSubscription.format(null, "Happ/3.7.0/Android/1730000000", on))
        assertEquals(Format.XRAY_JSON, VlessSubscription.format(null, "Happ/1.63.1", on))
        assertEquals(Format.BASE64, VlessSubscription.format(null, "Happ/1.63.0", on))
        assertEquals(Format.BASE64, VlessSubscription.format(null, "Happ/1.9", on))
        assertEquals(Format.XRAY_JSON, VlessSubscription.format(null, "v2RayTun/5.23.74 (Android 14)", on))
        assertEquals(Format.XRAY_JSON, VlessSubscription.format(null, "v2raytun/ios", on))
        assertEquals(Format.BASE64, VlessSubscription.format(null, "Happ/3.7.0", off))
        assertEquals(Format.BASE64, VlessSubscription.format(null, "v2RayTun/5", off))
        assertEquals(Format.BASE64, VlessSubscription.format(null, "v2rayNG/1.10.5", on))
        assertEquals(Format.BASE64, VlessSubscription.format(null, "HiddifyNext/2.5.7 (android) like ClashMeta v2ray sing-box", on))
        assertEquals(Format.SINGBOX, VlessSubscription.format(null, "SFA/1.12.4", on))
        assertEquals(Format.CLASH, VlessSubscription.format(null, "mihomo/1.19", on))
        // ?format= wins, in both directions and with the JSON flag off.
        assertEquals(Format.XRAY_JSON, VlessSubscription.format("json", "v2rayNG/1.10.5", off))
        assertEquals(Format.XRAY_JSON, VlessSubscription.format("xray", null, on))
        assertEquals(Format.BASE64, VlessSubscription.format("base64", "Happ/3.7.0", on))
        assertEquals(VlessSubscription.Client.HAPP, VlessSubscription.client("Happ/3.7.0"))
        assertEquals(VlessSubscription.Client.V2RAYTUN, VlessSubscription.client("v2RayTun/5"))
        assertEquals(VlessSubscription.Client.OTHER, VlessSubscription.client("v2rayNG/1.10"))
        assertEquals(VlessSubscription.Client.OTHER, VlessSubscription.client(null))
    }

    @Test fun `env flags and DNS addresses`() {
        val d = Config.load(mapOf("JAGANET_MODE" to "simulation"))
        assertEquals(SubscriptionOptions.DEFAULT_DNS, d.vlessSub.dns.map { it.url })
        assertTrue(d.vlessSub.blockQuic && d.vlessSub.xrayJson && d.entryBlockQuic)
        val c = Config.load(
            mapOf(
                "JAGANET_MODE" to "simulation", "VLESS_SUB_DNS" to " https://dns.example.net/q , https://9.9.9.9:8443/dns-query",
                "VLESS_SUB_BLOCK_QUIC" to "0", "VLESS_SUB_XRAY_JSON" to "off", "VLESS_ENTRY_BLOCK_QUIC" to "0",
            ),
        )
        assertEquals(listOf(DohServer("https://dns.example.net/q", "dns.example.net", 443, "/q"), DohServer("https://9.9.9.9:8443/dns-query", "9.9.9.9", 8443, "/dns-query")), c.vlessSub.dns)
        assertFalse(c.vlessSub.dns[0].isIp)
        assertTrue(c.vlessSub.dns[1].isIp)
        assertFalse(c.vlessSub.blockQuic || c.vlessSub.xrayJson || c.entryBlockQuic)
        assertEquals(emptyList(), Config.load(mapOf("JAGANET_MODE" to "simulation", "VLESS_SUB_DNS" to "off")).vlessSub.dns)
        assertFailsWith<IllegalArgumentException> { Config.load(mapOf("JAGANET_MODE" to "simulation", "VLESS_SUB_DNS" to "8.8.8.8")) }
        assertFailsWith<IllegalArgumentException> { Config.load(mapOf("JAGANET_MODE" to "simulation", "VLESS_SUB_DNS" to "http://dns.example.net/q")) }
    }

    @Test fun `Happ and v2RayTun get Xray configs with remote DNS and the QUIC block`() = harness(mapOf("PUBLIC_URL" to "https://boop.example")) {
        val (token, uuid) = twoLocations()
        for (res in listOf(sub(token, ua = "Happ/3.7.0/Android"), sub(token, ua = "v2RayTun/5.23.74"), sub(token, "?format=json", ua = "v2rayNG/1.10.5"))) {
            assertEquals(HttpStatusCode.OK, res.status)
            assertTrue(res.headers["Content-Type"]!!.startsWith("application/json"))
            // The subscription's own headers stay; no routing profile with JSON.
            assertTrue(res.headers["subscription-userinfo"]!!.startsWith("upload=0; download=0;"))
            assertEquals("12", res.headers["profile-update-interval"])
            assertNull(res.headers["routing"])
            val configs = Json.parseToJsonElement(res.bodyAsText()).jsonArray.map { it.jsonObject }
            // "Авто (лучший)", then the base64 list's links in its order and names.
            assertEquals(
                listOf("Авто (лучший)", "🇫🇮 Финляндия", "🇫🇮 Финляндия (запасной)", "🇳🇱 Нидерланды", "🇳🇱 Нидерланды (запасной)"),
                configs.map { it.str("remarks") },
            )
            for (c in configs) {
                val dns = c["dns"]!!.jsonObject
                assertEquals(SubscriptionOptions.DEFAULT_DNS, dns.strs("servers"))
                assertEquals("UseIPv4", dns.str("queryStrategy"))
                assertFalse("fakedns" in c.toString().lowercase())
                val outbounds = c.arr("outbounds")
                assertEquals("proxy", outbounds.first().str("tag"))
                assertEquals(listOf("direct", "block", "dns-out"), outbounds.takeLast(3).map { it.str("tag") })
                val rules = c["routing"]!!.jsonObject.arr("rules")
                assertEquals(mapOf("type" to "field", "network" to "udp", "port" to "443", "outboundTag" to "block"), rules[0].mapValues { it.value.jsonPrimitive.content })
                assertEquals("dns-out", rules[1].str("outboundTag"))
                assertEquals("53", rules[1].str("port"))
                assertEquals("direct", rules[2].str("outboundTag"))
                assertEquals(listOf("127.0.0.1"), c.arr("inbounds").map { it.str("listen") }.distinct())
                for (o in outbounds.filter { it.str("protocol") == "vless" }) {
                    val user = o["settings"]!!.jsonObject.arr("vnext").single().arr("users").single()
                    assertEquals(uuid, user.str("id"))
                    assertEquals(pbk, o["streamSettings"]!!.jsonObject["realitySettings"]!!.jsonObject.str("publicKey"))
                }
            }
            // Auto: both Vision links behind a leastPing balancer with the observatory.
            val auto = configs.first()
            val vless = auto.arr("outbounds").filter { it.str("protocol") == "vless" }
            assertEquals(listOf("proxy", "proxy-2"), vless.map { it.str("tag") })
            assertEquals(listOf("fi.example.com", "nl.example.com"), vless.map { it["settings"]!!.jsonObject.arr("vnext").single().str("address") })
            assertTrue(vless.all { it["streamSettings"]!!.jsonObject.str("network") == "tcp" })
            val routing = auto["routing"]!!.jsonObject
            val balancer = routing.arr("balancers").single()
            assertEquals("leastPing", balancer["strategy"]!!.jsonObject.str("type"))
            assertEquals(listOf("proxy"), balancer.strs("selector"))
            assertEquals("auto", routing.arr("rules").last().str("balancerTag"))
            assertEquals(listOf("proxy"), auto["observatory"]!!.jsonObject.strs("subjectSelector"))
            // A backup is the XHTTP link.
            val backup = configs[2].arr("outbounds").first()
            assertEquals("xhttp", backup["streamSettings"]!!.jsonObject.str("network"))
            assertEquals("/p", backup["streamSettings"]!!.jsonObject["xhttpSettings"]!!.jsonObject.str("path"))
            assertEquals(8443, backup["settings"]!!.jsonObject.arr("vnext").single()["port"]!!.jsonPrimitive.content.toInt())
            assertFalse("balancers" in configs[2]["routing"]!!.jsonObject)
        }
        // Everyone else keeps the plain links, without a routing header.
        val plain = sub(token, ua = "v2rayNG/1.10.5")
        assertTrue(plain.headers["Content-Type"]!!.startsWith("text/plain"))
        assertEquals(4, String(Base64.getDecoder().decode(plain.bodyAsText())).split("\n").size)
        assertNull(plain.headers["routing"])
    }

    @Test fun `flags off - no DNS, no QUIC block in the Xray configs`() = harness(mapOf("VLESS_SUB_DNS" to "off", "VLESS_SUB_BLOCK_QUIC" to "0")) {
        val (token, _) = twoLocations()
        val configs = Json.parseToJsonElement(sub(token, ua = "Happ/3.7.0").bodyAsText()).jsonArray.map { it.jsonObject }
        for (c in configs) {
            assertFalse("dns" in c)
            assertEquals(listOf("direct", "block"), c.arr("outbounds").filter { it.str("protocol") != "vless" }.map { it.str("tag") })
            assertTrue(c["routing"]!!.jsonObject.arr("rules").none { "port" in it })
        }
    }

    @Test fun `with VLESS_SUB_XRAY_JSON=0 Happ and v2RayTun get the links and their routing header`() = harness(mapOf("VLESS_SUB_XRAY_JSON" to "0")) {
        val (token, _) = twoLocations()
        // Happ: a routing profile with the remote DNS, no fake DNS.
        val happ = sub(token, ua = "Happ/3.7.0")
        assertTrue(happ.headers["Content-Type"]!!.startsWith("text/plain"))
        assertEquals(4, String(Base64.getDecoder().decode(happ.bodyAsText())).split("\n").size)
        val link = happ.headers["routing"]!!
        assertTrue(link.startsWith("happ://routing/onadd/"), link)
        val profile = Json.parseToJsonElement(String(Base64.getDecoder().decode(link.removePrefix("happ://routing/onadd/")))).jsonObject
        assertEquals("Boop VPN", profile.str("Name"))
        assertEquals("DoH", profile.str("RemoteDNSType"))
        assertEquals("https://1.1.1.1/dns-query", profile.str("RemoteDNSDomain"))
        assertEquals("1.1.1.1", profile.str("RemoteDNSIP"))
        assertEquals("https://8.8.8.8/dns-query", profile.str("DomesticDNSDomain"))
        assertEquals("false", profile.str("FakeDNS"))
        assertEquals("true", profile.str("GlobalProxy"))
        // v2RayTun: an Xray routing object with UDP 443 to block.
        val tun = sub(token, ua = "v2RayTun/5.23.74")
        assertTrue(tun.headers["Content-Type"]!!.startsWith("text/plain"))
        val routing = Json.parseToJsonElement(String(Base64.getDecoder().decode(tun.headers["routing"]!!))).jsonObject
        assertEquals("Boop VPN", routing.str("name"))
        val quic = routing.arr("rules").first()
        assertEquals(listOf("field", "udp", "443", "block"), listOf("type", "network", "port", "outboundTag").map { quic.str(it) })
        // Unknown clients: no routing header.
        assertNull(sub(token).headers["routing"])
        // Without DNS servers Happ gets no profile; without the QUIC block v2RayTun gets no routing.
        assertNull(VlessSubscription.happRouting(SubscriptionOptions(dns = emptyList())))
        assertNull(VlessSubscription.v2rayTunRouting(SubscriptionOptions(blockQuic = false)))
    }

    @Test fun `sing-box and Clash profiles carry the remote DNS and the QUIC block`() = harness {
        val (token, _) = twoLocations()
        val box = Json.parseToJsonElement(sub(token, "?format=singbox").bodyAsText()).jsonObject
        val remote = box["dns"]!!.jsonObject.arr("servers").first()
        assertEquals(listOf("https", "dns-remote", "1.1.1.1", "Boop VPN"), listOf("type", "tag", "server", "detour").map { remote.str(it) })
        val rules = box["route"]!!.jsonObject.arr("rules")
        val quic = rules.single { "network" in it }
        assertEquals(listOf("udp", "443", "reject"), listOf("network", "port", "action").map { quic.str(it) })
        // After sniffing and the DNS hijack, before the private networks.
        assertEquals(2, rules.indexOf(quic))

        val y = sub(token, "?format=clash").bodyAsText()
        assertTrue("dns:\n  enable: true\n  ipv6: false\n  enhanced-mode: redir-host\n  respect-rules: true\n  nameserver: [\"https://1.1.1.1/dns-query\", \"https://8.8.8.8/dns-query\"]" in y, y)
        assertFalse("fake-ip" in y)
        assertTrue("rules:\n  - \"AND,((NETWORK,UDP),(DST-PORT,443)),REJECT\"\n" in y, y)

        // A DoH server by name: sing-box resolves it with the local DNS, mihomo with plain DNS.
        val named = SubscriptionOptions(dns = listOf(DohServer.parse("https://dns.example.net/q")), blockQuic = false)
        val locations = ctx.db.run { sql -> dev.jaganet.server.services.VlessLocations.of(ctx, sql, sql.one("SELECT device_id FROM vless_keys")!!.str("device_id")) }
        val box2 = Json.parseToJsonElement(VlessSubscription.singBox(locations, options = named)).jsonObject
        val r2 = box2["dns"]!!.jsonObject.arr("servers").first()
        assertEquals(listOf("dns.example.net", "/q", "dns-local"), listOf("server", "path", "domain_resolver").map { r2.str(it) })
        assertTrue(box2["route"]!!.jsonObject.arr("rules").none { "network" in it })
        val y2 = VlessSubscription.clash(locations, options = named)
        assertTrue("  default-nameserver: " in y2)
        assertFalse("REJECT" in y2)
        assertEquals(1 + 4, XrayConfigs.configs(locations, options = named).size)
    }

    @Test fun `the entry fragment blocks UDP 443 unless VLESS_ENTRY_BLOCK_QUIC=0`() {
        val exit = XrayBalancer.Exit("x1", settings("x1.example.com"))
        val on = XrayBalancer.fragment(listOf("vision"), "11111111-2222-4333-8444-555555555555", listOf(exit))
        val rules = on["routing"]!!.jsonObject.arr("rules")
        assertEquals(listOf(XrayBalancer.BLOCK, null), rules.map { it["outboundTag"]?.jsonPrimitive?.content })
        assertEquals(XrayBalancer.BALANCER, rules[1].str("balancerTag"))
        assertTrue(on.arr("outbounds").any { it.str("tag") == XrayBalancer.BLOCK && it.str("protocol") == "blackhole" })
        // The balancer's selector never takes the blackhole.
        assertFalse(XrayBalancer.BLOCK.startsWith(XrayBalancer.PREFIX))
        val off = XrayBalancer.fragment(listOf("vision"), "11111111-2222-4333-8444-555555555555", listOf(exit), blockQuic = false)
        assertEquals(1, off["routing"]!!.jsonObject.arr("rules").size)
        assertTrue(off.arr("outbounds").none { it.str("tag") == XrayBalancer.BLOCK })
    }

    @Test fun `VLESS_ENTRY_BLOCK_QUIC=0 reaches the panel's fragment`() = harness(mapOf("VLESS_BALANCER" to "1", "VLESS_ENTRY_BLOCK_QUIC" to "0")) {
        val entry = services.nodes.create(NodeReq("VLESS RU", "City", "RU", maxPeers = 100, protocol = "vless", vless = settings("entry.example.com"), cascadeRole = "entry")).node
        services.nodes.create(NodeReq("VLESS FI", "City", "FI", maxPeers = 100, protocol = "vless", vless = settings("x1.example.com"), cascadeRole = "exit"))
        val f = services.nodes.balancer(entry.id)
        assertEquals(1, f["routing"]!!.jsonObject.arr("rules").size)
    }
}
