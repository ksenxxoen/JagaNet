package dev.jaganet.server

import dev.jaganet.api.NodeReq
import dev.jaganet.api.Protocols
import dev.jaganet.api.TunnelProvisionReq
import dev.jaganet.api.WireGuard
import dev.jaganet.server.services.NodePeerCounter
import dev.jaganet.server.services.NodeRegisterReq
import dev.jaganet.server.services.NodeReportReq
import dev.jaganet.server.services.NodeSystem
import dev.jaganet.server.services.TrafficClasses
import java.io.File
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyticsTest {
    private fun awg(n: Int, server: String) = TunnelProvisionReq(Protocols.AMNEZIAWG, server, Protocols.encode(WireGuard.ClientParams(key(n))))

    @Test fun `traffic is split into BitTorrent and the rest per server, user and device`() = harness {
        val owner = signIn("owner@test.dev").api
        assertEquals("FORBIDDEN", code { signIn("x@example.com").api.analytics() })
        val id = owner.createNode(NodeReq("Austria", "Vienna", "AT")).node.id
        val token = owner.newNodeToken(id).command.substringAfter("NODE_TOKEN=").substringBefore(' ')
        services.nodes.register(token, NodeRegisterReq("203.0.113.7", 40000, "amneziawg"))

        val a = signIn("a@example.com")
        a.api.provisionTunnel(awg(1, id))
        val b = signIn("b@example.com")
        b.api.provisionTunnel(awg(2, id))

        // Hour 1: a downloads 8 MB of which 6 MB BitTorrent; b browses.
        fun report(vararg c: NodePeerCounter) = kotlinx.coroutines.runBlocking {
            services.nodes.report(token, NodeReportReq("amneziawg", c.toList(), NodeSystem(classes = "ok")))
        }
        report(
            NodePeerCounter(key(1), rx = 1_000_000, tx = 8_000_000, lastHandshake = clock.epochSecond, torrentRx = 500_000, torrentTx = 6_000_000),
            NodePeerCounter(key(2), rx = 200_000, tx = 3_000_000, lastHandshake = clock.epochSecond),
        )
        services.traffic.collect()
        // An hour later the node restarted: counters start again from zero.
        clock = clock.plus(Duration.ofHours(1))
        report(NodePeerCounter(key(1), rx = 100_000, tx = 2_000_000, lastHandshake = clock.epochSecond, torrentRx = 0, torrentTx = 1_000_000))
        services.traffic.collect()

        val all = owner.analytics("24h")
        assertEquals("hour", all.bucket)
        assertEquals(24, all.points.size)
        assertEquals(13_000_000, all.totals.down)
        assertEquals(7_000_000, all.totals.torrentDown)
        assertEquals(500_000, all.totals.torrentUp)
        assertEquals(2, all.users)
        val at = all.servers.single { it.id == id }
        assertEquals("ok", at.classes)
        assertEquals(2, at.devices)
        assertEquals(2, at.activeDevices)
        assertEquals(13_000_000, at.traffic.down)
        assertEquals(all.totals.down + all.totals.up, all.points.sumOf { it.down + it.up })

        val server = owner.analyticsServer(id, "24h")
        assertEquals(listOf("a@example.com", "b@example.com"), server.users.map { it.email }) // most traffic first
        assertEquals(7_000_000, server.users.first().traffic.torrentDown)
        assertEquals(listOf("Austria"), server.users.first().servers)

        val users = owner.analyticsUsers("7d", q = "B@EX")
        assertEquals(listOf("b@example.com"), users.users.map { it.email })
        assertEquals(0, users.users.single().traffic.torrentDown)

        val ua = owner.analyticsUser(server.users.first().id, "7d")
        assertEquals(7, ua.points.size)
        assertEquals(10_000_000, ua.user.traffic.down)
        assertEquals(listOf("Austria"), ua.servers.map { it.name })
        assertEquals(1, ua.devices.size)
        assertEquals("AT", ua.devices.single().countryCode)
        assertEquals("NOT_FOUND", code { owner.analyticsUser("nope") })
        assertEquals("BAD_REQUEST", code { owner.analytics("1y") })
    }

    @Test fun `the local traffic classes file is read only while fresh`() {
        val f = File.createTempFile("traffic", ".json")
        val now = java.time.Instant.ofEpochSecond(1_800_000_000)
        f.writeText("""{"ok":true,"updatedAt":${now.epochSecond - 20},"peers":{"K=":{"torrentRx":5,"torrentTx":9}}}""")
        assertEquals(TrafficClasses("ok", mapOf("K=" to (5L to 9L))), TrafficClasses.read(f.path, now))
        assertEquals("stale", TrafficClasses.read(f.path, now.plusSeconds(600)).status)
        f.writeText("""{"ok":false,"updatedAt":${now.epochSecond},"error":"iptables: no string match","peers":{}}""")
        assertEquals("iptables: no string match", TrafficClasses.read(f.path, now).status)
        f.delete()
        assertEquals("off", TrafficClasses.read(f.path, now).status)
    }
}
