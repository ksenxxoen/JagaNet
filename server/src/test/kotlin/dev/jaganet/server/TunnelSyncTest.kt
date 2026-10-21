package dev.jaganet.server

import dev.jaganet.api.AmneziaWG
import dev.jaganet.api.NodeReq
import dev.jaganet.api.Protocols
import dev.jaganet.api.TunnelProvisionReq
import dev.jaganet.api.WireGuard
import dev.jaganet.server.services.NodeRegisterReq
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the desktop app relies on: GET /tunnel shows every change the owner makes, so the app can follow it while connected. */
class TunnelSyncTest {
    private fun awg(n: Int, server: String? = null) = TunnelProvisionReq(Protocols.AMNEZIAWG, server, Protocols.encode(WireGuard.ClientParams(key(n))))

    @Test fun `a connected app sees a move to another node and a request for a new key`() = harness {
        val owner = signIn("owner@test.dev").api
        val id = owner.createNode(NodeReq("Austria", "Vienna", "AT")).node.id
        val token = owner.newNodeToken(id).command.substringAfter("NODE_TOKEN=").substringBefore(' ')
        services.nodes.register(token, NodeRegisterReq("203.0.113.7", 40000, "amneziawg"))

        val a = signIn("a@example.com", device = "Laptop")
        assertNull(a.api.currentTunnel().config) // nothing provisioned yet
        a.api.provisionTunnel(awg(1, "n1"))
        val first = a.api.currentTunnel()
        assertEquals("n1", first.config!!.serverId)
        assertEquals("n1:51821", Protocols.decode<AmneziaWG.ServerParams>(first.config!!.params).endpoint)
        assertEquals(first.version, a.api.currentTunnel().version) // stable while nothing changes
        assertFalse(first.rekey)
        // The server list says where to ping.
        assertEquals("203.0.113.7", a.api.servers().servers.single { it.id == id }.host)

        // The owner moves this one device: the next check shows the new node.
        val device = a.api.devices().devices.single { it.isCurrent }.id
        owner.adminMoveDevice(device, id)
        val moved = a.api.currentTunnel()
        assertEquals(id, moved.config!!.serverId)
        assertEquals("AT", moved.countryCode)
        assertEquals("203.0.113.7:40000", Protocols.decode<AmneziaWG.ServerParams>(moved.config!!.params).endpoint)
        assertNotEquals(first.version, moved.version)
        assertEquals("BAD_REQUEST", code { owner.adminMoveDevice(device, id) })

        // A new key: the app makes a key pair and provisions again on the same node.
        owner.adminRekeyDevice(device)
        assertTrue(a.api.currentTunnel().rekey)
        a.api.provisionTunnel(awg(2, id))
        val rekeyed = a.api.currentTunnel()
        assertFalse(rekeyed.rekey)
        assertEquals(id, rekeyed.config!!.serverId)
        assertNotEquals(moved.version, rekeyed.version)

        // Key files can't change their own key.
        val keyDevice = owner.analyticsUser(owner.me().user.id).devices
        assertTrue(keyDevice.isEmpty())
        assertEquals("NOT_FOUND", code { owner.adminRekeyDevice("00000000-0000-0000-0000-000000000000") })
    }
}
