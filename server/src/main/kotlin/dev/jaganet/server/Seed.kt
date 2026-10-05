package dev.jaganet.server

import dev.jaganet.api.Protocols
import dev.jaganet.server.db.Jsonb
import dev.jaganet.server.db.Sql
import dev.jaganet.server.services.Crypto
import dev.jaganet.server.services.monthStart
import dev.jaganet.server.services.node
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Base64
import kotlin.random.Random

/** Demo data for the simulation. Does nothing if users already exist. */
class Seed(private val ctx: Ctx) {
    private val now = ctx.now()
    private fun ago(d: Duration) = now.minus(d)
    private val day = Duration.ofDays(1)
    private val hour = Duration.ofHours(1)
    private var ip = 10

    suspend fun run() = ctx.db.run { sql ->
        if (sql.one("SELECT count(*)::int AS n FROM users")!!.int("n") > 0) return@run
        sql.exec(
            """INSERT INTO servers (id, name, city, country_code, subnet, protocols, max_peers, monthly_traffic_limit_bytes, paid_through)
               VALUES ('sim-1','Sim node 1','Frankfurt','DE','10.8.0.0/24',?,50,1000000000000,?)""",
            Jsonb("""{"wireguard":{"endpoint":"vpn1.sim.jaganet.dev:51820","publicKey":"c2ltdWxhdGVkLXNlcnZlci1wdWJsaWMta2V5LTAwMDA="},"jaga-custom":{"endpoint":"vpn1.sim.jaganet.dev:443"}}"""),
            LocalDate.ofInstant(now.plus(Duration.ofDays(40)), ZoneOffset.UTC),
        )
        sql.exec("INSERT INTO users (email, role, referral_code, created_at) VALUES ('owner@jaganet.dev','owner','OWNER-0000',?)", ago(Duration.ofDays(120)))

        val alex = user(sql, "alex@example.com")
        sub(sql, alex, "pro_monthly", ago(Duration.ofDays(55)), 30, status = "expired")
        sub(sql, alex, "pro_yearly", ago(Duration.ofDays(25)), 365, renewal = true)
        val laptop = device(sql, alex, "Work laptop", "desktop", tunnel = true, connected = true)
        val tablet = device(sql, alex, "Tablet", "android", tunnel = true, lastSeen = ago(Duration.ofDays(3)))
        history(sql, laptop, 30, 60e6, sessionsPerDay = 1)
        history(sql, tablet, 30, 8e6, sessionsPerDay = 0)

        val sam = user(sql, "sam@example.com", Duration.ofDays(20), referredBy = alex)
        val samPhone = device(sql, sam, "Old phone", "android")
        // ~70% of the 10 GB free allowance used this month
        sql.exec(
            "INSERT INTO traffic_samples (device_id, bucket, rx_bytes, tx_bytes) VALUES (?::uuid,?,5900000000,1100000000)",
            samPhone, maxOf(monthStart(now), ago(Duration.ofDays(2))).truncatedTo(ChronoUnit.HOURS),
        )

        // A believable customer base for the owner dashboard.
        for (i in 0 until 70) {
            val u = user(sql, "demo$i@example.com", Duration.ofDays(10L + i), referredBy = if (i % 6 == 0) alex else null)
            when {
                i < 12 -> {
                    val yearly = i % 3 != 0
                    sub(sql, u, if (yearly) "pro_yearly" else "pro_monthly", ago(day.multipliedBy((i % 7).toLong()).plus(hour.multipliedBy(2))), if (yearly) 365 else 30)
                }
                i < 15 -> sub(sql, u, "pro_monthly", ago(Duration.ofDays(20)), 30, status = "cancelled", cancelled = ago(Duration.ofDays(2)))
            }
        }
    }

    private fun user(sql: Sql, email: String, age: Duration = Duration.ofDays(60), referredBy: String? = null): String = sql.one(
        "INSERT INTO users (email, role, referral_code, referred_by, created_at) VALUES (?,'user',?,?::uuid,?) RETURNING id",
        email, Crypto.referralCode(email), referredBy, ago(age),
    )!!.str("id")

    private fun sub(sql: Sql, userId: String, product: String, start: Instant, days: Long, renewal: Boolean = false, status: String = "active", cancelled: Instant? = null) =
        sql.exec(
            """INSERT INTO subscriptions (user_id, product_id, source, external_id, started_at, expires_at, is_renewal, status, cancelled_at)
               VALUES (?::uuid,?,'dev',?,?,?,?,?,?)""",
            userId, product, "seed-$userId-$product-${start.toEpochMilli()}", start, start.plus(Duration.ofDays(days)), renewal, status, cancelled,
        )

    private suspend fun device(sql: Sql, userId: String, name: String, platform: String, tunnel: Boolean = false, connected: Boolean = false, lastSeen: Instant = now): String {
        val id = sql.one(
            "INSERT INTO devices (user_id, name, platform, created_at) VALUES (?::uuid,?,?,?) RETURNING id",
            userId, name, platform, ago(Duration.ofDays(50)),
        )!!.str("id")
        if (tunnel) {
            val server = sql.one("SELECT * FROM servers WHERE id='sim-1'")!!
            val node = server.node(Protocols.WIREGUARD)
            val driver = ctx.drivers[Protocols.WIREGUARD]!!
            val pub = Base64.getEncoder().encodeToString("seed-$id".padEnd(32, '.').take(32).toByteArray())
            val cp = buildJsonObject { put("publicKey", JsonPrimitive(pub)) }
            val address = "10.8.0.${ip++}"
            val added = driver.addPeer(node, id, address, cp)
            sql.exec(
                "INSERT INTO tunnels (device_id, server_id, protocol, address, peer_key, client_params, last_seen_at) VALUES (?::uuid,'sim-1','wireguard',?,?,?,?)",
                id, address, added.peerKey, cp, lastSeen,
            )
            if (connected) driver.onConnectionEvent(node, added.peerKey, true)
        }
        return id
    }

    /** Hourly traffic with an evening peak, plus connection sessions. */
    private fun history(sql: Sql, deviceId: String, days: Int, scale: Double, sessionsPerDay: Int) {
        for (h in days * 24 downTo 1) {
            val t = ago(hour.multipliedBy(h.toLong())).truncatedTo(ChronoUnit.HOURS)
            val shape = when (t.atZone(ZoneOffset.UTC).hour) { in 0..5 -> 0.05; in 6..8 -> 0.4; in 9..16 -> 0.7; else -> 1.3 }
            val rx = (scale * shape * (0.5 + Random.nextDouble())).toLong()
            if (rx < 1000) continue
            sql.exec("INSERT INTO traffic_samples (device_id, bucket, rx_bytes, tx_bytes) VALUES (?::uuid,?,?,?)", deviceId, t, rx, (rx * 0.19).toLong())
        }
        for (d in days downTo 1) repeat(sessionsPerDay) { s ->
            val start = ago(day.multipliedBy(d.toLong())).plus(hour.multipliedBy(8L + s * 6))
            sql.exec(
                "INSERT INTO connection_sessions (device_id, protocol, started_at, ended_at, peak_down_bps) VALUES (?::uuid,'wireguard',?,?,?)",
                deviceId, start, start.plus(Duration.ofMinutes(60L + Random.nextLong(120))), (60e6 + Random.nextDouble() * 60e6).toLong(),
            )
        }
    }
}
