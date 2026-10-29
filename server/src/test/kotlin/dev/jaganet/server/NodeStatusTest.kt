package dev.jaganet.server

import dev.jaganet.api.NodeStatus
import dev.jaganet.api.NodeStatusReq
import dev.jaganet.api.NoticeSettings
import dev.jaganet.api.Protocols
import dev.jaganet.api.ServerLocation
import dev.jaganet.api.ServersRes
import dev.jaganet.api.TunnelProvisionReq
import dev.jaganet.api.WireGuard
import dev.jaganet.server.db.Jsonb
import dev.jaganet.server.services.Settings
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #10: the owner marks a node (ok, degraded, blocked, maintenance) and leaves notes the apps show. */
class NodeStatusTest {
    private fun wg(n: Int, server: String? = null) = TunnelProvisionReq(Protocols.WIREGUARD, server, Protocols.encode(WireGuard.ClientParams(key(n))))

    /** ServerLocation and ServersRes exactly as the apps released before this change know them. */
    @Serializable
    private data class OldServerLocation(val id: String, val name: String, val city: String, val countryCode: String, val load: Double, val protocols: List<String>, val host: String? = null)

    @Serializable
    private data class OldServersRes(val servers: List<OldServerLocation>)

    /** The apps' decoder (Protocols.json) ignores unknown keys; so does any lenient client. */
    private val oldJson = Json { ignoreUnknownKeys = true }

    private suspend fun Harness.addNode(id: String) = ctx.db.run {
        it.exec(
            "INSERT INTO servers (id, name, city, country_code, subnet, protocols, max_peers) VALUES (?,?,'Elsewhere','NL','10.9.0.0/29',?,5)",
            id, "Node $id", Jsonb("""{"wireguard":{"endpoint":"$id:51820","publicKey":"${key(97)}"}}"""),
        )
    }

    @Test fun `older apps still read the server list and the tunnel`() = harness {
        val a = signIn("old@example.com")
        val owner = signIn("owner@test.dev").api
        owner.setNodeStatus("n1", NodeStatusReq(NodeStatus.MAINTENANCE, "Planned work"))
        owner.saveNoticeSettings(NoticeSettings("Hello everyone"))

        val raw = b.createClient {}.get("/v1/servers") { header("Authorization", "Bearer ${a.session.token}") }.bodyAsText()
        val old = oldJson.decodeFromString(OldServersRes.serializer(), raw)
        assertEquals(listOf("n1"), old.servers.map { it.id })
        assertEquals("DE", old.servers.single().countryCode)
        // The new fields are additions only.
        val node = Json.parseToJsonElement(raw).jsonObject["servers"]!!.jsonArray.single().jsonObject
        assertEquals("\"maintenance\"", node["status"].toString())
        assertEquals("\"Planned work\"", node["notice"].toString())

        // A node without a note leaves "notice" out; the status is always there.
        owner.setNodeStatus("n1", NodeStatusReq(NodeStatus.OK, "  "))
        val plain = b.createClient {}.get("/v1/servers") { header("Authorization", "Bearer ${a.session.token}") }.bodyAsText()
        val plainNode = Json.parseToJsonElement(plain).jsonObject["servers"]!!.jsonArray.single().jsonObject
        assertFalse("notice" in plainNode)
        assertEquals("\"ok\"", plainNode["status"].toString())

        // New apps against an older panel: missing fields mean "ok" and no note.
        val fromOldPanel = Protocols.json.decodeFromString(ServersRes.serializer(), """{"servers":[{"id":"x","name":"X","city":"C","countryCode":"DE","load":0.1,"protocols":["amneziawg"]}]}""")
        assertEquals(ServerLocation("x", "X", "C", "DE", 0.1, listOf("amneziawg")), fromOldPanel.servers.single())
        assertEquals(NodeStatus.OK, fromOldPanel.servers.single().status)
        assertNull(fromOldPanel.notice)

        // The tunnel answers keep their shape for the shared client (the mobile apps).
        val cfg = a.api.provisionTunnel(wg(1))
        assertNull(cfg.notice)
        assertEquals("n1", a.api.currentTunnel().config!!.serverId)
    }

    @Test fun `the owner sets a status and a note, the apps get them`() = harness {
        val a = signIn("a@example.com")
        val owner = signIn("owner@test.dev").api
        a.api.provisionTunnel(wg(2))
        val before = a.api.currentTunnel()
        assertNull(before.notice)

        val n = owner.setNodeStatus("n1", NodeStatusReq("degraded", "  Slow   in the evening\n "))
        assertEquals(NodeStatus.DEGRADED, n.status)
        assertEquals("Slow in the evening", n.notice) // one line, trimmed
        assertEquals(NodeStatus.DEGRADED, owner.adminNodes().nodes.single { it.id == "n1" }.status)

        val s = a.api.servers().servers.single()
        assertEquals(NodeStatus.DEGRADED, s.status)
        assertEquals("Slow in the evening", s.notice)

        // GET /tunnel: the selected node's note, and the version stays (nothing to apply).
        val now = a.api.currentTunnel()
        assertEquals("Slow in the evening", now.notice)
        assertEquals("Slow in the evening", now.config!!.notice)
        assertEquals(before.version, now.version)
        // POST /tunnel: the note comes with the new config.
        assertEquals("Slow in the evening", a.api.provisionTunnel(wg(3)).notice)

        // Clearing the note.
        owner.setNodeStatus("n1", NodeStatusReq(NodeStatus.OK, null))
        assertNull(a.api.servers().servers.single().notice)
        assertNull(a.api.currentTunnel().notice)

        // Bad values are refused.
        assertEquals("BAD_REQUEST", code { owner.setNodeStatus("n1", NodeStatusReq("broken")) })
        assertEquals("BAD_REQUEST", code { owner.setNodeStatus("n1", NodeStatusReq(NodeStatus.OK, "x".repeat(201))) })
        assertEquals("OK", code { owner.setNodeStatus("n1", NodeStatusReq(NodeStatus.OK, "x".repeat(200))) })
        assertEquals("NOT_FOUND", code { owner.setNodeStatus("nope", NodeStatusReq(NodeStatus.BLOCKED)) })
    }

    @Test fun `only the owner changes a status or the note for everyone`() = harness {
        val a = signIn("user@example.com")
        assertEquals("FORBIDDEN", code { a.api.setNodeStatus("n1", NodeStatusReq(NodeStatus.BLOCKED, "x")) })
        assertEquals("FORBIDDEN", code { a.api.saveNoticeSettings(NoticeSettings("x")) })
        assertEquals("UNAUTHORIZED", code { client().setNodeStatus("n1", NodeStatusReq(NodeStatus.BLOCKED)) })
        assertEquals("UNAUTHORIZED", code { client().saveNoticeSettings(NoticeSettings("x")) })
        // Nothing changed.
        val s = a.api.servers()
        assertEquals(NodeStatus.OK, s.servers.single().status)
        assertNull(s.servers.single().notice)
        assertNull(s.notice)
    }

    @Test fun `the note for everyone comes with the server list and survives a restart`() = harness {
        val a = signIn("a@example.com")
        val owner = signIn("owner@test.dev").api
        assertNull(a.api.servers().notice)
        assertEquals("Tonight 01 to 02 maintenance", owner.saveNoticeSettings(NoticeSettings(" Tonight 01 to 02 maintenance ")).notice.text)
        assertEquals("Tonight 01 to 02 maintenance", a.api.servers().notice)

        ctx.live.notice = null
        Settings(ctx).load() // as at start
        assertEquals("Tonight 01 to 02 maintenance", a.api.servers().notice)

        assertEquals("BAD_REQUEST", code { owner.saveNoticeSettings(NoticeSettings("y".repeat(201))) })
        owner.saveNoticeSettings(NoticeSettings(""))
        assertNull(a.api.servers().notice)
        assertNull(owner.adminSettings().notice.text)
    }

    @Test fun `the server's own pick avoids blocked and maintenance nodes`() = harness {
        addNode("n2")
        val owner = signIn("owner@test.dev").api
        owner.setNodeStatus("n1", NodeStatusReq(NodeStatus.MAINTENANCE))
        val a = signIn("a@example.com")
        assertEquals("n2", a.api.provisionTunnel(wg(4)).serverId)

        owner.setNodeStatus("n2", NodeStatusReq(NodeStatus.BLOCKED))
        owner.setNodeStatus("n1", NodeStatusReq(NodeStatus.DEGRADED))
        val b2 = signIn("b@example.com")
        assertEquals("n1", b2.api.provisionTunnel(wg(5)).serverId)

        // Asked for by id, a node under maintenance still works: older apps keep connecting.
        owner.setNodeStatus("n1", NodeStatusReq(NodeStatus.MAINTENANCE))
        assertEquals("n1", b2.api.provisionTunnel(wg(6, "n1")).serverId)
        // Nothing else left: the least bad one rather than no VPN at all.
        val c = signIn("c@example.com")
        assertTrue(c.api.provisionTunnel(wg(7)).serverId in setOf("n1", "n2"))
    }
}
