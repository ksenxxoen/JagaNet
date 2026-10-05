package dev.jaganet.server.services

import dev.jaganet.api.DeviceTraffic
import dev.jaganet.api.StatsBucket
import dev.jaganet.api.StatsPeriod
import dev.jaganet.api.StatsRes
import dev.jaganet.server.Ctx
import java.time.Instant

private const val HOUR = 3_600_000L

class Stats(private val ctx: Ctx) {
    /** period -> (number of buckets, bucket size ms) */
    private fun shape(p: StatsPeriod) = when (p) {
        StatsPeriod.DAY -> 12 to 2 * HOUR
        StatsPeriod.WEEK -> 7 to 24 * HOUR
        StatsPeriod.MONTH -> 30 to 24 * HOUR
    }

    suspend fun get(p: Principal, period: StatsPeriod): StatsRes = ctx.db.run { sql ->
        val (n, size) = shape(period)
        val nowMs = ctx.now().toEpochMilli()
        val end = Math.ceilDiv(nowMs, size) * size
        val start = end - n * size
        val buckets = LongArray(n * 2)
        val byDevice = linkedMapOf<String, DeviceTraffic>()
        var totalRx = 0L
        var totalTx = 0L
        for (s in sql.query(
            """SELECT t.device_id, d.name, t.bucket, t.rx_bytes, t.tx_bytes
                 FROM traffic_samples t JOIN devices d ON d.id=t.device_id
                WHERE d.user_id=?::uuid AND t.bucket >= ? AND t.bucket < ?""",
            p.user.id, Instant.ofEpochMilli(start), Instant.ofEpochMilli(end),
        )) {
            val i = ((s.instant("bucket").toEpochMilli() - start) / size).toInt()
            val rx = s.long("rx_bytes")
            val tx = s.long("tx_bytes")
            buckets[i * 2] += rx
            buckets[i * 2 + 1] += tx
            totalRx += rx
            totalTx += tx
            val id = s.str("device_id")
            byDevice[id] = DeviceTraffic(id, s.str("name"), (byDevice[id]?.bytes ?: 0) + rx + tx)
        }

        var protectedMs = 0L
        var peak: Long? = null
        val sessions = sql.query(
            """SELECT c.started_at, c.ended_at, c.peak_down_bps FROM connection_sessions c JOIN devices d ON d.id=c.device_id
                WHERE d.user_id=?::uuid AND c.started_at < ? AND (c.ended_at IS NULL OR c.ended_at > ?)""",
            p.user.id, Instant.ofEpochMilli(end), Instant.ofEpochMilli(start),
        )
        for (s in sessions) {
            val a = maxOf(s.instant("started_at").toEpochMilli(), start)
            val b = minOf(s.instantOrNull("ended_at")?.toEpochMilli() ?: nowMs, end, nowMs)
            protectedMs += maxOf(0, b - a)
            s.longOrNull("peak_down_bps")?.let { peak = maxOf(peak ?: 0, it) }
        }
        val protectedSeconds = protectedMs / 1000

        StatsRes(
            period = period,
            buckets = (0 until n).map { StatsBucket(Instant.ofEpochMilli(start + it * size).toString(), buckets[it * 2], buckets[it * 2 + 1]) },
            totalRxBytes = totalRx,
            totalTxBytes = totalTx,
            protectedSeconds = protectedSeconds,
            sessions = sessions.size,
            avgDownBps = if (protectedSeconds > 0) totalRx * 8 / protectedSeconds else null,
            peakDownBps = peak,
            byDevice = byDevice.values.sortedByDescending { it.bytes },
        )
    }
}
