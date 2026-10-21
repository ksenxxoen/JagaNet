package dev.jaganet.server.services

import dev.jaganet.server.Ctx
import java.time.temporal.ChronoUnit

data class CollectResult(val samples: Int, val suspended: Int)

/**
 * Polls every node/protocol for cumulative peer counters, turns them into hourly
 * per-device samples and enforces the free plan's data limit. Runs on a timer;
 * a later multi-node setup can push counters instead.
 */
class Traffic(private val ctx: Ctx, private val ent: Entitlements, private val tunnels: Tunnels) {
    suspend fun collect(): CollectResult {
        val bucket = ctx.now().truncatedTo(ChronoUnit.HOURS)
        var samples = 0
        val touched = mutableSetOf<String>()
        val servers = ctx.db.run { it.query("SELECT * FROM servers WHERE active") }

        for (server in servers) for (protocol in server.json("protocols").keys) {
            if (ctx.drivers[protocol] == null) continue
            val counters = readCounters(ctx, server, protocol)
            ctx.db.run { sql ->
                for (c in counters) {
                    val t = sql.one(
                        """SELECT t.device_id, d.user_id, t.last_rx, t.last_tx, t.last_torrent_rx, t.last_torrent_tx FROM tunnels t JOIN devices d ON d.id=t.device_id
                            WHERE t.protocol=? AND t.peer_key=? AND t.server_id=?""",
                        protocol, c.peerKey, server.str("id"),
                    ) ?: continue
                    // Counters reset when the node restarts: then the whole value is new traffic.
                    fun delta(now: Long, last: Long) = if (now >= last) now - last else now
                    val dRx = delta(c.rxBytes, t.long("last_rx"))
                    val dTx = delta(c.txBytes, t.long("last_tx"))
                    // The BitTorrent part is counted inside the tunnel (no encryption overhead): never more than the whole.
                    val tRx = delta(c.torrentRx, t.long("last_torrent_rx")).coerceAtMost(dRx)
                    val tTx = delta(c.torrentTx, t.long("last_torrent_tx")).coerceAtMost(dTx)
                    sql.exec(
                        """UPDATE tunnels SET last_rx=?, last_tx=?, last_torrent_rx=?, last_torrent_tx=?, last_seen_at=GREATEST(last_seen_at, ?)
                           WHERE device_id=?::uuid""",
                        c.rxBytes, c.txBytes, c.torrentRx, c.torrentTx, c.lastSeenAt, t.str("device_id"),
                    )
                    if (dRx + dTx == 0L) continue
                    // User's point of view: what the node sent is their download.
                    sql.exec(
                        """INSERT INTO traffic_samples (device_id, bucket, rx_bytes, tx_bytes, torrent_rx_bytes, torrent_tx_bytes, server_id) VALUES (?::uuid,?,?,?,?,?,?)
                           ON CONFLICT (device_id, bucket) DO UPDATE SET rx_bytes=traffic_samples.rx_bytes+EXCLUDED.rx_bytes,
                             tx_bytes=traffic_samples.tx_bytes+EXCLUDED.tx_bytes,
                             torrent_rx_bytes=traffic_samples.torrent_rx_bytes+EXCLUDED.torrent_rx_bytes,
                             torrent_tx_bytes=traffic_samples.torrent_tx_bytes+EXCLUDED.torrent_tx_bytes,
                             server_id=EXCLUDED.server_id""",
                        t.str("device_id"), bucket, dTx, dRx, tTx, tRx, server.str("id"),
                    )
                    samples++
                    touched += t.str("user_id")
                }
            }
        }

        var suspended = 0
        for (userId in touched) {
            val over = ctx.db.run { sql ->
                val limit = ent.entitlement(sql, userId).monthlyDataLimitBytes
                limit != null && ent.monthlyUsageBytes(sql, userId) >= limit
            }
            if (!over) continue
            val devices = ctx.db.run { sql ->
                sql.query("SELECT t.device_id FROM tunnels t JOIN devices d ON d.id=t.device_id WHERE d.user_id=?::uuid", userId).map { it.str("device_id") }
            }
            for (d in devices) {
                ctx.db.tx { tunnels.revoke(it, d) }
                suspended++
            }
        }
        return CollectResult(samples, suspended)
    }
}
