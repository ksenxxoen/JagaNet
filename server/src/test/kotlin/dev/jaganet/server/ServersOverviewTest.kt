package dev.jaganet.server

import dev.jaganet.api.AdminNode
import dev.jaganet.api.CheckLevel
import dev.jaganet.api.HealthCheck
import dev.jaganet.api.NodeReq
import dev.jaganet.api.NodeState
import dev.jaganet.api.NodeStatus
import dev.jaganet.api.NodeStatusReq
import dev.jaganet.api.Protocols
import dev.jaganet.api.Vless
import dev.jaganet.server.services.NodeBalancerReport
import dev.jaganet.server.services.NodeRegisterReq
import dev.jaganet.server.services.NodeReportReq
import dev.jaganet.server.services.NodeSystem
import dev.jaganet.server.services.ServersOverview
import dev.jaganet.server.services.ServersOverviewRes
import dev.jaganet.server.services.XrayBalancer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The owner's status page in one request: GET /v1/admin/servers/overview. */
class ServersOverviewTest {
    private suspend fun Harness.overview(token: String, query: String = ""): ServersOverviewRes {
        val res = b.createClient {}.get("/v1/admin/servers/overview$query") { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return Protocols.json.decodeFromString(ServersOverviewRes.serializer(), res.bodyAsText())
    }

    /** An AmneziaWG node made as the owner does it, its agent registered; returns (id, token). */
    private suspend fun Harness.awgNode(name: String, maxPeers: Int = 250): Pair<String, String> {
        val res = services.nodes.create(NodeReq(name, "City", "NL", maxPeers = maxPeers))
        val token = res.command.substringAfter("NODE_TOKEN=").substringBefore(' ')
        services.nodes.register(token, NodeRegisterReq("203.0.113.${(10..250).random()}", 51821, "amneziawg"))
        return res.node.id to token
    }

    private fun sys(cpu: Double, rx: Long = 0, mem: Long = 500_000_000) = NodeSystem(cpu = cpu, memUsed = mem, memTotal = 1_000_000_000, rxBytes = rx, txBytes = rx / 2, agent = "3")

    @Test fun `only the owner sees it`() = harness {
        val user = signIn("someone@example.com")
        val res = b.createClient {}.get("/v1/admin/servers/overview") { header("Authorization", "Bearer ${user.session.token}") }
        assertEquals(HttpStatusCode.Forbidden, res.status)
        assertEquals(HttpStatusCode.Unauthorized, b.createClient {}.get("/v1/admin/servers/overview").status)
    }

    @Test fun `every server with a verdict, sparklines and the open card's history`() = harness {
        val owner = signIn("owner@test.dev")
        val (busy, busyToken) = awgNode("Busy")
        val (silent, _) = awgNode("Silent")
        val (off, _) = awgNode("Off")
        val (mnt, _) = awgNode("Maintenance")
        services.nodes.update(off, NodeReq("Off", "City", "NL", active = false))
        owner.api.setNodeStatus(mnt, NodeStatusReq(NodeStatus.MAINTENANCE))

        // Three reports a minute apart from the busy node; the silent one says nothing for 4 minutes.
        repeat(3) { i ->
            clock = clock.plus(Duration.ofMinutes(1))
            services.nodes.report(busyToken, NodeReportReq("amneziawg", emptyList(), sys(0.95, rx = 750_000_000L * (i + 1))))
        }
        clock = clock.plus(Duration.ofMinutes(1))

        val o = overview(owner.session.token, "?range=1h&open=$busy")
        assertEquals("1h", o.range)
        val by = o.servers.associateBy { it.id }
        assertEquals(setOf("n1", busy, silent, off, mnt), by.keys)

        val main = by.getValue("n1")
        assertEquals(false, main.remote)
        assertEquals("ok", main.health)

        val b = by.getValue(busy)
        assertEquals("warn", b.health)
        assertEquals(listOf("cpu"), b.issues.map { it.code })
        assertEquals("95", b.issues.single().args["p"])
        assertEquals("3", b.agentVersion)
        assertEquals(100_000_000L, b.rxBps, "750 MB a minute is 100 Mbit/s")
        assertEquals(ServersOverview.SPARK, b.spark.size)
        assertTrue(b.spark.filterNotNull().isNotEmpty(), "the reports fill the sparkline")
        val history = assertNotNull(b.history, "the open card gets its history")
        assertEquals(ServersOverview.HISTORY, history.size)
        assertEquals(0.95, history.mapNotNull { it.cpu }.last(), 0.001)
        assertEquals(0.5, history.mapNotNull { it.mem }.last(), 0.001)

        assertEquals("down", by.getValue(silent).health)
        assertEquals("silent", by.getValue(silent).issues.single().code)
        assertNull(by.getValue(silent).history, "closed cards get no history")
        assertEquals("off" to "disabled", by.getValue(off).let { it.health to it.issues.single().code })
        assertEquals("off" to "maintenance", by.getValue(mnt).let { it.health to it.issues.single().code })

        // The main server's measurements only come with its card open.
        assertTrue(o.monitor.series.isEmpty())
        assertEquals(false, o.balancer)
        assertEquals(false, o.main.mailReady)
    }

    @Test fun `the verdict rules`() {
        fun node(remote: Boolean = true, state: NodeState = NodeState.ONLINE, peers: Int = 0, status: String = NodeStatus.OK, mem: Long = 0) = AdminNode(
            id = "x", name = "X", city = "C", countryCode = "DE", remote = remote, state = state, active = true, maxPeers = 100, peers = peers, online = 0,
            createdAt = "2026-01-01T00:00:00Z", status = status, cpu = 0.1, memUsed = mem, memTotal = 1000,
        )
        assertEquals("ok", ServersOverview.classify(node(), emptyList()).first)
        assertEquals("off", ServersOverview.classify(node(state = NodeState.WAITING), emptyList()).first)
        assertEquals("warn" to "full", ServersOverview.classify(node(peers = 95), emptyList()).let { it.first to it.second.single().code })
        assertEquals("warn" to "memory", ServersOverview.classify(node(mem = 950), emptyList()).let { it.first to it.second.single().code })
        assertEquals("warn" to "blocked", ServersOverview.classify(node(status = NodeStatus.BLOCKED), emptyList()).let { it.first to it.second.single().code })
        // An offline node's old numbers don't add warnings to "not responding".
        assertEquals(listOf("silent"), ServersOverview.classify(node(state = NodeState.OFFLINE, mem = 990), emptyList()).second.map { it.code })

        // The main server's VPN follows the monitor: VPN, database or website down is red, other warnings orange,
        // checks about other machines (nodes, block lists) don't count.
        val local = node(remote = false)
        val vpnDown = HealthCheck("vpn", CheckLevel.CRITICAL, "The VPN interface doesn't answer")
        val disk = HealthCheck("disk", CheckLevel.WARNING, "{free} free of {total}", mapOf("free" to "3 GB", "total" to "40 GB"))
        val nodes = HealthCheck("nodes", CheckLevel.CRITICAL, "No contact with {names}", mapOf("names" to "Silent"))
        assertEquals("ok", ServersOverview.classify(local, listOf(nodes)).first)
        val (h, issues) = ServersOverview.classify(local, listOf(disk, vpnDown))
        assertEquals("down", h)
        assertEquals(listOf("vpn" to "down", "check" to "warn"), issues.map { it.code to it.level })
        assertEquals("disk", issues[1].args["key"])
        assertEquals("{free} free of {total}", issues[1].message)
    }

    @Test fun `a cascade entry reports the exit its balancer selects`() = harness(mapOf("VLESS_BALANCER" to "1")) {
        val owner = signIn("owner@test.dev")
        val pbk = "_No2ExH-ajHLTIY4xDz1EjOQ6izyfCBAueJM73gZbh0"
        suspend fun vless(cc: String, role: String): Pair<String, String> {
            val host = "${cc.lowercase()}.example.com"
            val res = services.nodes.create(
                NodeReq("VLESS $cc", "City", cc, maxPeers = 100, protocol = "vless", cascadeRole = role,
                    vless = Vless.NodeSettings(host = host, publicKey = pbk, shortIds = listOf("a1b2c3d4"), sni = host, transports = listOf(Vless.Transport("tcp", 443)))),
            )
            val token = res.command.substringAfter("NODE_TOKEN=").substringBefore(' ')
            services.nodes.register(token, NodeRegisterReq("203.0.113.${(10..250).random()}", protocol = "vless"))
            return res.node.id to token
        }
        val (entry, entryToken) = vless("RU", "entry")
        val (exit, exitToken) = vless("FI", "exit")
        // The panel names the balancer to the entry's agent only.
        assertEquals(XrayBalancer.BALANCER, services.nodes.peers(entryToken, null).balancer)
        assertNull(services.nodes.peers(exitToken, null).balancer)

        // An old agent: no balancer in the report, nothing breaks.
        services.nodes.report(entryToken, NodeReportReq("vless", emptyList(), sys(0.1)))
        assertNull(overview(owner.session.token).servers.single { it.id == entry }.balancer)

        services.nodes.report(entryToken, NodeReportReq("vless", emptyList(), sys(0.1), NodeBalancerReport(XrayBalancer.BALANCER, listOf(XrayBalancer.PREFIX + exit))))
        val o = overview(owner.session.token)
        assertTrue(o.balancer)
        val e = o.servers.single { it.id == entry }
        assertEquals("entry", e.cascadeRole)
        assertEquals(listOf(exit), e.balancer!!.selected)
        assertEquals("exit", o.servers.single { it.id == exit }.cascadeRole)
    }
}
