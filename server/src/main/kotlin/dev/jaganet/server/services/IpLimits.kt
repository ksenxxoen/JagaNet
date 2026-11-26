package dev.jaganet.server.services

import dev.jaganet.api.Protocols
import dev.jaganet.server.Ctx
import dev.jaganet.server.badRequest
import dev.jaganet.server.db.Sql
import java.time.Duration
import java.time.Instant

const val IP_LIMIT_MODE_OFF = "off"
const val IP_LIMIT_MODE_OBSERVE = "observe"
const val IP_LIMIT_MODE_ENFORCE = "enforce"
val IP_LIMIT_MODES = setOf(IP_LIMIT_MODE_OFF, IP_LIMIT_MODE_OBSERVE, IP_LIMIT_MODE_ENFORCE)
const val DEFAULT_IP_LIMIT_WINDOW_SECONDS = 120
private val SETTINGS_KEY = "control"

/** One address seen for a key, possibly on several nodes; [lastSeen] is the latest of them. */
data class IpSighting(
    val ip: String,
    val firstSeen: Instant,
    val lastSeen: Instant,
    val serverId: String? = null,
    val serverName: String? = null,
)

data class IpLimitDecision(
    val allowed: List<IpSighting>,
    val rejected: List<IpSighting>,
    /** Addresses the nodes should drop in enforce mode: rejected plus kicks that are still active. */
    val blocked: List<IpSighting>,
)

/**
 * Concurrent IP cap for a key. Already-active addresses (earliest [IpSighting.firstSeen]) keep their
 * slots; a new address is refused while the cap is full. A kicked address does not occupy a slot
 * and stays blocked until it goes quiet.
 */
object IpLimitLogic {
    fun effectiveLimit(override: Int?, planLimit: Int): Int? = when (override) {
        null -> planLimit.takeIf { it > 0 }
        0 -> null
        else -> override
    }

    fun decide(active: List<IpSighting>, limit: Int?, kicked: Set<String>): IpLimitDecision {
        val merged = active.groupBy { it.ip }.values.map { rows ->
            rows.reduce { a, b ->
                val newer = if (a.lastSeen >= b.lastSeen) a else b
                IpSighting(a.ip, minOf(a.firstSeen, b.firstSeen), maxOf(a.lastSeen, b.lastSeen), newer.serverId, newer.serverName)
            }
        }
        val kickedActive = merged.filter { it.ip in kicked }
        val candidates = merged.filter { it.ip !in kicked }.sortedWith(compareBy<IpSighting> { it.firstSeen }.thenBy { it.ip })
        val cap = limit?.takeIf { it > 0 }
        val (allowed, rejected) = if (cap == null) candidates to emptyList() else candidates.take(cap) to candidates.drop(cap)
        return IpLimitDecision(allowed, rejected, rejected + kickedActive)
    }
}

fun loadControlSettings(sql: Sql): CtlSettings =
    sql.one("SELECT value FROM settings WHERE key=?", SETTINGS_KEY)?.json("value")
        ?.let { runCatching { Protocols.decode<CtlSettings>(it) }.getOrNull() }
        ?: CtlSettings()

/**
 * Applies the concurrent-IP cap across every node: records refused addresses, and in enforce mode
 * tells the agents which source IPs to drop on the Xray ports.
 */
class IpLimits(private val ctx: Ctx, private val ent: Entitlements, private val nodes: Nodes) {

    fun window(s: CtlSettings = CtlSettings()): Duration =
        Duration.ofSeconds(s.ipLimitWindowSeconds.coerceIn(30, 3600).toLong())

    /** Distinct IPs of [deviceId] with [client_ips.last_seen] inside the window, all nodes together. */
    fun activeIps(sql: Sql, deviceId: String, now: Instant, window: Duration): List<IpSighting> = sql.query(
        """SELECT c.ip, min(c.first_seen) AS first_seen, max(c.last_seen) AS last_seen,
                  (array_agg(c.server_id ORDER BY c.last_seen DESC))[1] AS server_id,
                  (array_agg(s.name ORDER BY c.last_seen DESC))[1] AS server_name
             FROM client_ips c LEFT JOIN servers s ON s.id = c.server_id
            WHERE c.device_id=?::uuid AND c.last_seen > ?
            GROUP BY c.ip""",
        deviceId, now.minus(window),
    ).map {
        IpSighting(it.str("ip"), it.instant("first_seen"), it.instant("last_seen"), it.strOrNull("server_id"), it.strOrNull("server_name"))
    }

    fun rejectedIps(sql: Sql, deviceId: String, now: Instant, window: Duration): List<IpSighting> = sql.query(
        """SELECT r.ip, r.first_seen, r.last_seen, r.server_id, s.name AS server_name
             FROM ip_limit_rejects r LEFT JOIN servers s ON s.id = r.server_id
            WHERE r.device_id=?::uuid AND r.last_seen > ?
            ORDER BY r.last_seen DESC""",
        deviceId, now.minus(window),
    ).map {
        IpSighting(it.str("ip"), it.instant("first_seen"), it.instant("last_seen"), it.strOrNull("server_id"), it.strOrNull("server_name"))
    }

    fun kickedIps(sql: Sql, deviceId: String): Set<String> =
        sql.query("SELECT ip FROM ip_limit_kicks WHERE device_id=?::uuid", deviceId).map { it.str("ip") }.toSet()

    fun planLimit(sql: Sql, userId: String): Int = ent.entitlement(sql, userId).deviceLimit

    /**
     * Rebuilds the refuse log and the block list the agents should apply. Returns the source IPs
     * to drop when the mode is enforce (empty otherwise). Wakes VLESS agents if that list changed.
     */
    fun recompute(sql: Sql, now: Instant = ctx.now()): List<String> {
        val s = loadControlSettings(sql)
        val window = window(s)
        val cutoff = now.minus(window)
        val before = if (s.ipLimitMode == IP_LIMIT_MODE_ENFORCE) currentBlocks(sql) else emptySet()
        if (s.ipLimitMode == IP_LIMIT_MODE_OFF) {
            sql.exec("DELETE FROM ip_limit_rejects")
            dropStaleKicks(sql, cutoff)
            if (before.isNotEmpty()) notifyVlessNodes(sql)
            return emptyList()
        }
        val devices = sql.query(
            """SELECT d.id, d.user_id, d.ip_limit FROM devices d
                JOIN tunnels t ON t.device_id = d.id
               WHERE d.removed_at IS NULL""",
        )
        val planByUser = mutableMapOf<String, Int>()
        val blocked = linkedSetOf<String>()
        for (d in devices) {
            val userId = d.str("user_id")
            val plan = planByUser.getOrPut(userId) { planLimit(sql, userId) }
            val limit = IpLimitLogic.effectiveLimit(d.longOrNull("ip_limit")?.toInt(), plan)
            val id = d.str("id")
            val decision = IpLimitLogic.decide(activeIps(sql, id, now, window), limit, kickedIps(sql, id))
            writeRejects(sql, id, decision.rejected, cutoff)
            dropStaleKicksFor(sql, id, cutoff)
            if (s.ipLimitMode == IP_LIMIT_MODE_ENFORCE) decision.blocked.forEach { blocked.add(it.ip) }
        }
        // Kicks whose IP is not in client_ips yet (just reset) must still be dropped at once.
        if (s.ipLimitMode == IP_LIMIT_MODE_ENFORCE) {
            sql.query("SELECT ip FROM ip_limit_kicks").forEach { blocked.add(it.str("ip")) }
        }
        val list = blocked.toList().sorted()
        if (s.ipLimitMode == IP_LIMIT_MODE_ENFORCE) {
            if (list.toSet() != before) notifyVlessNodes(sql)
        } else if (before.isNotEmpty()) notifyVlessNodes(sql)
        return list
    }

    private fun currentBlocks(sql: Sql): Set<String> {
        // Same computation as the last enforce pass would send: rejected in-window + all kicks.
        val s = loadControlSettings(sql)
        val window = window(s)
        val now = ctx.now()
        val out = linkedSetOf<String>()
        sql.query("SELECT ip FROM ip_limit_rejects WHERE last_seen > ?", now.minus(window)).forEach { out.add(it.str("ip")) }
        sql.query("SELECT ip FROM ip_limit_kicks").forEach { out.add(it.str("ip")) }
        return out
    }

    private fun writeRejects(sql: Sql, deviceId: String, rejected: List<IpSighting>, cutoff: Instant) {
        sql.exec("DELETE FROM ip_limit_rejects WHERE device_id=?::uuid AND last_seen <= ?", deviceId, cutoff)
        val keep = rejected.map { it.ip }.toSet()
        sql.query("SELECT ip FROM ip_limit_rejects WHERE device_id=?::uuid", deviceId).map { it.str("ip") }
            .filter { it !in keep }
            .forEach { sql.exec("DELETE FROM ip_limit_rejects WHERE device_id=?::uuid AND ip=?", deviceId, it) }
        for (r in rejected) {
            sql.exec(
                """INSERT INTO ip_limit_rejects (device_id, ip, first_seen, last_seen, server_id) VALUES (?::uuid,?,?,?,?)
                   ON CONFLICT (device_id, ip) DO UPDATE SET last_seen=GREATEST(ip_limit_rejects.last_seen, EXCLUDED.last_seen),
                     first_seen=LEAST(ip_limit_rejects.first_seen, EXCLUDED.first_seen),
                     server_id=COALESCE(EXCLUDED.server_id, ip_limit_rejects.server_id)""",
                deviceId, r.ip, r.firstSeen, r.lastSeen, r.serverId,
            )
        }
    }

    private fun dropStaleKicks(sql: Sql, cutoff: Instant) {
        sql.exec(
            """DELETE FROM ip_limit_kicks k WHERE NOT EXISTS (
                 SELECT 1 FROM client_ips c WHERE c.device_id=k.device_id AND c.ip=k.ip AND c.last_seen > ?)""",
            cutoff,
        )
    }

    private fun dropStaleKicksFor(sql: Sql, deviceId: String, cutoff: Instant) {
        sql.exec(
            """DELETE FROM ip_limit_kicks k WHERE k.device_id=?::uuid AND NOT EXISTS (
                 SELECT 1 FROM client_ips c WHERE c.device_id=k.device_id AND c.ip=k.ip AND c.last_seen > ?)""",
            deviceId, cutoff,
        )
    }

    fun prune(sql: Sql, now: Instant): Int {
        val n = sql.exec("DELETE FROM ip_limit_rejects WHERE last_seen < ?", now.minus(ClientIps.KEEP))
        dropStaleKicks(sql, now.minus(window(loadControlSettings(sql))))
        return n
    }

    fun blocksForAgents(sql: Sql): List<String> {
        val s = loadControlSettings(sql)
        if (s.ipLimitMode != IP_LIMIT_MODE_ENFORCE) return emptyList()
        return currentBlocks(sql).sorted()
    }

    fun setOverrideIn(sql: Sql, deviceId: String, ipLimit: Int?) {
        if (ipLimit != null && ipLimit !in 0..100) throw badRequest("Enter 0 to 100, or leave empty for the plan's limit")
        sql.exec("UPDATE devices SET ip_limit=? WHERE id=?::uuid", ipLimit, deviceId)
        recompute(sql)
    }

    fun kickIp(sql: Sql, deviceId: String, ip: String, now: Instant = ctx.now()) {
        val host = ClientIps.hostOf(ip) ?: throw badRequest("Enter an IP address")
        sql.exec(
            """INSERT INTO ip_limit_kicks (device_id, ip, kicked_at) VALUES (?::uuid,?,?)
               ON CONFLICT (device_id, ip) DO UPDATE SET kicked_at=EXCLUDED.kicked_at""",
            deviceId, host, now,
        )
        recompute(sql)
    }

    fun kickAll(sql: Sql, deviceId: String, now: Instant = ctx.now()) {
        val s = loadControlSettings(sql)
        val ips = activeIps(sql, deviceId, now, window(s)).map { it.ip }.toSet() + kickedIps(sql, deviceId)
        for (ip in ips) {
            sql.exec(
                """INSERT INTO ip_limit_kicks (device_id, ip, kicked_at) VALUES (?::uuid,?,?)
                   ON CONFLICT (device_id, ip) DO UPDATE SET kicked_at=EXCLUDED.kicked_at""",
                deviceId, ip, now,
            )
        }
        recompute(sql)
    }

    fun notifyVlessNodes(sql: Sql) {
        sql.query("SELECT id FROM servers WHERE active AND protocols ? 'vless'").forEach { ctx.nodeHub.changed(it.str("id")) }
    }
}
