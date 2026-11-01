package dev.jaganet.server

import dev.jaganet.server.db.Jsonb
import dev.jaganet.server.db.Sql
import dev.jaganet.server.http.Services
import dev.jaganet.server.services.NodeBalancerReport
import dev.jaganet.server.services.NodePeerCounter
import dev.jaganet.server.services.NodeReportReq
import dev.jaganet.server.services.NodeSystem
import dev.jaganet.server.services.XrayBalancer
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.ZoneOffset
import kotlin.math.max
import kotlin.math.sin

/**
 * Simulation only: more nodes for the status page and stand-in agents that report like real ones.
 *
 * SIM_SCENARIO picks the picture: "ok" (default, every node works), "down" (one node stopped
 * reporting four minutes ago) or "many" (twelve servers in every state: load, maintenance, off,
 * not responding, a long name). With VLESS_BALANCER=1 a cascade is added: entry "Вход RU" balancing
 * over the two VLESS nodes as exits.
 */
object SimNodes {
    private val log = LoggerFactory.getLogger("sim")
    private val scenario get() = System.getenv("SIM_SCENARIO")?.lowercase() ?: "ok"

    /** Nodes whose agents say nothing (the "not responding" state). */
    private val silent: Set<String> get() = when (scenario) { "down" -> setOf("sim-4"); "many" -> setOf("sim-4", "sim-8"); else -> emptySet() }

    /** Typical processor load per node (the Turkish one is busy). */
    private fun baseCpu(id: String) = when (id) { "sim-6" -> 0.92; "sim-2" -> 0.22; "sim-ru" -> 0.31; else -> 0.06 + (id.hashCode() and 0xff) / 2550.0 }
    private fun scale(id: String) = when (id) { "sim-2" -> 90e6; "sim-ru" -> 160e6; "sim-6" -> 40e6; else -> 8e6 + (id.hashCode() and 0xff) * 1e5 }

    private fun awg(host: String, ip: String, port: Int) =
        """{"amneziawg":{"endpoint":"$host:$port","publicIp":"$ip","port":$port,"publicKey":"c2ltdWxhdGVkLW5vZGUtcHVibGljLWtleS0wMDAwMDE="}}"""

    private fun vless(host: String, ip: String) =
        """{"vless":{"host":"$host","publicKey":"c2ltdWxhdGVkLXJlYWxpdHktcHVibGljLWtleS0wMw0","shortIds":["c1d2e3f4"],"sni":"$host","fingerprint":"chrome",
           "transports":[{"type":"tcp","port":443,"tag":"vision","flow":"xtls-rprx-vision"}],"endpoint":"$host:443","publicIp":"$ip"}}"""

    /** Called once by Seed after the base demo data. */
    fun seed(ctx: Ctx, sql: Sql) {
        val now = ctx.now()
        fun node(id: String, name: String, city: String, cc: String, protocols: String, maxPeers: Int, active: Boolean = true, status: String = "ok", subnet: String = "10.8.0.0/24") =
            sql.exec(
                """INSERT INTO servers (id, name, city, country_code, subnet, protocols, max_peers, agent_token_hash, registered_at, last_report_at, active, status, endpoint_host)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                id, name, city, cc, subnet, Jsonb(protocols), maxPeers, "sim-agent-$id", now.minus(Duration.ofDays(9)), now.minus(Duration.ofSeconds(20)),
                active, status, if (protocols.contains("amneziawg")) protocols.substringAfter("\"endpoint\":\"").substringBefore(':') else null,
            )
        if (scenario == "many") {
            node("sim-5", "Германия", "Frankfurt", "DE", awg("de1.sim.jaganet.dev", "203.0.113.61", 51820), 250)
            node("sim-6", "Турция", "Istanbul", "TR", awg("tr1.sim.jaganet.dev", "203.0.113.62", 51820), 100)
            node("sim-7", "США", "New York", "US", awg("us1.sim.jaganet.dev", "203.0.113.63", 51820), 250, status = "maintenance")
            node("sim-8", "Польша", "Warsaw", "PL", awg("pl1.sim.jaganet.dev", "203.0.113.64", 51820), 250)
            node("sim-9", "Казахстан", "Almaty", "KZ", vless("kz1.sim.jaganet.dev", "203.0.113.65"), 500, subnet = "10.9.0.0/16")
            node("sim-10", "Швеция, резервный узел в Стокгольме", "Stockholm", "SE", awg("se-backup-stockholm-01.sim.jaganet.dev", "203.0.113.66", 51820), 250)
            node("sim-11", "Япония", "Tokyo", "JP", awg("jp1.sim.jaganet.dev", "203.0.113.67", 51820), 250, active = false)
            node("sim-12", "Сербия", "Belgrade", "RS", awg("rs1.sim.jaganet.dev", "203.0.113.68", 51820), 40)
        }
        if (ctx.cfg.vlessBalancer) {
            node("sim-ru", "Вход RU", "Moscow", "RU", vless("ru1.sim.jaganet.dev", "203.0.113.70"), 1000, subnet = "10.9.0.0/16")
            sql.exec("UPDATE servers SET cascade_role='entry', cascade_uuid=? WHERE id='sim-ru'", java.util.UUID.randomUUID().toString())
            sql.exec("UPDATE servers SET cascade_role='exit', display_name=CASE id WHEN 'sim-3' THEN 'Выход FI' ELSE 'Выход NL' END WHERE id IN ('sim-3','sim-4')")
        }
        for (id in silent) sql.exec("UPDATE servers SET last_report_at=? WHERE id=?", now.minus(Duration.ofMinutes(4)), id)

        // A week of health samples every 5 minutes for every node with an agent.
        val ids = sql.query("SELECT id, max_peers FROM servers WHERE agent_token_hash IS NOT NULL AND active ORDER BY id")
        val rnd = java.util.Random(11)
        for (r in ids) {
            val id = r.str("id")
            val stop = if (id in silent) now.minus(Duration.ofMinutes(5)) else now.minus(Duration.ofMinutes(1))
            var t = now.minus(Duration.ofDays(7))
            while (t < stop) {
                val d = daily(t.atZone(ZoneOffset.UTC).hour)
                val rx = (scale(id) * (0.3 + d) * (0.85 + 0.3 * rnd.nextDouble())).toLong()
                sql.exec(
                    "INSERT INTO node_metrics (server_id, ts, cpu, mem_used, mem_total, rx_bps, tx_bps, online) VALUES (?,?,?,?,?,?,?,?)",
                    id, t, (baseCpu(id) * (0.7 + 0.5 * d) + 0.03 * rnd.nextDouble()).coerceAtMost(0.99), (700_000_000 + 500_000_000 * d).toLong(), 2_000_000_000L,
                    rx, (rx * 0.92).toLong(), (r.int("max_peers") * 0.04 * (0.4 + d)).toInt(),
                )
                t = t.plus(Duration.ofMinutes(5))
            }
        }
    }

    /** Some demo devices on the extra nodes, so their bars are not all empty (called with the demo users). */
    fun devicesFor(i: Int): String? = if (scenario != "many") null else listOf("sim-5", "sim-6", "sim-6", "sim-9", "sim-10", "sim-12", "sim-12", "sim-12", "sim-5", "sim-6")[i % 10]

    private fun daily(h: Int) = 0.35 + 0.65 * max(0.0, sin((h - 6) / 24.0 * 2 * Math.PI))

    /** The stand-in agents: every 20 seconds each node with an agent (but the silent ones) reports. */
    suspend fun agents(s: Services) {
        val counters = HashMap<String, Long>()
        val rnd = java.util.Random(5)
        while (true) {
            runCatching {
                val ctx = s.ctx
                val now = ctx.now()
                val rows = ctx.db.run { it.query("SELECT id, protocols FROM servers WHERE agent_token_hash IS NOT NULL AND active AND protocols <> '{}'::jsonb ORDER BY id") }
                val exits = ctx.db.run { sql -> sql.query("SELECT id FROM servers WHERE cascade_role='exit' AND active ORDER BY sort_order, id").map { it.str("id") } }
                for (r in rows) {
                    val id = r.str("id")
                    if (id in silent) continue
                    val protocol = r.json("protocols").keys.first()
                    val d = daily(now.atZone(ZoneOffset.UTC).hour)
                    val bps = scale(id) * (0.3 + d) * (0.85 + 0.3 * rnd.nextDouble())
                    val rx = counters.merge("$id/rx", (bps / 8 * 20).toLong(), Long::plus)!!
                    val tx = counters.merge("$id/tx", (bps * 0.92 / 8 * 20).toLong(), Long::plus)!!
                    // About two thirds of the node's devices are connected.
                    val peers = ctx.db.run { sql -> sql.query("SELECT peer_key FROM tunnels WHERE server_id=? AND protocol=?", id, protocol).map { it.str("peer_key") } }
                        .filter { (it.hashCode() and 3) != 0 }
                        .map { NodePeerCounter(it, rx / 1000, tx / 1000, lastHandshake = now.epochSecond) }
                    val balancer = if (ctx.cfg.vlessBalancer && id == "sim-ru") {
                        NodeBalancerReport(XrayBalancer.BALANCER, exits.filter { it !in silent }.take(1).map { XrayBalancer.PREFIX + it })
                    } else null
                    s.nodes.simulatedReport(
                        id,
                        NodeReportReq(
                            protocol, peers,
                            NodeSystem(cpu = (baseCpu(id) * (0.7 + 0.5 * d) + 0.03 * rnd.nextDouble()).coerceAtMost(0.99), memUsed = (700_000_000 + 500_000_000 * d).toLong(),
                                memTotal = 2_000_000_000, rxBytes = rx, txBytes = tx, uptime = 86_400, classes = "off", agent = "3"),
                            balancer,
                        ),
                    )
                }
            }.onFailure { log.warn("simulated node reports failed", it) }
            delay(20_000)
        }
    }
}
