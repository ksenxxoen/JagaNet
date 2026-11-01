package dev.jaganet.server.services

import dev.jaganet.api.AdminNode
import dev.jaganet.api.CheckLevel
import dev.jaganet.api.HealthCheck
import dev.jaganet.api.MonitorRes
import dev.jaganet.api.NodeState
import dev.jaganet.api.NodeStatus
import dev.jaganet.api.i18n.Lang
import dev.jaganet.server.Ctx
import dev.jaganet.server.db.Sql
import dev.jaganet.server.monitor.Monitor
import kotlinx.serialization.Serializable
import java.net.URI
import java.time.Duration
import java.time.Instant

/* GET /v1/admin/servers/overview: everything the owner's status page shows, in one answer. */

/** Why a server is not plainly "ok": [code] picks the text on the page, [args] fill it. */
@Serializable
data class OverviewIssue(
    /** disabled, maintenance, waiting, silent, vpn, cpu, memory, full, degraded, blocked, check */
    val code: String,
    /** "warn", "down" or "off" */
    val level: String,
    /** "check" only: the monitor check's English text, filled from [args] like the checks themselves. */
    val message: String? = null,
    val args: Map<String, String> = emptyMap(),
)

/** One point of a node's history (an average over a bucket); null where nothing was reported. */
@Serializable
data class NodePoint(
    val ts: String,
    /** 0..1 */
    val cpu: Double? = null,
    /** Memory used, 0..1 */
    val mem: Double? = null,
    val rxBps: Long? = null,
    val txBps: Long? = null,
    val online: Int? = null,
)

/** A balancing cascade entry's choice, from its agent (version 3): exit node ids. */
@Serializable
data class OverviewBalancer(val selected: List<String> = emptyList(), val override: String? = null, val reportedAt: String? = null)

@Serializable
data class OverviewServer(
    val id: String,
    val name: String,
    val displayName: String? = null,
    val countryName: String? = null,
    val city: String,
    val countryCode: String,
    /** What the machine is called: its host name, else the VPN address's host. */
    val host: String? = null,
    val protocols: List<String> = emptyList(),
    /** False for the VPN on the main server itself. */
    val remote: Boolean,
    val active: Boolean,
    /** online, offline, waiting, disabled (as on the Nodes tab). */
    val state: String,
    /** The owner's mark for the apps ([NodeStatus]) and its note. */
    val status: String = NodeStatus.OK,
    val notice: String? = null,
    /** ok (green), warn (orange), down (red), off (gray): one color language on the whole page. */
    val health: String,
    val issues: List<OverviewIssue> = emptyList(),
    /** "entry" or "exit" when the balancer is on (VLESS_BALANCER=1), else null. */
    val cascadeRole: String? = null,
    val peers: Int,
    val maxPeers: Int,
    val online: Int,
    val cpu: Double? = null,
    val memUsed: Long? = null,
    val memTotal: Long? = null,
    val rxBps: Long? = null,
    val txBps: Long? = null,
    val lastReportAt: String? = null,
    val agentVersion: String? = null,
    val traffic24h: Long = 0,
    /** Traffic (down + up, bits per second) over the range in [ServersOverview.SPARK] equal steps, oldest first. */
    val spark: List<Long?> = emptyList(),
    /** Only for the servers asked for with ?open=: the detailed charts' points, oldest first. */
    val history: List<NodePoint>? = null,
    val balancer: OverviewBalancer? = null,
)

/** The main server itself: its latest measurements and the services the map shows. */
@Serializable
data class OverviewMain(
    /** The public host name of the site and API. */
    val host: String,
    val cpu: Double? = null,
    val memUsed: Long? = null,
    val memTotal: Long? = null,
    val diskFree: Long? = null,
    val diskTotal: Long? = null,
    val rxBps: Long? = null,
    val txBps: Long? = null,
    val measuredAt: String? = null,
    /** SMTP is set up (the admin panel or SMTP_*). */
    val mailReady: Boolean,
    /** TELEGRAM_BOT_TOKEN is set. */
    val botEnabled: Boolean,
)

@Serializable
data class ServersOverviewRes(
    val now: String,
    val range: String,
    /** VLESS_BALANCER=1: cascade entries balance over the exits (issue #41). */
    val balancer: Boolean,
    val main: OverviewMain,
    /** The main server's monitoring as on the old status page; its series only when the main server is open. */
    val monitor: MonitorRes,
    val servers: List<OverviewServer>,
)

/**
 * The status page's data: the main server's monitoring, every node with a health verdict, short
 * traffic history for sparklines and, for open cards, the detailed history. Node history comes from
 * node_metrics (one sample per agent report, V24); the main server's from server_metrics.
 */
class ServersOverview(private val ctx: Ctx, private val nodes: Nodes, private val monitor: Monitor) {
    companion object {
        const val SPARK = 40
        const val HISTORY = 120
        val RANGES = mapOf("1h" to Duration.ofHours(1), "24h" to Duration.ofHours(24), "7d" to Duration.ofDays(7), "30d" to Duration.ofDays(30))
        const val HOT = 0.9

        private fun pct(v: Double) = Math.round(v * 100).toString()

        /**
         * The verdict for one server. A remote node: switched off, in maintenance or not installed is
         * gray; no report for 3 minutes is red; processor, memory or device slots at 90% and the owner's
         * "degraded" or "blocked" mark are orange. The main server's own VPN takes the monitor's checks:
         * VPN, database or website down is red, any other warning orange.
         */
        fun classify(n: AdminNode, checks: List<HealthCheck>): Pair<String, List<OverviewIssue>> {
            val off = when {
                !n.active -> "disabled"
                n.status == NodeStatus.MAINTENANCE -> "maintenance"
                n.state == NodeState.WAITING -> "waiting"
                else -> null
            }
            if (off != null) return "off" to listOf(OverviewIssue(off, "off"))
            val issues = mutableListOf<OverviewIssue>()
            if (n.remote) {
                if (n.state == NodeState.OFFLINE) issues += OverviewIssue("silent", "down", args = n.lastReportAt?.let { mapOf("since" to it) } ?: emptyMap())
            } else {
                for (c in checks) {
                    if (c.level != CheckLevel.WARNING && c.level != CheckLevel.CRITICAL) continue
                    if (c.key == "nodes" || c.key == "blocklist") continue // about other machines
                    val down = c.level == CheckLevel.CRITICAL && c.key in setOf("vpn", "db", "https")
                    issues += OverviewIssue(if (c.key == "vpn") "vpn" else "check", if (down) "down" else "warn", c.message, c.args + ("key" to c.key))
                }
            }
            val live = n.remote && n.state == NodeState.ONLINE
            if (live && (n.cpu ?: 0.0) >= HOT) issues += OverviewIssue("cpu", "warn", args = mapOf("p" to pct(n.cpu!!)))
            if (live && n.memTotal != null && n.memTotal!! > 0 && (n.memUsed ?: 0).toDouble() / n.memTotal!! >= HOT) {
                issues += OverviewIssue("memory", "warn", args = mapOf("p" to pct((n.memUsed ?: 0).toDouble() / n.memTotal!!)))
            }
            if (n.maxPeers > 0 && n.peers >= n.maxPeers * HOT) issues += OverviewIssue("full", "warn", args = mapOf("peers" to "${n.peers}", "max" to "${n.maxPeers}"))
            if (n.status == NodeStatus.DEGRADED || n.status == NodeStatus.BLOCKED) issues += OverviewIssue(n.status, "warn")
            val health = when {
                issues.any { it.level == "down" } -> "down"
                issues.isNotEmpty() -> "warn"
                else -> "ok"
            }
            return health to issues.sortedBy { if (it.level == "down") 0 else 1 }
        }
    }

    suspend fun get(rangeKey: String?, open: Set<String>, lang: Lang): ServersOverviewRes {
        val range = rangeKey?.takeIf { it in RANGES } ?: "24h"
        val span = RANGES.getValue(range)
        val now = ctx.now()
        val list = nodes.list()
        val mainOpen = list.any { !it.remote && it.id in open } || "main" in open
        val mon = monitor.overview(range, lang, withSeries = mainOpen)
        val checks = mon.checks
        val balancerOn = ctx.cfg.vlessBalancer
        return ctx.db.run { sql ->
            val traffic = sql.query(
                "SELECT server_id, coalesce(sum(rx_bytes + tx_bytes), 0)::bigint AS b FROM traffic_samples WHERE bucket >= ? AND server_id IS NOT NULL GROUP BY 1",
                now.minus(Duration.ofDays(1)),
            ).associate { it.str("server_id") to it.long("b") }
            val reports = sql.query("SELECT id, report FROM servers").associate { it.str("id") to storedReport(it) }
            val sparkStep = maxOf(60L, span.seconds / SPARK)
            val remoteSpark = spark(sql, "node_metrics", now, sparkStep, byServer = true)
            val localSpark = spark(sql, "server_metrics", now, sparkStep, byServer = false)[""]
            val last = sql.one("SELECT * FROM server_metrics ORDER BY ts DESC LIMIT 1")
            val histStep = maxOf(60L, span.seconds / HISTORY)
            val servers = list.map { n ->
                val (health, issues) = classify(n, checks)
                val rep = reports[n.id]
                val local = !n.remote
                OverviewServer(
                    id = n.id, name = n.name, displayName = n.displayName, countryName = n.countryName, city = n.city, countryCode = n.countryCode,
                    host = n.hostname ?: n.endpoint?.substringBeforeLast(':')?.takeIf { !it.matches(Regex("^[0-9.]+$")) } ?: n.vless?.host,
                    protocols = n.protocols, remote = n.remote, active = n.active,
                    state = n.state.name.lowercase(), status = n.status, notice = n.notice, health = health, issues = issues,
                    cascadeRole = n.cascadeRole.takeIf { balancerOn },
                    peers = n.peers, maxPeers = n.maxPeers, online = n.online,
                    // The main server's VPN runs on the main machine: its measurements are the monitor's.
                    cpu = if (local) last?.let { (it["cpu"] as Number?)?.toDouble() } else n.cpu,
                    memUsed = if (local) last?.long("mem_used") else n.memUsed,
                    memTotal = if (local) last?.long("mem_total") else n.memTotal,
                    rxBps = if (local) last?.long("rx_bps") else n.rxBps,
                    txBps = if (local) last?.long("tx_bps") else n.txBps,
                    lastReportAt = if (local) last?.instant("ts")?.toString() else n.lastReportAt,
                    agentVersion = rep?.system?.agent,
                    traffic24h = traffic[n.id] ?: 0,
                    spark = if (local) localSpark ?: List(SPARK) { null } else remoteSpark[n.id] ?: List(SPARK) { null },
                    history = if (n.remote && n.id in open) history(sql, n.id, now, histStep) else null,
                    balancer = if (balancerOn && n.cascadeRole == CASCADE_ENTRY) rep?.balancer?.let { b ->
                        OverviewBalancer(
                            b.selected.map { it.removePrefix(XrayBalancer.PREFIX) },
                            b.override?.removePrefix(XrayBalancer.PREFIX),
                            Instant.ofEpochMilli(rep.at).toString(),
                        )
                    } else null,
                )
            }
            ServersOverviewRes(
                now = now.toString(), range = range, balancer = balancerOn,
                main = OverviewMain(
                    host = runCatching { URI(ctx.cfg.publicUrl).host }.getOrNull() ?: ctx.cfg.publicUrl,
                    cpu = last?.let { (it["cpu"] as Number?)?.toDouble() }, memUsed = last?.long("mem_used"), memTotal = last?.long("mem_total"),
                    diskFree = last?.long("disk_free"), diskTotal = last?.long("disk_total"), rxBps = last?.long("rx_bps"), txBps = last?.long("tx_bps"),
                    measuredAt = last?.instant("ts")?.toString(),
                    mailReady = ctx.live.smtp != null, botEnabled = ctx.cfg.telegramBotToken != null,
                ),
                monitor = mon,
                servers = servers,
            )
        }
    }

    /** Down + up traffic per [step] seconds over the last [SPARK] steps, by server (or one series, key ""). */
    private fun spark(sql: Sql, table: String, now: Instant, step: Long, byServer: Boolean): Map<String, List<Long?>> {
        val end = now.epochSecond / step
        val start = end - SPARK + 1
        val key = if (byServer) "server_id" else "''"
        val rows = sql.query(
            """SELECT $key AS k, floor(extract(epoch FROM ts) / ?)::bigint AS b, avg(rx_bps + tx_bps)::bigint AS v
                 FROM $table WHERE ts >= ? GROUP BY 1, 2""",
            step, Instant.ofEpochSecond(start * step),
        )
        return rows.groupBy { it.str("k") }.mapValues { (_, rs) ->
            val slots = arrayOfNulls<Long>(SPARK)
            for (r in rs) {
                val i = (r.long("b") - start).toInt()
                if (i in 0 until SPARK) slots[i] = r.long("v")
            }
            slots.toList()
        }
    }

    /** One node's detailed history: [HISTORY] steps of [step] seconds, empty steps kept as gaps. */
    private fun history(sql: Sql, id: String, now: Instant, step: Long): List<NodePoint> {
        val end = now.epochSecond / step
        val start = end - HISTORY + 1
        val rows = sql.query(
            """SELECT floor(extract(epoch FROM ts) / ?)::bigint AS b, avg(cpu) AS cpu,
                      avg(CASE WHEN mem_total > 0 THEN mem_used::float8 / mem_total END) AS mem,
                      avg(rx_bps)::bigint AS rx, avg(tx_bps)::bigint AS tx, max(online) AS online
                 FROM node_metrics WHERE server_id = ? AND ts >= ? GROUP BY 1""",
            step, id, Instant.ofEpochSecond(start * step),
        ).associateBy { it.long("b") }
        return (start..end).map { b ->
            val r = rows[b]
            NodePoint(
                Instant.ofEpochSecond(b * step).toString(),
                (r?.get("cpu") as Number?)?.toDouble(), (r?.get("mem") as Number?)?.toDouble(),
                r?.long("rx"), r?.long("tx"), (r?.get("online") as Number?)?.toInt(),
            )
        }
    }
}
