package dev.jaganet.server

import dev.jaganet.api.Protocols
import dev.jaganet.server.db.Jsonb
import dev.jaganet.server.db.Sql
import dev.jaganet.server.services.Crypto
import dev.jaganet.server.services.monthStart
import dev.jaganet.server.services.node
import dev.jaganet.server.protocols.AwgParams
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    suspend fun run() {
        seedAll()
        seedMetrics()
    }

    /** A week of server measurements every 5 minutes, with a daily rhythm and one past incident. */
    private suspend fun seedMetrics() = ctx.db.run { sql ->
        if (sql.one("SELECT count(*)::int AS n FROM server_metrics")!!.int("n") > 0) return@run
        val rnd = java.util.Random(7)
        var t = now.minus(Duration.ofDays(7))
        while (t < now.minus(Duration.ofMinutes(5))) {
            val h = t.atZone(java.time.ZoneOffset.UTC).hour
            val daily = 0.35 + 0.65 * kotlin.math.max(0.0, kotlin.math.sin((h - 6) / 24.0 * 2 * Math.PI))
            val incident = Duration.between(t, now) in Duration.ofHours(30)..Duration.ofHours(31)
            val rx = ((120 + 380 * daily) * 1_000_000 * (0.85 + 0.3 * rnd.nextDouble())).toLong()
            val tx = (rx * (0.9 + 0.15 * rnd.nextDouble())).toLong()
            sql.exec(
                """INSERT INTO server_metrics (ts, cpu, mem_used, mem_total, disk_free, disk_total, rx_bps, tx_bps, rx_bytes, tx_bytes,
                     utilization, ping_ms, loss_pct, peers, online, errors, drops) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                t, 0.08 + 0.35 * daily + 0.05 * rnd.nextDouble(), (1_100 + 500 * daily).toLong() * 1_000_000, 3_900_000_000L,
                21_000_000_000L, 40_000_000_000L, rx, tx, rx / 8 * 300, tx / 8 * 300, rx.coerceAtLeast(tx) / 1e9,
                if (incident) 180.0 + 60 * rnd.nextDouble() else 4.0 + 3 * rnd.nextDouble(), if (incident) 12.0 else 0.0,
                (40 + 25 * daily).toInt(), (8 + 30 * daily).toInt(), if (incident) 420L else rnd.nextInt(3).toLong(), if (incident) 160L else 0L,
            )
            t = t.plus(Duration.ofMinutes(5))
        }
        sql.exec(
            "INSERT INTO monitor_alerts (key, level, message, args, opened_at, resolved_at) VALUES ('ping','critical','Packet loss {loss}% to {host}',?,?,?)",
            Jsonb("""{"loss":"12","host":"1.1.1.1"}"""), now.minus(Duration.ofHours(31)), now.minus(Duration.ofHours(30)),
        )
    }

    private suspend fun seedAll() = ctx.db.run { sql ->
        if (sql.one("SELECT count(*)::int AS n FROM users")!!.int("n") > 0) return@run
        sql.exec(
            """INSERT INTO servers (id, name, city, country_code, subnet, protocols, max_peers, monthly_traffic_limit_bytes, paid_through)
               VALUES ('sim-1','Sim node 1','Frankfurt','DE','10.8.0.0/24',?,50,1000000000000,?)""",
            Jsonb(
                buildJsonObject {
                    put("amneziawg", buildJsonObject {
                        put("endpoint", "vpn1.sim.jaganet.dev:51821")
                        put("publicKey", "c2ltdWxhdGVkLXNlcnZlci1wdWJsaWMta2V5LTAwMDE=")
                        put("obfuscation", JsonObject(AwgParams.generate().mapValues { JsonPrimitive(it.value) }))
                    })
                    put("wireguard", buildJsonObject {
                        put("endpoint", "vpn1.sim.jaganet.dev:51820")
                        put("publicKey", "c2ltdWxhdGVkLXNlcnZlci1wdWJsaWMta2V5LTAwMDA=")
                    })
                    put("jaga-custom", buildJsonObject { put("endpoint", "vpn1.sim.jaganet.dev:443") })
                }.toString(),
            ),
            LocalDate.ofInstant(now.plus(Duration.ofDays(40)), ZoneOffset.UTC),
        )
        // A second location, run by a node agent, with BitTorrent counting on.
        sql.exec(
            """INSERT INTO servers (id, name, city, country_code, subnet, protocols, max_peers, agent_token_hash, endpoint_host, registered_at, last_report_at, report)
               VALUES ('sim-2','Amsterdam','Amsterdam','NL','10.8.0.0/24',?,250,'sim-agent','nl1.sim.jaganet.dev',?,?,?)""",
            Jsonb("""{"amneziawg":{"endpoint":"nl1.sim.jaganet.dev:51985","publicIp":"203.0.113.40","port":51985,"publicKey":"c2ltdWxhdGVkLW5vZGUtMi1wdWJsaWMta2V5LTAwMDE="}}"""),
            ago(Duration.ofDays(20)), ago(Duration.ofSeconds(40)),
            Jsonb("""{"at":${now.toEpochMilli()},"system":{"cpu":0.18,"memUsed":700000000,"memTotal":2000000000,"classes":"ok"},"rxBps":84000000,"txBps":91000000}"""),
        )
        // A VLESS+Reality node (Xray): a cascade whose entry the apps connect to, Vision first, then XHTTP.
        // Only the Reality public key is here; apps from before VLESS don't get it in their list.
        sql.exec(
            """INSERT INTO servers (id, name, city, country_code, subnet, protocols, max_peers, agent_token_hash, registered_at, last_report_at, report)
               VALUES ('sim-3','Финляндия','Helsinki','FI','10.9.0.0/16',?,1000,'sim-agent-vless',?,?,?)""",
            Jsonb(
                """{"vless":{"host":"fi1.sim.jaganet.dev","publicKey":"c2ltdWxhdGVkLXJlYWxpdHktcHVibGljLWtleS0wMQ0","shortIds":["a1b2c3d4"],
                   "sni":"fi1.sim.jaganet.dev","fingerprint":"firefox","transports":[{"type":"tcp","port":443,"tag":"vision","flow":"xtls-rprx-vision"},
                   {"type":"xhttp","port":8443,"tag":"xhttp","flow":"","path":"/sim-xhttp","mode":"auto"}],"endpoint":"fi1.sim.jaganet.dev:443","publicIp":"203.0.113.50"}}""",
            ),
            ago(Duration.ofDays(2)), ago(Duration.ofSeconds(30)),
            Jsonb("""{"at":${now.toEpochMilli()},"system":{"cpu":0.07,"memUsed":400000000,"memTotal":2000000000,"classes":"off"},"rxBps":12000000,"txBps":13000000}"""),
        )
        // A second VLESS location (issue #41): every user's VLESS key works on both, the subscription lists them in this order.
        sql.exec(
            """INSERT INTO servers (id, name, city, country_code, subnet, protocols, max_peers, agent_token_hash, registered_at, last_report_at, report, sort_order)
               VALUES ('sim-4','Нидерланды VLESS','Amsterdam','NL','10.9.0.0/16',?,1000,'sim-agent-vless-2',?,?,?,1)""",
            Jsonb(
                """{"vless":{"host":"nl2.sim.jaganet.dev","publicKey":"c2ltdWxhdGVkLXJlYWxpdHktcHVibGljLWtleS0wMg0","shortIds":["b1c2d3e4"],
                   "sni":"nl2.sim.jaganet.dev","fingerprint":"chrome","transports":[{"type":"tcp","port":443,"tag":"vision","flow":"xtls-rprx-vision"},
                   {"type":"xhttp","port":8443,"tag":"xhttp","flow":"","path":"/sim-xhttp-2","mode":"auto"}],"endpoint":"nl2.sim.jaganet.dev:443","publicIp":"203.0.113.51"}}""",
            ),
            ago(Duration.ofDays(1)), ago(Duration.ofSeconds(30)),
            Jsonb("""{"at":${now.toEpochMilli()},"system":{"cpu":0.05,"memUsed":300000000,"memTotal":2000000000,"classes":"off"},"rxBps":9000000,"txBps":9500000}"""),
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
            "INSERT INTO traffic_samples (device_id, bucket, rx_bytes, tx_bytes, server_id) VALUES (?::uuid,?,5900000000,1100000000,'sim-1')",
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
            // Traffic for the analytics: most on the Amsterdam node, a few heavy BitTorrent users.
            if (i < 18) {
                val server = if (i % 3 == 0) "sim-1" else "sim-2"
                val phone = device(sql, u, "Phone", "android", tunnel = true, server = server, lastSeen = ago(Duration.ofMinutes(if (i % 4 == 0) 1L else 90L + i)))
                history(sql, phone, 30, (2e6 + i * 4e5), sessionsPerDay = 0, server = server, torrent = if (i % 5 == 1) 0.6 else 0.0)
                if (i % 2 == 0) {
                    val pc = device(sql, u, "Laptop", "desktop", tunnel = true, server = "sim-2", lastSeen = ago(Duration.ofHours(3)))
                    history(sql, pc, 30, 9e6 + i * 1e6, sessionsPerDay = 0, server = "sim-2", torrent = if (i % 4 == 2) 0.75 else 0.05)
                }
            }
        }
    }

    private fun user(sql: Sql, email: String, age: Duration = Duration.ofDays(60), referredBy: String? = null): String = sql.one(
        "INSERT INTO users (email, role, referral_code, referred_by, created_at) VALUES (?,'user',?,?::uuid,?) RETURNING id",
        email, Crypto.referralCode(email), referredBy, ago(age),
    )!!.str("id")

    private fun sub(sql: Sql, userId: String, product: String, start: Instant, days: Long, renewal: Boolean = false, status: String = "active", cancelled: Instant? = null) =
        sql.exec(
            """INSERT INTO subscriptions (user_id, product_id, source, external_id, started_at, expires_at, is_renewal, status, cancelled_at, tariff_name)
               VALUES (?::uuid,?,'dev',?,?,?,?,?,?,?)""",
            userId, product, "seed-$userId-$product-${start.toEpochMilli()}", start, start.plus(Duration.ofDays(days)), renewal, status, cancelled,
            if (product == "pro_yearly") "Pro на год" else "Pro на месяц",
        )

    private suspend fun device(sql: Sql, userId: String, name: String, platform: String, tunnel: Boolean = false, connected: Boolean = false, lastSeen: Instant = now, server: String = "sim-1"): String {
        val id = sql.one(
            "INSERT INTO devices (user_id, name, platform, created_at) VALUES (?::uuid,?,?,?) RETURNING id",
            userId, name, platform, ago(Duration.ofDays(50)),
        )!!.str("id")
        if (tunnel && server != "sim-1") {
            val pub = Base64.getEncoder().encodeToString("seed-$id".padEnd(32, '.').take(32).toByteArray())
            sql.exec(
                "INSERT INTO tunnels (device_id, server_id, protocol, address, peer_key, client_params, last_seen_at) VALUES (?::uuid,?,'amneziawg',?,?,?,?)",
                id, server, "10.8.0.${ip++}", pub, Jsonb("""{"publicKey":"$pub"}"""), lastSeen,
            )
        } else if (tunnel) {
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
    private fun history(sql: Sql, deviceId: String, days: Int, scale: Double, sessionsPerDay: Int, server: String = "sim-1", torrent: Double = 0.0) {
        for (h in days * 24 downTo 1) {
            val t = ago(hour.multipliedBy(h.toLong())).truncatedTo(ChronoUnit.HOURS)
            val shape = when (t.atZone(ZoneOffset.UTC).hour) { in 0..5 -> 0.05; in 6..8 -> 0.4; in 9..16 -> 0.7; else -> 1.3 }
            val rx = (scale * shape * (0.5 + Random.nextDouble())).toLong()
            if (rx < 1000) continue
            val tx = (rx * 0.19).toLong()
            val share = if (torrent > 0) (torrent * (0.6 + 0.8 * Random.nextDouble())).coerceAtMost(0.95) else 0.0
            sql.exec(
                "INSERT INTO traffic_samples (device_id, bucket, rx_bytes, tx_bytes, torrent_rx_bytes, torrent_tx_bytes, server_id) VALUES (?::uuid,?,?,?,?,?,?)",
                deviceId, t, rx, tx, (rx * share).toLong(), (tx * share * 2).toLong().coerceAtMost(tx), server,
            )
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
