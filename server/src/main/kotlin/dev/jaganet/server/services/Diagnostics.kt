package dev.jaganet.server.services

import dev.jaganet.api.DiagnosticsListRes
import dev.jaganet.api.DiagnosticsReport
import dev.jaganet.api.DiagnosticsReq
import dev.jaganet.api.DiagnosticsRes
import dev.jaganet.api.DiagnosticsSummary
import dev.jaganet.api.ErrorCode
import dev.jaganet.server.AppError
import dev.jaganet.server.Ctx
import dev.jaganet.server.badRequest
import dev.jaganet.server.db.Row
import dev.jaganet.server.notFound
import java.time.Duration

/**
 * Problem reports from the Boop apps ("Сообщить о проблеме", issue #11): stored as sent, shown to
 * the owner in the admin panel, kept [KEEP]. At most [PER_HOUR] per device per hour; the request
 * size is capped by the route ([MAX_BYTES]).
 */
class Diagnostics(private val ctx: Ctx) {
    companion object {
        const val MAX_BYTES = 256 * 1024
        const val PER_HOUR = 5
        val KEEP: Duration = Duration.ofDays(30)
        private const val LIST = 200
        private val ID_TEXT = Regex("^[0-9a-fA-F-]{36}$")
    }

    /** Trimmed, cut to [max] characters, null when empty. */
    private fun String?.clean(max: Int) = this?.trim()?.take(max)?.takeIf { it.isNotEmpty() }

    /** A plain number in a sane range, else null (a made-up value does not break the list). */
    private fun Long?.sane() = this?.takeIf { it in 0..(1L shl 50) }

    suspend fun submit(p: Principal, r: DiagnosticsReq): DiagnosticsRes {
        val text = r.text.trim()
        if (text.isEmpty()) throw badRequest("The report is empty")
        val now = ctx.now()
        val id = ctx.db.tx { sql ->
            // One device at a time, so two reports sent at once cannot both slip under the limit.
            sql.one("SELECT id FROM devices WHERE id=?::uuid FOR UPDATE", p.deviceId)
            val recent = sql.one(
                "SELECT count(*)::int AS n FROM diagnostics WHERE device_id=?::uuid AND received_at > ?",
                p.deviceId, now.minus(Duration.ofHours(1)),
            )!!.int("n")
            if (recent >= PER_HOUR) throw AppError(429, ErrorCode.TOO_MANY_ATTEMPTS, "Too many reports from this device, try again in an hour")
            val serverId = r.serverId.clean(64)?.takeIf { sql.one("SELECT 1 AS one FROM servers WHERE id=?", it) != null }
            sql.one(
                """INSERT INTO diagnostics (user_id, device_id, app_version, platform, os_version, state, problem, server_id, country,
                                            host, resolved, handshake_age_sec, rx_bytes, tx_bytes, probe, comment, body, received_at)
                   VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                p.user.id, p.deviceId, r.appVersion.clean(32), r.platform.clean(16), r.osVersion.clean(80),
                r.state.clean(16), r.problem.clean(40), serverId, r.country.clean(8)?.let { if (it.equals("auto", true)) "auto" else it.uppercase() },
                r.host.clean(253), r.resolved.mapNotNull { it.clean(64) }.take(16).joinToString(" ").ifEmpty { null },
                r.handshakeAgeSec.sane(), r.rxBytes.sane(), r.txBytes.sane(), r.probe.clean(200),
                r.comment.clean(2000), text, now,
            )!!.long("id")
        }
        return DiagnosticsRes(id.toString())
    }

    /** Reports older than 30 days go. Runs every hour with the failure cleanup. */
    suspend fun prune(): Int = ctx.db.run { it.exec("DELETE FROM diagnostics WHERE received_at < ?", ctx.now().minus(KEEP)) }

    private val select = """SELECT g.*, u.email, d.name AS device_name, s.name AS server_name
                              FROM diagnostics g
                              LEFT JOIN users u ON u.id = g.user_id
                              LEFT JOIN devices d ON d.id = g.device_id
                              LEFT JOIN servers s ON s.id = g.server_id"""

    private fun Row.summary() = DiagnosticsSummary(
        id = long("id").toString(),
        at = instant("received_at").toString(),
        userId = strOrNull("user_id"),
        email = strOrNull("email"),
        deviceId = strOrNull("device_id"),
        deviceName = strOrNull("device_name"),
        appVersion = strOrNull("app_version"),
        platform = strOrNull("platform"),
        osVersion = strOrNull("os_version"),
        state = strOrNull("state"),
        problem = strOrNull("problem"),
        serverId = strOrNull("server_id"),
        serverName = strOrNull("server_name"),
        country = strOrNull("country"),
        hasComment = strOrNull("comment") != null,
    )

    /** The admin panel's list: the latest 200, optionally of one user (e-mail part or id). */
    suspend fun list(user: String?): DiagnosticsListRes = ctx.db.run { sql ->
        val q = user?.trim()?.takeIf { it.isNotEmpty() }
        val (where, args) = when {
            q == null -> "TRUE" to emptyList()
            ID_TEXT.matches(q) -> "g.user_id = ?::uuid" to listOf(q)
            else -> "u.email ILIKE ?" to listOf("%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
        }
        DiagnosticsListRes(sql.query("$select WHERE $where ORDER BY g.received_at DESC, g.id DESC LIMIT $LIST", *args.toTypedArray()).map { it.summary() })
    }

    /** One report with its text. */
    suspend fun get(id: String): DiagnosticsReport = ctx.db.run { sql ->
        val n = id.toLongOrNull() ?: throw notFound("No such report")
        val r = sql.one("$select WHERE g.id = ?", n) ?: throw notFound("No such report")
        DiagnosticsReport(
            report = r.summary(),
            comment = r.strOrNull("comment"),
            host = r.strOrNull("host"),
            resolved = r.strOrNull("resolved")?.split(' ')?.filter { it.isNotEmpty() } ?: emptyList(),
            handshakeAgeSec = r.longOrNull("handshake_age_sec"),
            rxBytes = r.longOrNull("rx_bytes"),
            txBytes = r.longOrNull("tx_bytes"),
            probe = r.strOrNull("probe"),
            text = r.str("body"),
        )
    }
}
