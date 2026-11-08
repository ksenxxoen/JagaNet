package dev.jaganet.server

import dev.jaganet.api.CheckLevel
import dev.jaganet.server.monitor.AlertSink
import dev.jaganet.server.monitor.IfaceCounters
import dev.jaganet.server.monitor.Monitor
import dev.jaganet.server.monitor.PingResult
import dev.jaganet.server.monitor.SystemProbe
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeProbe : SystemProbe {
    var cpu = 0.2
    var rxBytes = 0L
    var txBytes = 0L
    var errors = 0L
    var loss = 0.0
    var httpOk = true
    override fun cpu() = cpu
    override fun memory() = 1_000_000_000L to 4_000_000_000L
    override fun disk() = 30_000_000_000L to 40_000_000_000L
    override fun wanInterface() = "eth0"
    override fun counters(iface: String) = IfaceCounters(rxBytes, txBytes, errors, 0)
    override fun linkSpeedMbps(iface: String): Long? = null
    override suspend fun ping(host: String) = PingResult(host, if (loss >= 100) null else 12.0, loss)
    override suspend fun dns(name: String): Long? = 15
    override suspend fun httpOk(url: String) = httpOk
    override suspend fun certExpiry(host: String): Instant? = null
}

class MonitorTest {
    private class Sent(val subject: String, val text: String)

    private fun Harness.monitor(probe: FakeProbe, sent: MutableList<Sent>) = Monitor(
        Ctx(
            cfg.copy(monitor = MonitorConfig(enabled = true, channelMbps = 100, monthlyTrafficLimitBytes = 8_000_000_000, pingTargets = listOf("1.1.1.1"))),
            ctx.db, ctx.drivers, ctx.mailer, { clock },
        ),
        probe, sinks = { listOf(AlertSink { s, t -> sent += Sent(s, t) }) },
    )

    /** One minute passes: [mbps] of traffic each way, then a check. */
    private suspend fun Harness.minute(m: Monitor, p: FakeProbe, mbps: Long = 10) {
        clock = clock.plus(Duration.ofMinutes(1))
        p.rxBytes += mbps * 1_000_000 / 8 * 60
        p.txBytes += mbps * 1_000_000 / 8 * 60
        m.tick()
    }

    @Test fun `measures the channel and stores every minute`() = harness {
        val p = FakeProbe(); val sent = mutableListOf<Sent>()
        val m = monitor(p, sent)
        repeat(3) { minute(m, p, mbps = 40) }
        val ch = m.latest.single { it.key == "channel" }
        assertEquals(CheckLevel.OK, ch.level)
        assertEquals("40", ch.args["p"])
        val o = m.overview("1h")
        assertEquals(2, o.series.size, "the first minute after a start is not stored")
        assertEquals(40_000_000L, o.series.last().rxBps)
        assertEquals(0.4, o.series.last().utilization!!, 0.001)
        assertEquals("config", o.network.capacitySource)
        // The first minute has no previous reading, so two minutes of traffic each way are counted.
        assertEquals(2 * 40_000_000L / 8 * 60, o.network.monthRxBytes)
        assertTrue(sent.isEmpty())
        assertEquals(CheckLevel.OK, o.overall)
    }

    @Test fun `a lasting problem opens one alert and its recovery closes it`() = harness {
        val p = FakeProbe(); val sent = mutableListOf<Sent>()
        val m = monitor(p, sent)
        minute(m, p)
        p.loss = 30.0
        repeat(2) { minute(m, p) }
        assertTrue(sent.isEmpty(), "two bad minutes are not an alert yet")
        minute(m, p)
        assertEquals(1, sent.size)
        assertTrue(sent[0].subject.startsWith("[Boop VPN] Проблема"), sent[0].subject)
        assertTrue("1.1.1.1" in sent[0].text && "30" in sent[0].text, sent[0].text)
        repeat(5) { minute(m, p) }
        assertEquals(1, sent.size, "no repeats while it lasts")
        assertEquals(1, m.overview("1h").alerts.count { it.resolvedAt == null })

        p.loss = 0.0
        repeat(2) { minute(m, p) }
        assertEquals(2, sent.size)
        assertTrue(sent[1].subject.startsWith("[Boop VPN] Исправлено"), sent[1].subject)
        assertTrue(m.overview("1h").alerts.single().resolvedAt != null)
    }

    @Test fun `a busy channel and the hosting traffic allowance raise warnings`() = harness {
        val p = FakeProbe(); val sent = mutableListOf<Sent>()
        val m = monitor(p, sent)
        minute(m, p)
        repeat(5) { minute(m, p, mbps = 90) }
        assertEquals(CheckLevel.WARNING, m.latest.single { it.key == "channel" }.level)
        assertTrue(sent.any { "Загрузка канала" in it.subject }, sent.joinToString { it.subject })
        // 5 counted minutes at 90 Mbit/s each way is 6.75 GB of the 8 GB allowance (84%).
        assertEquals(CheckLevel.WARNING, m.latest.single { it.key == "traffic" }.level)
    }

    @Test fun `the website going down is critical`() = harness {
        val p = FakeProbe(); val sent = mutableListOf<Sent>()
        val m = monitor(p, sent)
        p.httpOk = false
        repeat(3) { minute(m, p) }
        assertEquals(CheckLevel.CRITICAL, m.overview("1h").overall)
        assertTrue(sent.single().subject.contains("HTTPS"), sent.single().subject)
    }

    @Test fun `only the owner sees monitoring`() = harness {
        assertEquals("FORBIDDEN", code { signIn("a@example.com").api.adminMonitor() })
        val o = signIn("owner@test.dev").api.adminMonitor("24h")
        assertEquals(CheckLevel.UNKNOWN, o.overall)
    }

    @Test fun `a restart does not repeat an open problem that was already announced`() = harness {
        val p = FakeProbe(); val sent = mutableListOf<Sent>()
        val m = monitor(p, sent)
        minute(m, p)
        p.httpOk = false
        repeat(3) { minute(m, p) }
        val opened = sent.count { it.subject.contains("Проблема") }
        assertEquals(1, opened)
        // Long after opening, the reminder goes out once ...
        clock = clock.plus(Duration.ofHours(30))
        minute(m, p)
        val afterRemind = sent.size
        assertEquals(opened + 1, afterRemind)
        // ... and a restarted panel (new instance, same database) stays quiet.
        val p2 = FakeProbe().also { it.httpOk = false }
        val m2 = monitor(p2, sent)
        repeat(3) { minute(m2, p2) }
        assertEquals(afterRemind, sent.size)
        // A change (recovery) is still announced.
        p2.httpOk = true
        repeat(3) { minute(m2, p2) }
        assertTrue(sent.size > afterRemind)
    }

    /** Issue #115: a node marked "maintenance" is expected to be silent; others are named as the apps name them. */
    @Test fun `a node in maintenance raises no no-contact alert and the others are named by display name`() = harness {
        val p = FakeProbe(); val sent = mutableListOf<Sent>()
        suspend fun node(name: String, display: String?): String {
            val res = services.nodes.create(dev.jaganet.api.NodeReq(name, "City", "NL", maxPeers = 100, displayName = display))
            services.nodes.register(res.command.substringAfter("NODE_TOKEN=").substringBefore(' '), dev.jaganet.server.services.NodeRegisterReq("203.0.113.7", 51821, "amneziawg"))
            return res.node.id
        }
        val loud = node("tech-name-1", "Нидерланды")
        val spare = node("tech-name-2", "Запасной")
        services.nodes.setMaintenance(spare, true)
        val m = monitor(p, sent)
        repeat(6) { minute(m, p) }
        val nodes = m.latest.single { it.key == "nodes" }
        assertEquals(CheckLevel.CRITICAL, nodes.level)
        val names = nodes.args.getValue("names")
        assertTrue("Нидерланды" in names && "tech-name-1" !in names, names)
        assertTrue("Запасной" !in names, "the maintenance node is not mentioned: $names")
        assertTrue(sent.any { "Нидерланды" in it.text }, "the Telegram text names the location")
        assertTrue(sent.none { "Запасной" in it.text })
        // The event log gets one "offline" line for the silent node and none for the one in maintenance.
        val logged = ctx.db.run { it.query("SELECT server_id, kind FROM node_events") }.map { it.str("server_id") to it.str("kind") }
        assertEquals(listOf(loud to "offline"), logged.filter { it.second == "offline" })
        // Switching maintenance on is the only way out of the alert: with it on for both, all is quiet.
        services.nodes.setMaintenance(services.nodes.list().first { !it.maintenance && it.remote }.id, true)
        minute(m, p)
        assertEquals(CheckLevel.OK, m.latest.single { it.key == "nodes" }.level)
    }
}
