package dev.jaganet.server.services

import dev.jaganet.api.AnalyticsDevice
import dev.jaganet.api.AnalyticsOverviewRes
import dev.jaganet.api.AnalyticsServer
import dev.jaganet.api.AnalyticsServerRes
import dev.jaganet.api.AnalyticsUser
import dev.jaganet.api.AnalyticsUserRes
import dev.jaganet.api.AnalyticsUserServer
import dev.jaganet.api.AnalyticsUsersRes
import dev.jaganet.api.TrafficPoint
import dev.jaganet.api.TrafficSplit
import dev.jaganet.server.Ctx
import dev.jaganet.server.badRequest
import dev.jaganet.server.db.Row
import dev.jaganet.server.db.Sql
import dev.jaganet.server.notFound
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Traffic by server, user and device for the owner, from the hourly samples (byte counts only).
 * Periods: 24h in hours, 7d / 30d / 90d in days (UTC).
 */
class Analytics(private val ctx: Ctx) {
    private class Period(val key: String, val unit: ChronoUnit, val count: Int)

    private fun period(key: String?) = when (key ?: "7d") {
        "24h" -> Period("24h", ChronoUnit.HOURS, 24)
        "7d" -> Period("7d", ChronoUnit.DAYS, 7)
        "30d" -> Period("30d", ChronoUnit.DAYS, 30)
        "90d" -> Period("90d", ChronoUnit.DAYS, 90)
        else -> throw badRequest("Unknown period")
    }

    private fun Period.start(now: Instant): Instant = now.truncatedTo(unit).minus((count - 1).toLong(), unit)
    private val Period.bucket get() = if (unit == ChronoUnit.HOURS) "hour" else "day"

    private fun split(r: Row?) = if (r == null) TrafficSplit() else TrafficSplit(
        r.longOrNull("down") ?: 0, r.longOrNull("up") ?: 0, r.longOrNull("tdown") ?: 0, r.longOrNull("tup") ?: 0,
    )

    private val SUMS = "coalesce(sum(s.rx_bytes),0) AS down, coalesce(sum(s.tx_bytes),0) AS up, " +
        "coalesce(sum(s.torrent_rx_bytes),0) AS tdown, coalesce(sum(s.torrent_tx_bytes),0) AS tup"

    /** Every bucket of the period, zeros where nothing was sampled. */
    private fun points(sql: Sql, p: Period, now: Instant, where: String, vararg args: Any?): List<TrafficPoint> {
        val start = p.start(now)
        val unit = if (p.unit == ChronoUnit.HOURS) "hour" else "day"
        val rows = sql.query(
            """SELECT date_trunc('$unit', s.bucket AT TIME ZONE 'UTC') AS b, $SUMS
               FROM traffic_samples s JOIN devices d ON d.id = s.device_id
               WHERE s.bucket >= ? $where GROUP BY 1""",
            start, *args,
        ).associateBy { (it["b"] as java.sql.Timestamp).toLocalDateTime().toInstant(java.time.ZoneOffset.UTC) }
        return (0 until p.count).map { i ->
            val ts = start.plus(i.toLong(), p.unit)
            val r = rows[ts]
            val s = split(r)
            TrafficPoint(ts.toString(), s.down, s.up, s.torrentDown + s.torrentUp)
        }
    }

    private fun classesOf(r: Row): String {
        if (r.strOrNull("agent_token_hash") == null) return TrafficClasses.read(ctx.cfg.trafficClassesFile, ctx.now()).status
        val sys = r.jsonOrNull("report")?.get("system") as? JsonObject ?: return "off"
        return (sys["classes"] as? JsonPrimitive)?.content ?: "off"
    }

    private fun servers(sql: Sql, since: Instant, now: Instant, id: String? = null): List<AnalyticsServer> = sql.query(
        """SELECT v.*,
             (SELECT count(*)::int FROM tunnels t WHERE t.server_id = v.id) AS devices,
             (SELECT count(DISTINCT d.user_id)::int FROM tunnels t JOIN devices d ON d.id = t.device_id WHERE t.server_id = v.id) AS users,
             (SELECT count(*)::int FROM tunnels t WHERE t.server_id = v.id AND t.last_seen_at > ?) AS online,
             x.active_devices, x.down, x.up, x.tdown, x.tup
           FROM servers v
           LEFT JOIN (SELECT s.server_id, count(DISTINCT s.device_id)::int AS active_devices, $SUMS
                      FROM traffic_samples s WHERE s.bucket >= ? GROUP BY s.server_id) x ON x.server_id = v.id
           WHERE (?::text IS NULL OR v.id = ?)
           ORDER BY v.active DESC, coalesce(x.down, 0) + coalesce(x.up, 0) DESC, v.name""",
        now.minus(NODE_OFFLINE), since, id, id,
    ).map { r ->
        AnalyticsServer(
            id = r.str("id"), name = r.str("name"), city = r.str("city"), countryCode = r.str("country_code").trim(),
            active = r.bool("active"), remote = r.strOrNull("agent_token_hash") != null,
            devices = r.int("devices"), activeDevices = r.longOrNull("active_devices")?.toInt() ?: 0, users = r.int("users"),
            online = r.int("online"), traffic = split(r), classes = classesOf(r),
        )
    }

    /**
     * Users with their devices and traffic. serverId: only devices and traffic on that server.
     * userId: one user. q: part of the e-mail or name.
     */
    private fun users(sql: Sql, since: Instant, serverId: String? = null, userId: String? = null, q: String? = null, limit: Int = 500): List<AnalyticsUser> {
        val like = q?.trim()?.takeIf { it.isNotEmpty() }?.let { "%" + it.lowercase().replace("%", "\\%").replace("_", "\\_") + "%" }
        return sql.query(
            """WITH dev AS (
                 SELECT d.id, d.user_id, t.server_id, t.last_seen_at FROM tunnels t JOIN devices d ON d.id = t.device_id
                 WHERE d.removed_at IS NULL AND (?::text IS NULL OR t.server_id = ?)
               ), tr AS (
                 SELECT d.user_id, count(DISTINCT s.device_id)::int AS active_devices, $SUMS
                 FROM traffic_samples s JOIN devices d ON d.id = s.device_id
                 WHERE s.bucket >= ? AND (?::text IS NULL OR s.server_id = ?) GROUP BY d.user_id
               )
               SELECT u.id, u.email, u.note,
                 (SELECT count(*)::int FROM dev WHERE dev.user_id = u.id) AS devices,
                 (SELECT max(dev.last_seen_at) FROM dev WHERE dev.user_id = u.id) AS last_seen,
                 (SELECT string_agg(DISTINCT v.name, '|') FROM dev JOIN servers v ON v.id = dev.server_id WHERE dev.user_id = u.id) AS server_names,
                 (SELECT p.source FROM subscriptions p WHERE p.user_id = u.id AND p.status = 'active' AND p.expires_at > now()
                    ORDER BY (p.source = 'test') LIMIT 1) AS paid,
                 tr.active_devices, tr.down, tr.up, tr.tdown, tr.tup
               FROM users u LEFT JOIN tr ON tr.user_id = u.id
               WHERE (?::uuid IS NULL OR u.id = ?::uuid)
                 AND (tr.user_id IS NOT NULL OR EXISTS (SELECT 1 FROM dev WHERE dev.user_id = u.id))
                 AND (?::text IS NULL OR lower(u.email) LIKE ? OR lower(coalesce(u.note, '')) LIKE ?)
               ORDER BY coalesce(tr.down, 0) + coalesce(tr.up, 0) DESC, u.created_at DESC
               LIMIT $limit""",
            serverId, serverId, since, serverId, serverId, userId, userId, like, like, like,
        ).map { r ->
            AnalyticsUser(
                id = r.str("id"), name = r.strOrNull("note")?.takeIf { it.isNotBlank() } ?: r.str("email"), email = r.str("email"),
                plan = when (r.strOrNull("paid")) { null -> "free"; "test" -> "test"; else -> "pro" },
                devices = r.int("devices"), activeDevices = r.longOrNull("active_devices")?.toInt() ?: 0,
                servers = r.strOrNull("server_names")?.split('|').orEmpty(),
                traffic = split(r), lastSeenAt = r.instantOrNull("last_seen")?.toString(),
            )
        }
    }

    suspend fun overview(periodKey: String?): AnalyticsOverviewRes = ctx.db.run { sql ->
        val p = period(periodKey); val now = ctx.now(); val since = p.start(now)
        val tot = sql.one("SELECT $SUMS, count(DISTINCT s.device_id)::int AS devs, count(DISTINCT d.user_id)::int AS users FROM traffic_samples s JOIN devices d ON d.id = s.device_id WHERE s.bucket >= ?", since)
        val servers = servers(sql, since, now)
        AnalyticsOverviewRes(
            period = p.key, bucket = p.bucket, totals = split(tot), users = tot?.int("users") ?: 0, activeDevices = tot?.int("devs") ?: 0,
            online = servers.sumOf { it.online }, points = points(sql, p, now, ""), servers = servers,
        )
    }

    suspend fun server(id: String, periodKey: String?): AnalyticsServerRes = ctx.db.run { sql ->
        val p = period(periodKey); val now = ctx.now(); val since = p.start(now)
        val server = servers(sql, since, now, id).firstOrNull() ?: throw notFound("Node not found")
        AnalyticsServerRes(p.key, p.bucket, server, points(sql, p, now, "AND s.server_id = ?", id), users(sql, since, serverId = id))
    }

    suspend fun users(periodKey: String?, q: String?): AnalyticsUsersRes = ctx.db.run { sql ->
        val p = period(periodKey)
        AnalyticsUsersRes(p.key, users(sql, p.start(ctx.now()), q = q))
    }

    suspend fun user(id: String, periodKey: String?): AnalyticsUserRes = ctx.db.run { sql ->
        if (runCatching { java.util.UUID.fromString(id) }.isFailure) throw notFound("User not found")
        val p = period(periodKey); val now = ctx.now(); val since = p.start(now)
        val user = users(sql, since, userId = id).firstOrNull()
            ?: sql.one("SELECT id, email, note FROM users WHERE id = ?::uuid", id)?.let {
                AnalyticsUser(it.str("id"), it.strOrNull("note")?.takeIf { n -> n.isNotBlank() } ?: it.str("email"), it.str("email"), "free", 0, 0, emptyList(), TrafficSplit())
            } ?: throw notFound("User not found")
        val servers = sql.query(
            """SELECT v.id, v.name, v.country_code, $SUMS FROM traffic_samples s JOIN devices d ON d.id = s.device_id JOIN servers v ON v.id = s.server_id
               WHERE d.user_id = ?::uuid AND s.bucket >= ? GROUP BY v.id, v.name, v.country_code ORDER BY 4 DESC""",
            id, since,
        ).map { AnalyticsUserServer(it.str("id"), it.str("name"), it.str("country_code").trim(), split(it)) }
        val devices = sql.query(
            """SELECT d.id, d.name, d.platform, d.removed_at, v.name AS server_name, v.country_code, t.last_seen_at, x.down, x.up, x.tdown, x.tup
               FROM devices d LEFT JOIN tunnels t ON t.device_id = d.id LEFT JOIN servers v ON v.id = t.server_id
               LEFT JOIN (SELECT s.device_id, $SUMS FROM traffic_samples s WHERE s.bucket >= ? GROUP BY s.device_id) x ON x.device_id = d.id
               WHERE d.user_id = ?::uuid AND ((d.removed_at IS NULL AND t.device_id IS NOT NULL) OR x.device_id IS NOT NULL)
               ORDER BY coalesce(x.down, 0) + coalesce(x.up, 0) DESC, d.created_at""",
            since, id,
        ).map {
            AnalyticsDevice(
                id = it.str("id"), name = it.str("name"), platform = it.str("platform"), serverName = it.strOrNull("server_name"),
                countryCode = it.strOrNull("country_code")?.trim(), traffic = split(it), lastSeenAt = it.instantOrNull("last_seen_at")?.toString(),
                removed = it.instantOrNull("removed_at") != null,
            )
        }
        AnalyticsUserRes(p.key, p.bucket, user, points(sql, p, now, "AND d.user_id = ?::uuid", id), servers, devices)
    }
}
