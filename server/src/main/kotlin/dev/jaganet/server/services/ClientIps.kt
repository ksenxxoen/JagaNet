package dev.jaganet.server.services

import dev.jaganet.server.db.Jsonb
import dev.jaganet.server.db.Sql
import dev.jaganet.server.protocols.PeerCounters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Duration
import java.time.Instant

/**
 * Where devices connect from (issue #39): the client address a node sees for each tunnel. AmneziaWG
 * and WireGuard nodes give the peer's endpoint, VLESS nodes the user's online IPs from Xray. Kept per
 * device for [KEEP] in `client_ips` (only the address and when it was seen), the latest one also in
 * `tunnels.last_ip`. Used by the admin panel: search by IP and "unique IPs in 24 hours".
 */
object ClientIps {
    val KEEP: Duration = Duration.ofDays(7)
    private val V4 = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")
    private val V6 = Regex("^[0-9a-fA-F:.]{2,45}$")

    /** "1.2.3.4:51820", "[2001:db8::1]:51820", "1.2.3.4" or "2001:db8::1" → the address; null for "(none)" and junk. */
    fun hostOf(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() && it != "(none)" } ?: return null
        val host = when {
            s.startsWith("[") -> s.substringAfter('[').substringBefore(']')
            s.count { it == ':' } == 1 -> s.substringBefore(':')
            else -> s
        }
        return host.lowercase().takeIf { V4.matches(it) || (':' in it && V6.matches(it)) }
    }

    /** The stored {"ip": epochSeconds} of a VLESS peer. */
    fun parseIps(o: JsonObject?): Map<String, Instant> = o.orEmpty().mapNotNull { (ip, v) ->
        val at = (v as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
        hostOf(ip)?.let { it to Instant.ofEpochSecond(at) }
    }.toMap()

    /** What an agent sent, checked (at most 64 addresses), as jsonb; null when empty. */
    fun ipsJson(ips: Map<String, Long>): Jsonb? {
        val ok = ips.entries.mapNotNull { (ip, at) -> hostOf(ip)?.takeIf { at > 0 }?.let { it to at } }.take(64)
        if (ok.isEmpty()) return null
        return Jsonb(JsonObject(ok.associate { (ip, at) -> ip to JsonPrimitive(at) }).toString())
    }

    /** The addresses in one counter with when each was seen: the endpoint at the last handshake, VLESS IPs at their own time. */
    fun seen(c: PeerCounters): Map<String, Instant> = buildMap {
        putAll(c.ips)
        val ep = hostOf(c.endpoint)
        val at = c.lastSeenAt
        if (ep != null && at != null) put(ep, maxOf(at, get(ep) ?: at))
    }

    /** Records the addresses of one device's counter (Traffic.collect, every minute). */
    fun record(sql: Sql, deviceId: String, serverId: String, c: PeerCounters) {
        val seen = seen(c)
        if (seen.isEmpty()) return
        for ((ip, at) in seen) {
            sql.exec(
                """INSERT INTO client_ips (device_id, ip, server_id, first_seen, last_seen) VALUES (?::uuid,?,?,?,?)
                   ON CONFLICT (device_id, ip) DO UPDATE SET last_seen=GREATEST(client_ips.last_seen, EXCLUDED.last_seen), server_id=EXCLUDED.server_id""",
                deviceId, ip, serverId, at, at,
            )
        }
        val (ip, at) = seen.entries.maxBy { it.value }.toPair()
        sql.exec(
            "UPDATE tunnels SET last_ip=?, last_ip_at=? WHERE device_id=?::uuid AND (last_ip_at IS NULL OR last_ip_at <= ?)",
            ip, at, deviceId, at,
        )
    }

    /** Addresses not seen for [KEEP] go. Runs every hour. */
    fun prune(sql: Sql, now: Instant): Int = sql.exec("DELETE FROM client_ips WHERE last_seen < ?", now.minus(KEEP))
}
