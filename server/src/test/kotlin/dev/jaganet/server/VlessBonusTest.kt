package dev.jaganet.server

import dev.jaganet.api.Protocols
import dev.jaganet.api.TunnelProvisionReq
import dev.jaganet.api.Vless
import dev.jaganet.api.WireGuard
import dev.jaganet.server.db.Jsonb
import dev.jaganet.server.services.Keys
import dev.jaganet.server.services.VlessKeys
import dev.jaganet.server.services.VlessSyncResult
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.time.Duration
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #33: every user with access has one VLESS key, outside the plan's device limit. */
class VlessBonusTest {
    private val pbk = "_No2ExH-ajHLTIY4xDz1EjOQ6izyfCBAueJM73gZbh0"
    // XHTTP listed first on purpose: links still give Vision first.
    private val settings = Vless.NodeSettings(
        host = "ru1.vpn.example.com", publicKey = pbk, shortIds = listOf("a1b2c3d4"), sni = "ru1.vpn.example.com",
        transports = listOf(Vless.Transport("xhttp", 8443, path = "/s3cr3t"), Vless.Transport("tcp", 443)),
    )

    private suspend fun Harness.vlessNode(id: String, country: String = "FI") = ctx.db.run {
        it.exec(
            "INSERT INTO servers (id, name, city, country_code, subnet, protocols, max_peers) VALUES (?,?,'Helsinki',?,'10.9.0.0/16',?,5)",
            id, "VLESS $id", country, Jsonb("""{"vless":${Protocols.encode(settings)}}"""),
        )
    }

    private suspend fun Harness.bonus(userId: String) = ctx.db.run { sql ->
        sql.one(
            """SELECT k.uuid, k.link_token, t.server_id, t.peer_key FROM vless_keys k LEFT JOIN tunnels t ON t.device_id = k.device_id
                WHERE k.user_id=?::uuid""",
            userId,
        )
    }

    private suspend fun Harness.count(q: String) = ctx.db.run { it.one(q)!!.int("n") }

    @Test fun `the VLESS key is not counted in the device limit and can't be deleted`() = harness {
        vlessNode("v1")
        val u = signIn("user@example.com")
        assertEquals(1, u.api.me().entitlement.deviceLimit)
        assertEquals(1, services.vlessKeys.sync().created)
        // Free plan, 1 device: the phone still gets its AmneziaWG tunnel next to the VLESS key.
        u.api.provisionTunnel(TunnelProvisionReq(Protocols.AMNEZIAWG, clientParams = Protocols.encode(WireGuard.ClientParams(key(1)))))
        assertEquals(1, u.api.me().usage.devicesUsed)
        // And a key link too would be over the limit: only real devices count.
        assertEquals("DEVICE_LIMIT", code { u.api.createKey() })

        // Old apps don't see it; apps with vless do, marked outside the limit, named by country.
        assertEquals(1, u.api.devices().devices.size)
        val list = u.api.devices(listOf("amneziawg", "vless")).devices
        val v = list.single { it.outsideLimit }
        assertEquals("🇫🇮 Финляндия", v.name)
        assertEquals(Protocols.VLESS, v.protocol)
        assertEquals("BAD_REQUEST", code { u.api.removeDevice(v.id) })
        assertNull(u.api.keys().vless)
        val key = assertNotNull(u.api.keys(listOf("amneziawg", "vless")).vless)
        assertTrue(key.active)
        assertEquals(2, key.links.size)
        assertTrue(key.subscriptionUrl.endsWith("/vless"))

        // The admin panel shows it in the user's devices, outside the limit.
        val admin = services.analytics.user(u.session.user.id, null)
        assertTrue(admin.devices.single { it.id == v.id }.outsideLimit)
        assertTrue(admin.devices.filter { it.id != v.id }.none { it.outsideLimit })
    }

    @Test fun `backfill is idempotent and keeps the UUID`() = harness {
        val a = signIn("a@example.com")
        signIn("b@example.com")
        // No VLESS node yet: nothing to make.
        assertEquals(VlessSyncResult(), services.vlessKeys.sync())
        vlessNode("v1")
        val first = services.vlessKeys.sync()
        assertEquals(2, first.created)
        val uuid = bonus(a.session.user.id)!!.str("uuid")
        assertEquals(uuid, bonus(a.session.user.id)!!.str("peer_key"))
        // Again and again: no duplicates, nothing changes.
        assertEquals(VlessSyncResult(), services.vlessKeys.sync())
        assertEquals(VlessSyncResult(), services.vlessKeys.sync())
        assertEquals(2, count("SELECT count(*)::int AS n FROM vless_keys"))
        assertEquals(2, count("SELECT count(*)::int AS n FROM devices WHERE kind = 'vless_bonus'"))
        assertEquals(2, count("SELECT count(*)::int AS n FROM tunnels WHERE protocol = 'vless'"))
        // Opening the keys page runs the same for one user: still one key.
        a.api.keys(listOf("vless"))
        assertEquals(2, count("SELECT count(*)::int AS n FROM vless_keys"))
        assertEquals(uuid, bonus(a.session.user.id)!!.str("uuid"))
    }

    @Test fun `ended access turns the key off, a purchase turns it on with the same UUID`() = harness(mapOf("FREE_DAYS" to "3")) {
        vlessNode("v1")
        val u = signIn("user@example.com")
        val id = u.session.user.id
        assertEquals(1, services.vlessKeys.sync().created)
        val before = bonus(id)!!
        val uuid = before.str("uuid")

        ctx.db.run { it.exec("UPDATE users SET created_at=? WHERE id=?::uuid", clock.minus(Duration.ofDays(10)), id) }
        assertEquals(1, services.vlessKeys.sync().disabled)
        assertNull(bonus(id)!!.strOrNull("server_id"))
        assertEquals(0, count("SELECT count(*)::int AS n FROM tunnels WHERE protocol = 'vless'"))
        assertEquals(VlessSyncResult(), services.vlessKeys.sync())
        val off = assertNotNull(u.api.keys(listOf("vless")).vless)
        assertTrue(!off.active && off.links.isEmpty())
        val raw = b.createClient {}
        assertEquals(HttpStatusCode.NotFound, raw.get("/k/${before.str("link_token")}/vless").status)

        buy(u.api)
        assertEquals(1, services.vlessKeys.sync().enabled)
        val after = bonus(id)!!
        assertEquals(uuid, after.str("uuid"))
        assertEquals(uuid, after.str("peer_key"))
        assertEquals(before.str("link_token"), after.str("link_token"))
    }

    @Test fun `a switched off node moves the key to another one with the same UUID`() = harness {
        vlessNode("v1")
        val u = signIn("user@example.com")
        services.vlessKeys.sync()
        val uuid = bonus(u.session.user.id)!!.str("uuid")
        vlessNode("v2", "NL")
        ctx.db.run { it.exec("UPDATE servers SET active=false WHERE id='v1'") }
        assertEquals(1, services.vlessKeys.sync().moved)
        val now = bonus(u.session.user.id)!!
        assertEquals("v2", now.str("server_id"))
        assertEquals(uuid, now.str("peer_key"))
        assertEquals("🇳🇱 Нидерланды", u.api.keys(listOf("vless")).vless!!.name)
    }

    @Test fun `key links for AmneziaWG are unchanged and give the VLESS subscription`() = harness(mapOf("PUBLIC_URL" to "https://boop.example")) {
        val u = signIn("user@example.com")
        buy(u.api)
        val awg = u.api.createKey()
        val token = awg.pageUrl.substringAfterLast("/k/")
        val conf = services.keys.config(u.session.user.id, awg.id)!!.text
        val raw = b.createClient {}
        // Before any VLESS node: the AmneziaWG key link as always, no VLESS subscription.
        assertEquals(conf, raw.get("/k/$token/${Keys.CONFIG_FILE}").bodyAsText())
        assertEquals(conf, raw.get("/k/$token/JagaNet.conf").bodyAsText())
        assertEquals(HttpStatusCode.OK, raw.get("/k/$token/qr.png").status)
        assertEquals(HttpStatusCode.OK, raw.get("/k/$token").status)
        assertEquals(HttpStatusCode.NotFound, raw.get("/k/$token/vless").status)

        vlessNode("v1")
        services.vlessKeys.sync()
        // The same files after the VLESS key arrived; the page links to it.
        assertEquals(conf, raw.get("/k/$token/${Keys.CONFIG_FILE}").bodyAsText())
        assertEquals(conf, services.keys.config(u.session.user.id, awg.id)!!.text)
        val b0 = bonus(u.session.user.id)!!
        val vt = b0.str("link_token")
        assertTrue("/k/$vt" in raw.get("/k/$token").bodyAsText())

        // The subscription: base64 of the links, Vision first, XHTTP second, named by country.
        val uuid = b0.str("uuid")
        val res = raw.get("/k/$token/vless")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("no-store", res.headers["Cache-Control"])
        assertEquals("base64:" + Base64.getEncoder().encodeToString("Boop VPN".toByteArray()), res.headers["profile-title"])
        assertTrue(res.headers["subscription-userinfo"]!!.startsWith("upload=0; download=0; total=0; expire="))
        val lines = String(Base64.getDecoder().decode(res.bodyAsText())).split("\n")
        val name = Vless.encode("🇫🇮 Финляндия")
        assertEquals(
            listOf(
                "vless://$uuid@ru1.vpn.example.com:443?encryption=none&security=reality&pbk=$pbk&sid=a1b2c3d4&sni=ru1.vpn.example.com&fp=firefox&type=tcp&flow=xtls-rprx-vision#$name",
                "vless://$uuid@ru1.vpn.example.com:8443?encryption=none&security=reality&pbk=$pbk&sid=a1b2c3d4&sni=ru1.vpn.example.com&fp=firefox&type=xhttp&path=%2Fs3cr3t&mode=auto#${name}%202",
            ),
            lines,
        )
        // The VLESS key's own token: the same subscription, its page and QR codes.
        assertEquals(res.bodyAsText(), raw.get("/k/$vt/vless").bodyAsText())
        val page = raw.get("/k/$vt").bodyAsText()
        assertTrue("🇫🇮 Финляндия" in page && "https://boop.example/k/$vt/vless" in page, page)
        assertTrue("протокол" !in page.lowercase())
        assertEquals(HttpStatusCode.OK, raw.get("/k/$vt/vless/0.png").status)
        assertEquals(HttpStatusCode.OK, raw.get("/k/$vt/vless/sub.png").status)
        assertEquals(HttpStatusCode.NotFound, raw.get("/k/$vt/vless/7.png").status)
        assertEquals(HttpStatusCode.NotFound, raw.get("/k/nope/vless").status)
        val key = u.api.keys(listOf("vless")).vless!!
        assertEquals("https://boop.example/k/$vt/vless", key.subscriptionUrl)
        assertEquals(lines, key.links)
        assertEquals(lines, VlessKeys.linksOf(Vless.ServerParams(uuid, settings.params(uuid).variants), "🇫🇮 Финляндия"))
    }
}
