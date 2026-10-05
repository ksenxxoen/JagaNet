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
            val driver = ctx.drivers[protocol] ?: continue
            val counters = driver.readCounters(server.node(protocol))
            ctx.db.run { sql ->
                for (c in counters) {
                    val t = sql.one(
                        """SELECT t.device_id, d.user_id, t.last_rx, t.last_tx FROM tunnels t JOIN devices d ON d.id=t.device_id
                            WHERE t.protocol=? AND t.peer_key=? AND t.server_id=?""",
                        protocol, c.peerKey, server.str("id"),
                    ) ?: continue
                    // Counters reset when the node restarts: then the whole value is new traffic.
                    val dRx = if (c.rxBytes >= t.long("last_rx")) c.rxBytes - t.long("last_rx") else c.rxBytes
                    val dTx = if (c.txBytes >= t.long("last_tx")) c.txBytes - t.long("last_tx") else c.txBytes
                    sql.exec(
                        "UPDATE tunnels SET last_rx=?, last_tx=?, last_seen_at=GREATEST(last_seen_at, ?) WHERE device_id=?::uuid",
                        c.rxBytes, c.txBytes, c.lastSeenAt, t.str("device_id"),
                    )
                    if (dRx + dTx == 0L) continue
                    // User's point of view: what the node sent is their download.
                    sql.exec(
                        """INSERT INTO traffic_samples (device_id, bucket, rx_bytes, tx_bytes) VALUES (?::uuid,?,?,?)
                           ON CONFLICT (device_id, bucket) DO UPDATE SET rx_bytes=traffic_samples.rx_bytes+EXCLUDED.rx_bytes,
                             tx_bytes=traffic_samples.tx_bytes+EXCLUDED.tx_bytes""",
                        t.str("device_id"), bucket, dTx, dRx,
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
