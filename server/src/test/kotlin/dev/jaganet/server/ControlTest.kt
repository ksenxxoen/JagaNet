package dev.jaganet.server

import dev.jaganet.api.CreateOrderReq
import dev.jaganet.api.NodeReq
import dev.jaganet.api.Protocols
import dev.jaganet.api.TunnelProvisionReq
import dev.jaganet.api.Vless
import dev.jaganet.api.WireGuard
import dev.jaganet.server.protocols.SimulatedDriver
import dev.jaganet.server.services.Actor
import dev.jaganet.server.services.ClientIps
import dev.jaganet.server.services.Control
import dev.jaganet.server.services.CtlSettings
import dev.jaganet.server.services.NodePeerCounter
import dev.jaganet.server.services.NodeRegisterReq
import dev.jaganet.server.services.NodeReportReq
import dev.jaganet.server.services.NodeSystem
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #39: the admin panel's control over users, payments, subscriptions and connections. */
class ControlTest {
    private val settings = Vless.NodeSettings(
        host = "fi1.vpn.example.com", publicKey = "_No2ExH-ajHLTIY4xDz1EjOQ6izyfCBAueJM73gZbh0", shortIds = listOf("a1b2c3d4"),
        sni = "fi1.vpn.example.com", transports = listOf(Vless.Transport("tcp", 443)),
    )

    private suspend fun Harness.http(method: HttpMethod, path: String, token: String?, body: String? = null): HttpResponse =
        b.createClient {}.request(path) {
            this.method = method
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }

    private suspend fun Harness.owner(): Pair<String, Actor> {
        val o = signIn("owner@test.dev")
        return o.session.token to Actor(o.session.user.id, o.session.user.email)
    }

    /** A VLESS node with an agent; returns its token. */
    private suspend fun Harness.vlessNode(ownerToken: String): Pair<String, String> {
        val owner = client(ownerToken)
        val id = owner.createNode(NodeReq("Finland", "Helsinki", "FI", protocol = Protocols.VLESS, vless = settings)).node.id
        val token = owner.newNodeToken(id).command.substringAfter("NODE_TOKEN=").substringBefore(' ')
        services.nodes.register(token, NodeRegisterReq("203.0.113.9", 443, Protocols.VLESS))
        return id to token
    }

    @Test fun `only the owner gets in, everyone else 403, nobody 401`() = harness {
        val user = signIn("user@example.com")
        val (ownerToken, _) = owner()
        val id = user.session.user.id
        val calls = listOf(
            HttpMethod.Get to "/v1/admin/control/dashboard",
            HttpMethod.Get to "/v1/admin/control/settings",
            HttpMethod.Put to "/v1/admin/control/settings",
            HttpMethod.Get to "/v1/admin/control/audit",
            HttpMethod.Get to "/v1/admin/users?q=user",
            HttpMethod.Get to "/v1/admin/users/$id",
            HttpMethod.Post to "/v1/admin/users/$id/extend",
            HttpMethod.Post to "/v1/admin/users/$id/grant",
            HttpMethod.Post to "/v1/admin/users/$id/plan",
            HttpMethod.Post to "/v1/admin/users/$id/block",
            HttpMethod.Post to "/v1/admin/users/$id/unblock",
            HttpMethod.Put to "/v1/admin/users/$id/note",
            HttpMethod.Post to "/v1/admin/users/$id/devices/$id/reissue",
            HttpMethod.Delete to "/v1/admin/users/$id/devices/$id",
            HttpMethod.Get to "/v1/admin/payments",
            HttpMethod.Get to "/v1/admin/payments.csv",
        )
        for ((m, path) in calls) {
            val body = if (m == HttpMethod.Get || m == HttpMethod.Delete) null else """{"days":5,"tariffId":"x","reason":"x","note":"x","sharingThreshold":3}"""
            assertEquals(HttpStatusCode.Forbidden, http(m, path, user.session.token, body).status, "$m $path as a user")
            assertEquals(HttpStatusCode.Unauthorized, http(m, path, null, body).status, "$m $path signed out")
        }
        // Nothing was done by the refused calls.
        assertEquals(0, ctx.db.run { it.one("SELECT count(*)::int AS n FROM admin_audit")!!.int("n") })
        // The owner does get in, and the answers carry no node secrets.
        val dash = http(HttpMethod.Get, "/v1/admin/control/dashboard", ownerToken)
        assertEquals(HttpStatusCode.OK, dash.status)
        val card = http(HttpMethod.Get, "/v1/admin/users/$id", ownerToken).bodyAsText()
        for (text in listOf(dash.bodyAsText(), card)) {
            assertFalse("publicKey" in text || "privateKey" in text || "obfuscation" in text || "node_key" in text, text)
        }
    }

    @Test fun `paid until counts bought time only, access until everything, and the current period start`() = harness {
        val u = signIn("buyer@example.com")
        val uid = u.session.user.id
        val (_, admin) = owner()
        val start = clock
        buy(u.api, MONTH)
        buy(u.api, YEAR)
        val c = services.control
        val paid = Instant.parse("2027-11-15T12:00:00Z") // 1 month, then 12 months after it
        ctx.db.run { sql ->
            assertEquals(paid, c.paidUntil(sql, uid))
            assertEquals(paid, c.accessUntil(sql, uid))
            assertEquals(start, c.currentStart(sql, uid))
        }
        // An admin grant comes after the bought time and is access, not payment.
        c.extend(admin, uid, 10)
        ctx.db.run { sql ->
            assertEquals(paid, c.paidUntil(sql, uid))
            assertEquals(paid.plus(Duration.ofDays(10)), c.accessUntil(sql, uid))
        }
        val card = c.card(uid)
        assertEquals(paid.toString(), card.user.paidUntil)
        assertEquals(paid.plus(Duration.ofDays(10)).toString(), card.user.accessUntil)
        assertEquals(start.toString(), card.startedAt)
        assertEquals("active", card.user.state)
        assertEquals(listOf("admin", "order", "order"), card.subscriptions.map { it.kind })
        assertEquals("upcoming", card.subscriptions.first().status)
        // Two years on: nothing left of either.
        clock = clock.plus(Duration.ofDays(800))
        ctx.db.run { sql ->
            assertNull(c.paidUntil(sql, uid))
            assertNull(c.accessUntil(sql, uid))
            assertNull(c.currentStart(sql, uid))
        }
        assertEquals("expired", c.card(uid).user.state)
        // A free user never paid.
        val free = signIn("free@example.com").session.user.id
        ctx.db.run { sql -> assertNull(c.paidUntil(sql, free)) }
        assertEquals("free", c.card(free).user.state)
    }

    @Test fun `payments table, totals, filters and CSV`() = harness {
        val (ownerToken, _) = owner()
        val a = signIn("a@example.com")
        val b = signIn("b@example.com")
        buy(a.api, MONTH)
        b.api.createOrder(CreateOrderReq(tariff(MONTH))) // never paid
        clock = clock.plus(Duration.ofDays(2))
        buy(a.api, YEAR)
        val month = ctx.db.run { it.one("SELECT amount_minor FROM orders WHERE status='paid' ORDER BY created_at LIMIT 1")!!.long("amount_minor") }
        val year = ctx.db.run { it.one("SELECT amount_minor FROM orders WHERE status='paid' ORDER BY created_at DESC LIMIT 1")!!.long("amount_minor") }
        val c = services.control

        val all = c.payments(c.filter(null, null, null, null))
        assertEquals(3, all.payments.size)
        // Newest first by payment time (an unpaid order by its own time, which the database sets).
        assertEquals(listOf("pending", "paid", "paid"), all.payments.map { it.status })
        assertEquals(MONTH, all.payments.last().product)
        assertEquals("web", all.payments.last().channel)
        assertEquals("test", all.payments.last().provider)
        assertEquals(2, all.totals.paidCount)
        assertEquals(1, all.totals.pendingCount)
        assertEquals(1, all.totals.newPayers)
        assertEquals(listOf(month + year), all.totals.revenue.map { it.minor })
        // Today = the second purchase only; 7 and 30 days = both.
        assertEquals(listOf(year), all.totals.revenueToday.map { it.minor })
        assertEquals(listOf(month + year), all.totals.revenueWeek.map { it.minor })
        assertEquals(listOf(month + year), all.totals.revenueMonth.map { it.minor })

        // Only the last day: a's second purchase is not a new payer.
        val today = c.payments(c.filter("2026-10-17", "2026-10-17", null, null))
        assertEquals(1, today.payments.size)
        assertEquals(0, today.totals.newPayers)
        assertEquals(1, c.payments(c.filter(null, null, "pending", null)).payments.size)
        assertEquals(2, c.payments(c.filter(null, null, "paid", "a@exa")).payments.size)
        assertFailsWithApp { c.filter("31.10.2026", null, null, null) }
        assertFailsWithApp { c.filter(null, null, "refunded", null) }

        // CSV through the API: a header and one line per order.
        val res = http(HttpMethod.Get, "/v1/admin/payments.csv?status=paid", ownerToken)
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.headers[HttpHeaders.ContentDisposition]!!.contains("payments.csv"))
        val lines = res.bodyAsText().removePrefix("﻿").trim().lines()
        assertEquals("id,created_at,paid_at,user_id,email,product,amount,currency,channel,provider,status,external_id", lines[0])
        assertEquals(3, lines.size)
        assertTrue(lines.all { it == lines[0] || ",paid," in it })
        assertEquals("%d.%02d".format(year / 100, year % 100), lines[1].split(',')[6])
        // Spreadsheet formulas are defused, quotes doubled.
        assertEquals("'=HYPERLINK(1)", Control.csv("=HYPERLINK(1)"))
        assertEquals("\"a,\"\"b\"\"\"", Control.csv("a,\"b\""))
        assertEquals("-5", Control.csv("-5"))
    }

    @Test fun `reissuing a VLESS key takes the old UUID off the node at once`() = harness {
        val (ownerToken, admin) = owner()
        val (_, token) = vlessNode(ownerToken)
        val u = signIn("v@example.com")
        val uid = u.session.user.id
        services.vlessKeys.ensureFor(uid)
        val before = ctx.db.run { it.one("SELECT uuid, link_token, device_id FROM vless_keys WHERE user_id=?::uuid", uid)!! }
        val oldUuid = before.str("uuid")
        assertTrue(oldUuid in services.nodes.peers(token, null).peers.map { it.publicKey })

        val res = services.control.reissue(admin, uid, before.str("device_id"))
        assertEquals("vless", res.mode)
        val newUuid = assertNotNull(res.uuid)
        assertNotEquals(oldUuid, newUuid)
        val peers = services.nodes.peers(token, null).peers.map { it.publicKey }
        assertFalse(oldUuid in peers, "the old UUID is still on the node")
        assertTrue(newUuid in peers)
        // The old link is gone too; the new one opens the same key.
        assertNull(services.vlessKeys.userByToken(before.str("link_token")))
        val now = ctx.db.run { it.one("SELECT link_token FROM vless_keys WHERE user_id=?::uuid", uid)!!.str("link_token") }
        assertEquals(uid, services.vlessKeys.userByToken(now))
        val log = services.control.auditLog(uid).entries.single()
        assertEquals("reissue", log.action)
        assertEquals("owner@test.dev", log.adminEmail)
        assertFalse(newUuid in log.details.toString())
    }

    @Test fun `reissuing a key for other apps removes the old peer and link`() = harness {
        val (_, admin) = owner()
        val u = signIn("k@example.com")
        val uid = u.session.user.id
        buy(u.api, MONTH)
        val key = u.api.keys().keys.single()
        val oldToken = key.pageUrl.substringAfter("/k/")
        val oldPeer = ctx.db.run { it.one("SELECT peer_key FROM tunnels WHERE device_id=?::uuid", key.id)!!.str("peer_key") }
        val awg = ctx.drivers[Protocols.AMNEZIAWG] as SimulatedDriver
        assertTrue(awg.peers.containsKey(oldPeer))

        assertEquals("key", services.control.reissue(admin, uid, key.id).mode)
        val newPeer = ctx.db.run { it.one("SELECT peer_key FROM tunnels WHERE device_id=?::uuid", key.id)!!.str("peer_key") }
        assertNotEquals(oldPeer, newPeer)
        assertFalse(awg.peers.containsKey(oldPeer), "the old key still works on the node")
        assertTrue(awg.peers.containsKey(newPeer))
        assertNull(services.keys.byToken(oldToken))
        val again = u.api.keys().keys.single()
        assertEquals(key.id, again.id)
        assertNotNull(services.keys.byToken(again.pageUrl.substringAfter("/k/")))
        // A Boop app makes its own keys: it is asked for a new pair instead.
        u.api.provisionTunnel(TunnelProvisionReq(Protocols.AMNEZIAWG, clientParams = Protocols.encode(WireGuard.ClientParams(key(3)))))
        assertEquals("app", services.control.reissue(admin, uid, u.session.deviceId).mode)
        assertTrue(u.api.currentTunnel().rekey)
    }

    @Test fun `blocking takes every peer off the nodes, unblocking brings them back`() = harness {
        val (ownerToken, admin) = owner()
        val (_, vToken) = vlessNode(ownerToken)
        val u = signIn("b@example.com")
        val uid = u.session.user.id
        buy(u.api, MONTH)
        services.vlessKeys.ensureFor(uid)
        val awg = ctx.drivers[Protocols.AMNEZIAWG] as SimulatedDriver
        val keyPeer = ctx.db.run { it.one("SELECT t.peer_key FROM tunnels t JOIN access_keys k ON k.device_id=t.device_id WHERE k.user_id=?::uuid", uid)!!.str("peer_key") }
        val uuid = ctx.db.run { it.one("SELECT uuid FROM vless_keys WHERE user_id=?::uuid", uid)!!.str("uuid") }
        assertTrue(awg.peers.containsKey(keyPeer))
        assertTrue(uuid in services.nodes.peers(vToken, null).peers.map { it.publicKey })

        val card = services.control.block(admin, uid, "Sharing the key")
        assertTrue(card.user.blocked)
        assertEquals("Sharing the key", card.blockedReason)
        assertFalse(awg.peers.containsKey(keyPeer))
        assertFalse(uuid in services.nodes.peers(vToken, null).peers.map { it.publicKey })
        assertEquals("FORBIDDEN", code { u.api.me() })
        assertFailsWithApp { services.tunnels.provisionFor(uid, u.session.deviceId, TunnelProvisionReq(Protocols.AMNEZIAWG, clientParams = Protocols.encode(WireGuard.ClientParams(key(4))))) }
        // The periodic sync keeps it off.
        services.vlessKeys.sync()
        assertFalse(uuid in services.nodes.peers(vToken, null).peers.map { it.publicKey })
        assertFailsWithApp { services.control.block(admin, uid, null) }

        services.control.unblock(admin, uid)
        assertTrue(awg.peers.containsKey(keyPeer))
        assertTrue(uuid in services.nodes.peers(vToken, null).peers.map { it.publicKey }, "same UUID after unblocking")
        assertEquals("b@example.com", u.api.me().user.email)
        assertEquals(listOf("unblock", "block"), services.control.auditLog(uid).entries.map { it.action })
        // The owner can't be blocked.
        assertFailsWithApp { services.control.block(admin, admin.id, null) }
    }

    private suspend fun assertFailsWithApp(block: suspend () -> Unit) {
        val e = runCatching { block() }.exceptionOrNull()
        assertTrue(e is AppError, "expected an AppError, got $e")
    }

    @Test fun `extend, grant, change plan, note and delete device are logged`() = harness {
        val (_, admin) = owner()
        val u = signIn("p@example.com")
        val uid = u.session.user.id
        val c = services.control
        // Nothing to change yet.
        assertFailsWithApp { c.changePlan(admin, uid, tariff(YEAR)) }
        // A free week on the year plan's terms (devices), as a gift: not a sale.
        var card = c.grantFree(admin, uid, 7, tariff(YEAR))
        assertEquals(clock.plus(Duration.ofDays(7)).toString(), card.user.accessUntil)
        assertNull(card.user.paidUntil)
        assertEquals("active", card.user.state)
        assertEquals(YEAR, card.user.tariffName)
        assertEquals(0, services.finance.get(dev.jaganet.api.ReferralPeriod.D30).newSubscriptions)
        card = c.extend(admin, uid, 3)
        assertEquals(clock.plus(Duration.ofDays(10)).toString(), card.user.accessUntil)
        assertEquals(YEAR, card.subscriptions.first().tariffName)
        card = c.changePlan(admin, uid, tariff(MONTH))
        assertEquals(MONTH, card.user.tariffName)
        assertTrue(card.subscriptions.all { it.tariffName == MONTH })
        card = c.note(admin, uid, "VIP, asked for help on 31.10")
        assertEquals("VIP, asked for help on 31.10", card.user.note)
        assertFailsWithApp { c.extend(admin, uid, 0) }
        assertFailsWithApp { c.extend(admin, uid, 5000) }

        u.api.provisionTunnel(TunnelProvisionReq(Protocols.AMNEZIAWG, clientParams = Protocols.encode(WireGuard.ClientParams(key(8)))))
        card = c.deleteDevice(admin, uid, u.session.deviceId)
        assertTrue(card.devices.none { it.id == u.session.deviceId })
        assertEquals("UNAUTHORIZED", code { u.api.me() })

        val log = c.auditLog(uid).entries
        assertEquals(listOf("delete_device", "note", "plan", "extend", "grant"), log.map { it.action })
        assertTrue(log.all { it.adminEmail == "owner@test.dev" && it.userId == uid })
        assertEquals(YEAR, log.single { it.action == "plan" }.details["from"]!!.jsonPrimitive.content)
        assertEquals(7, log.single { it.action == "grant" }.details["days"]!!.jsonPrimitive.int)
    }

    @Test fun `client addresses from agents, search by every handle, sharing and the dashboard`() = harness {
        val (ownerToken, admin) = owner()
        val (vId, vToken) = vlessNode(ownerToken)
        val owner = client(ownerToken)
        val awgId = owner.createNode(NodeReq("Austria", "Vienna", "AT")).node.id
        val awgToken = owner.newNodeToken(awgId).command.substringAfter("NODE_TOKEN=").substringBefore(' ')
        services.nodes.register(awgToken, NodeRegisterReq("203.0.113.7", 40000, "amneziawg"))

        val u = signIn("share@example.com")
        val uid = u.session.user.id
        ctx.db.run { it.exec("UPDATE users SET telegram_id=777000111, tg_username='share_tg' WHERE id=?::uuid", uid) }
        u.api.provisionTunnel(TunnelProvisionReq(Protocols.AMNEZIAWG, awgId, Protocols.encode(WireGuard.ClientParams(key(11)))))
        services.vlessKeys.ensureFor(uid)
        val uuid = ctx.db.run { it.one("SELECT uuid FROM vless_keys WHERE user_id=?::uuid", uid)!!.str("uuid") }

        // A new agent reports the endpoint and the online IPs; an old one neither (still accepted).
        services.nodes.report(awgToken, NodeReportReq("amneziawg", listOf(NodePeerCounter(key(11), rx = 10, tx = 20, lastHandshake = clock.epochSecond, endpoint = "198.51.100.4:51820"))))
        services.nodes.report(vToken, NodeReportReq("vless", listOf(NodePeerCounter(uuid, rx = 5, tx = 6, lastHandshake = clock.epochSecond,
            ips = mapOf("203.0.113.50" to clock.epochSecond, "2001:db8::7" to clock.epochSecond - 30, "junk" to clock.epochSecond)))))
        val old = http(HttpMethod.Post, "/v1/node/report", awgToken, """{"protocol":"amneziawg","peers":[{"publicKey":"${key(11)}","rx":11,"tx":21,"lastHandshake":${clock.epochSecond}}],"system":{"cpu":0.1}}""")
        assertEquals(HttpStatusCode.OK, old.status)
        services.nodes.report(awgToken, NodeReportReq("amneziawg", listOf(NodePeerCounter(key(11), rx = 12, tx = 22, lastHandshake = clock.epochSecond, endpoint = "198.51.100.4:51820")), NodeSystem(agent = "2")))
        services.traffic.collect()

        val card = services.control.card(uid)
        assertEquals(setOf("198.51.100.4", "203.0.113.50", "2001:db8::7"), card.ips24h.map { it.ip }.toSet())
        assertEquals(3, card.user.ips24h)
        val vless = card.devices.single { it.kind == "vless" }
        assertEquals(uuid, vless.uuid)
        assertEquals("203.0.113.50", vless.lastIp)
        assertEquals(vId, vless.serverId)
        val dev = card.devices.single { it.kind == "device" }
        assertEquals("198.51.100.4", dev.lastIp)
        assertEquals(34, dev.trafficTotal)
        assertTrue(dev.online)
        assertFalse(card.user.sharing) // 3 addresses, threshold 5

        // Search: e-mail, Telegram id, UUID, key link (whole or token), IP, user id.
        val token = ctx.db.run { it.one("SELECT link_token FROM vless_keys WHERE user_id=?::uuid", uid)!!.str("link_token") }
        for ((q, kind) in listOf("share@" to "email", "777000111" to "telegram", uuid.uppercase() to "uuid", token to "token",
            "https://boop.example/k/$token/vless" to "token", "198.51.100.4" to "ip", "[2001:db8::7]:443" to "ip", uid to "id")) {
            val found = services.control.search(q).users
            assertEquals(listOf(uid), found.map { it.id }, "search $q")
            assertEquals(kind, found.single().matched, "search $q")
        }
        assertTrue(services.control.search("nobody-like-this").users.isEmpty())
        // With #38's users.tg_username, @username search is on.
        assertTrue(services.control.search(null).telegramUsernames)
        assertEquals(listOf(uid), services.control.search("@share_tg").users.map { it.id }, "search @username")

        // A lower threshold flags the subscription as shared.
        services.control.saveSettings(admin, CtlSettings(2))
        assertTrue(services.control.card(uid).user.sharing)
        assertFailsWithApp { services.control.saveSettings(admin, CtlSettings(0)) }
        val dash = services.control.dashboard()
        assertEquals(listOf(uid), dash.sharing.map { it.id })
        assertEquals(2, dash.sharingThreshold)
        assertEquals(2, dash.onlineDevices)
        assertEquals("2", dash.servers.single { it.id == awgId }.agentVersion)
        assertNull(dash.servers.single { it.id == vId }.agentVersion)
        assertEquals("online", dash.servers.single { it.id == awgId }.state)
        assertTrue(dash.usersTotal >= 2)

        // A week later the addresses are cleaned up.
        clock = clock.plus(Duration.ofDays(8))
        ctx.db.run { ClientIps.prune(it, clock) }
        assertEquals(0, services.control.card(uid).ips24h.size)
    }

    @Test fun `the dashboard counts active, ending soon and blocked users`() = harness {
        val (ownerToken, admin) = owner()
        val a = signIn("a@example.com")
        buy(a.api, MONTH)
        val b = signIn("b@example.com")
        services.control.grantFree(admin, b.session.user.id, 2, null)
        val c = signIn("c@example.com")
        services.control.block(admin, c.session.user.id, null)
        val d = services.control.dashboard()
        assertEquals(4, d.usersTotal)
        assertEquals(2, d.usersActive)
        assertEquals(1, d.usersExpiring3d)
        assertEquals(listOf(b.session.user.id), d.expiring.map { it.id })
        assertEquals(1, d.usersBlocked)
        assertTrue(d.revenueToday.isNotEmpty())
        assertEquals(1, d.paidOrdersMonth)
        assertEquals(1, d.newPayersMonth)
        // Through the API too.
        val json = Json.parseToJsonElement(http(HttpMethod.Get, "/v1/admin/control/dashboard", ownerToken).bodyAsText()).jsonObject
        assertEquals(4, json["usersTotal"]!!.jsonPrimitive.int)
        assertTrue(json["servers"]!!.jsonArray.isNotEmpty())
    }
}
