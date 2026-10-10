package dev.jaganet.server

import dev.jaganet.api.AmneziaWG
import dev.jaganet.api.ErrorCode
import dev.jaganet.api.NodeReq
import dev.jaganet.api.NodeState
import dev.jaganet.api.Protocols
import dev.jaganet.api.TunnelProvisionReq
import dev.jaganet.api.WireGuard
import dev.jaganet.server.services.NodePeerCounter
import dev.jaganet.server.services.NodeRegisterReq
import dev.jaganet.server.services.NodeReportReq
import dev.jaganet.server.services.NodeSystem
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class NodeTest {
    private fun awg(n: Int, server: String? = null) =
        TunnelProvisionReq(Protocols.AMNEZIAWG, server, Protocols.encode(WireGuard.ClientParams(key(n))))

    private fun tokenOf(command: String) = command.substringAfter("NODE_TOKEN=").substringBefore(' ')

    @Test fun `a new node registers, gets peers, reports traffic and can move to another machine`() = harness {
        val owner = signIn("owner@test.dev").api
        assertEquals("FORBIDDEN", code { signIn("a@example.com").api.adminNodes() })

        val created = owner.createNode(NodeReq("Germany 1", "Nuremberg", "de"))
        val id = created.node.id
        assertEquals(NodeState.WAITING, created.node.state)
        assertEquals("DE", created.node.countryCode)
        assertTrue(created.command.startsWith("curl -fsSL ${cfg.publicUrl}/node/install.sh | JAGANET_URL="), created.command)
        val token = tokenOf(created.command)
        // Not offered to devices before its agent registers.
        assertTrue(owner.servers().servers.none { it.id == id })

        // The install script registers over HTTP; a wrong token is refused.
        val raw = b.createClient {}
        suspend fun register(t: String, ip: String) = raw.post("/v1/node/register") {
            header("Authorization", "Bearer $t"); contentType(ContentType.Application.Json)
            setBody("""{"publicIp":"$ip","port":51821,"protocol":"amneziawg"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, register("x".repeat(43), "203.0.113.5").status)
        val reg = register(token, "203.0.113.5")
        assertEquals(HttpStatusCode.OK, reg.status)
        val body = reg.bodyAsText()
        assertTrue("\"address\":\"10.8.0.1/24\"" in body && "\"privateKey\":" in body && "\"S1\":" in body, body)

        val node = owner.adminNodes().nodes.single { it.id == id }
        assertEquals(NodeState.ONLINE, node.state)
        assertEquals("203.0.113.5:51821", node.endpoint)
        assertTrue(node.remote)

        // A device lands on the node; the agent sees it in its peer list.
        val a = signIn("a@example.com")
        val cfg1 = a.api.provisionTunnel(awg(7, id))
        assertEquals(id, cfg1.serverId)
        val params = Protocols.decode<AmneziaWG.ServerParams>(cfg1.params)
        assertEquals("203.0.113.5:51821", params.endpoint)
        val peers = services.nodes.peers(token, null)
        assertEquals(listOf(key(7)), peers.peers.map { it.publicKey })
        // Nothing changed: the long poll waits, then answers with the same version.
        assertEquals(peers.version, services.nodes.peers(token, peers.version, 50.milliseconds).version)

        // The agent reports counters; traffic is counted for the device.
        services.nodes.report(token, NodeReportReq("amneziawg", listOf(NodePeerCounter(key(7), rx = 1_000_000, tx = 9_000_000, lastHandshake = clock.epochSecond)), NodeSystem(cpu = 0.2)))
        services.traffic.collect()
        assertEquals(10_000_000, a.api.me().usage.bytesUsed)
        assertEquals(1, owner.adminNodes().nodes.single { it.id == id }.online)

        // Silent for 5 minutes: offline, no new devices go there.
        clock = clock.plus(Duration.ofMinutes(5))
        assertEquals(NodeState.OFFLINE, owner.adminNodes().nodes.single { it.id == id }.state)
        assertTrue(owner.servers().servers.none { it.id == id })

        // The machine died: a new command on another machine brings the node back with the
        // same identity (key, obfuscation) and the same devices. With a host name the key
        // files don't change at all.
        owner.updateNode(id, NodeReq("Germany 1", "Nuremberg", "DE", hostname = "de1.vpn.example.com"))
        val moved = owner.newNodeToken(id)
        assertEquals(HttpStatusCode.Unauthorized, register(token, "198.51.100.9").status) // the old token is gone
        assertEquals(HttpStatusCode.OK, register(tokenOf(moved.command), "198.51.100.9").status)
        val after = owner.adminNodes().nodes.single { it.id == id }
        assertEquals(NodeState.ONLINE, after.state)
        assertEquals("de1.vpn.example.com:51821", after.endpoint)
        assertEquals("198.51.100.9", after.publicIp)
        assertEquals(listOf(key(7)), services.nodes.peers(tokenOf(moved.command), null).peers.map { it.publicKey })
        val again = Protocols.decode<AmneziaWG.ServerParams>(a.api.provisionTunnel(awg(7, id)).params)
        assertEquals(params.serverPublicKey, again.serverPublicKey)
        assertEquals(params.obfuscation, again.obfuscation)
        assertEquals("de1.vpn.example.com:51821", again.endpoint)
    }

    @Test fun `node settings are checked`() = harness {
        val owner = signIn("owner@test.dev").api
        assertEquals("BAD_REQUEST", code { owner.createNode(NodeReq("X", "Y", "Germany")) })
        assertEquals("BAD_REQUEST", code { owner.createNode(NodeReq("X", "Y", "DE", hostname = "not a host")) })
        val n = owner.createNode(NodeReq("X", "Y", "DE")).node
        assertEquals("BAD_REQUEST", code { owner.updateNode(n.id, NodeReq("X", "Y", "DE", maxPeers = 1000)) })
        assertNotNull(owner.adminNodes().nodes.firstOrNull { !it.remote }) // the main server's own VPN is listed too
        val t = tokenOf(owner.newNodeToken(n.id).command)
        assertEquals(ErrorCode.BAD_REQUEST, assertFailsWith<AppError> { services.nodes.register(t, NodeRegisterReq("1.2.3", 51821, "amneziawg")) }.code)
    }
}
