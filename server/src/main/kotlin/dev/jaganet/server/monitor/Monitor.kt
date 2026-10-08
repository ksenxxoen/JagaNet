package dev.jaganet.server.monitor

import dev.jaganet.api.CheckLevel
import dev.jaganet.api.Format
import dev.jaganet.api.HealthCheck
import dev.jaganet.api.MetricPoint
import dev.jaganet.api.MonitorAlert
import dev.jaganet.api.MonitorRes
import dev.jaganet.api.NetworkInfo
import dev.jaganet.api.NotifySettings
import dev.jaganet.api.i18n.I18n
import dev.jaganet.api.i18n.Lang
import dev.jaganet.server.Ctx
import dev.jaganet.server.db.Jsonb
import dev.jaganet.server.db.Row
import dev.jaganet.server.services.node
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/** Where alerts go: e-mail, Telegram. */
fun interface AlertSink {
    suspend fun send(subject: String, text: String)
}

/** How a check behaves: how many bad minutes open an alert, how many good ones close it. */
private class Rule(val openAfter: Int, val closeAfter: Int = 2)

private val RULES = mapOf(
    "vpn" to Rule(2), "db" to Rule(2), "https" to Rule(3), "cert" to Rule(1), "bot" to Rule(5),
    "cpu" to Rule(10), "memory" to Rule(5), "disk" to Rule(1),
    "channel" to Rule(5), "net_errors" to Rule(5), "ping" to Rule(3), "dns" to Rule(3), "traffic" to Rule(1),
)

/** Names used in alert subjects; the same keys the status page translates. */
private val NAMES = mapOf(
    "vpn" to "VPN", "db" to "Database", "https" to "Website (HTTPS)", "cert" to "Certificate", "bot" to "Telegram bot",
    "cpu" to "Processor", "memory" to "Memory", "disk" to "Disk", "channel" to "Channel load", "net_errors" to "Network errors",
    "ping" to "Ping and packet loss", "dns" to "DNS", "traffic" to "Monthly traffic",
)

/**
 * Server monitoring for the owner. Every minute: resources, the internet channel (speed,
 * load against its capacity, interface errors and drops, ping and packet loss, DNS, monthly
 * traffic against the hosting allowance), the VPN interface, the database, the website over
 * HTTPS, the certificate and the Telegram bot. Measurements are kept 30 days.
 *
 * A problem must last a few minutes before an alert opens (no alarm for one lost ping), and
 * a few good minutes close it. Opening, getting worse and closing are sent to the alert e-mails
 * and Telegram chats; a critical problem that stays open is repeated every 6 hours.
 */
class Monitor(
    private val ctx: Ctx,
    private val probe: SystemProbe,
    private val sinks: () -> List<AlertSink>,
    /** Last successful Telegram poll; null when the bot is off. */
    private val botAlive: () -> Instant? = { null },
    private val botEnabled: Boolean = false,
    private val lang: Lang = Lang.DEFAULT,
) {
    private val log = LoggerFactory.getLogger("monitor")
    private val bad = ConcurrentHashMap<String, Int>()
    private val good = ConcurrentHashMap<String, Int>()
    private var prev: Pair<Instant, IfaceCounters>? = null
    private var lastCertCheck: Instant? = null
    private var certDaysCached: Long? = null
    @Volatile var latest: List<HealthCheck> = emptyList()
        private set
    @Volatile var checkedAt: Instant? = null
        private set

    private val cfg get() = ctx.cfg.monitor
    private fun t(en: String, vararg args: Pair<String, Any?>) = I18n.tr(lang, en, *args)

    /** One round: measure, store, judge, alert. */
    suspend fun tick() {
        val now = ctx.now()
        val (memUsed, memTotal) = probe.memory()
        val (diskFree, diskTotal) = probe.disk()
        val cpu = probe.cpu()

        // ---- internet channel
        val iface = cfg.wanInterface ?: probe.wanInterface()
        val counters = iface?.let { probe.counters(it) }
        val capacityMbps = cfg.channelMbps ?: iface?.let { probe.linkSpeedMbps(it) }
        var rxBps = 0L; var txBps = 0L; var rxBytes = 0L; var txBytes = 0L; var errors = 0L; var drops = 0L
        val p = prev
        if (counters != null && p != null && p.first < now) {
            val secs = Duration.between(p.first, now).toMillis() / 1000.0
            // Counters reset when the machine reboots: a negative delta means "started from zero".
            fun d(a: Long, b: Long) = if (a >= b) a - b else a
            rxBytes = d(counters.rxBytes, p.second.rxBytes); txBytes = d(counters.txBytes, p.second.txBytes)
            errors = d(counters.errors, p.second.errors); drops = d(counters.drops, p.second.drops)
            rxBps = (rxBytes * 8 / secs).toLong(); txBps = (txBytes * 8 / secs).toLong()
        }
        if (counters != null) prev = now to counters
        val utilization = capacityMbps?.let { maxOf(rxBps, txBps).toDouble() / (it * 1_000_000) }

        val pings = cfg.pingTargets.map { probe.ping(it) }
        val answered = pings.filter { it.avgMs != null }
        val pingMs = answered.takeIf { it.isNotEmpty() }?.map { it.avgMs!! }?.average()
        val lossPct = pings.takeIf { it.isNotEmpty() }?.map { it.lossPct }?.average()

        // ---- VPN
        var vpnError: String? = null
        var peers = 0; var online = 0
        runCatching {
            val servers = ctx.db.run { it.query("SELECT * FROM servers WHERE active") }
            for (s in servers) for (proto in s.json("protocols").keys) {
                val driver = ctx.drivers[proto] ?: continue
                val list = driver.readCounters(s.node(proto))
                peers += list.size
                online += list.count { c -> c.lastSeenAt?.let { Duration.between(it, now) < Duration.ofMinutes(3) } == true }
            }
        }.onFailure { vpnError = it.message ?: it.javaClass.simpleName }

        // ---- database (also stores the minute)
        val dbOk = runCatching {
            ctx.db.run { sql ->
                // The first minute after a start has no traffic delta yet: check the database, store nothing.
                if (p == null) { sql.one("SELECT 1 AS one"); return@run }
                sql.exec(
                    """INSERT INTO server_metrics (ts, cpu, mem_used, mem_total, disk_free, disk_total, rx_bps, tx_bps, rx_bytes, tx_bytes,
                         utilization, ping_ms, loss_pct, peers, online, errors, drops) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                       ON CONFLICT (ts) DO NOTHING""",
                    now, cpu, memUsed, memTotal, diskFree, diskTotal, rxBps, txBps, rxBytes, txBytes,
                    utilization, pingMs, lossPct, peers, online, errors, drops,
                )
                if (now.atZone(ZoneOffset.UTC).minute == 0) sql.exec("DELETE FROM server_metrics WHERE ts < ?", now.minus(Duration.ofDays(30)))
            }
        }.isSuccess

        val monthBytes = runCatching {
            ctx.db.run { it.one("SELECT coalesce(sum(rx_bytes + tx_bytes), 0)::bigint AS n FROM server_metrics WHERE ts >= ?", monthStart(now))!!.long("n") }
        }.getOrDefault(0L)

        val httpOk = probe.httpOk(ctx.cfg.publicUrl.trimEnd('/') + "/health")
        val cert = certDays(now)
        val dnsMs = probe.dns("cloudflare.com")
        val botOk = botAlive()?.let { Duration.between(it, now) < Duration.ofMinutes(3) } == true

        // ---- checks, worded in any language (the status page asks in its reader's language)
        fun build(L: Lang): List<HealthCheck> = buildList {
            fun bytes(b: Long) = Format.bytes(b, L)
            fun mbps(bps: Long) = Format.mbps(bps, L) + " " + I18n.tr(L, "Mbps")
            add(
                if (vpnError == null) HealthCheck("vpn", CheckLevel.OK, "Running, {peers} devices, {online} online now", mapOf("peers" to "$peers", "online" to "$online"))
                else HealthCheck("vpn", CheckLevel.CRITICAL, "The VPN interface doesn't answer", mapOf("error" to vpnError!!.take(120))),
            )
            add(if (dbOk) HealthCheck("db", CheckLevel.OK, "Working") else HealthCheck("db", CheckLevel.CRITICAL, "The database doesn't answer"))
            add(if (httpOk) HealthCheck("https", CheckLevel.OK, "Opens") else HealthCheck("https", CheckLevel.CRITICAL, "The website doesn't open"))
            if (cert != null) add(
                if (cert == Long.MIN_VALUE) HealthCheck("cert", CheckLevel.WARNING, "Can't read the certificate")
                else HealthCheck("cert", when { cert < 3 -> CheckLevel.CRITICAL; cert < 14 -> CheckLevel.WARNING; else -> CheckLevel.OK }, "Valid for {n} more days", mapOf("n" to "$cert")),
            )
            if (botEnabled) add(if (botOk) HealthCheck("bot", CheckLevel.OK, "Working") else HealthCheck("bot", CheckLevel.WARNING, "The bot can't reach Telegram"))
            add(level(cpu, 0.9, 1.01).let { HealthCheck("cpu", it, "Load {p}%", mapOf("p" to pct(cpu))) })
            val memShare = memUsed.toDouble() / memTotal.coerceAtLeast(1)
            add(HealthCheck("memory", level(memShare, 0.9, 0.97), "{used} of {total} used", mapOf("used" to bytes(memUsed), "total" to bytes(memTotal))))
            val diskShare = diskFree.toDouble() / diskTotal.coerceAtLeast(1)
            add(
                HealthCheck(
                    "disk",
                    when { diskShare < 0.05 || diskFree < 1_000_000_000 -> CheckLevel.CRITICAL; diskShare < 0.10 -> CheckLevel.WARNING; else -> CheckLevel.OK },
                    "{free} free of {total}", mapOf("free" to bytes(diskFree), "total" to bytes(diskTotal)),
                ),
            )
            add(
                when {
                    counters == null -> HealthCheck("channel", CheckLevel.UNKNOWN, "Can't read the network interface")
                    utilization == null -> HealthCheck("channel", CheckLevel.OK, "Down {rx}, up {tx}, capacity unknown", mapOf("rx" to mbps(rxBps), "tx" to mbps(txBps)))
                    else -> HealthCheck(
                        "channel", level(utilization, 0.8, 0.95), "Load {p}% of {cap}, down {rx}, up {tx}",
                        mapOf("p" to pct(utilization), "cap" to mbps(capacityMbps!! * 1_000_000), "rx" to mbps(rxBps), "tx" to mbps(txBps)),
                    )
                },
            )
            if (counters != null) add(
                if (errors + drops > 100) HealthCheck("net_errors", CheckLevel.WARNING, "{errors} errors and {drops} dropped packets in a minute", mapOf("errors" to "$errors", "drops" to "$drops"))
                else HealthCheck("net_errors", CheckLevel.OK, "No errors"),
            )
            if (pings.isNotEmpty()) {
                val worst = pings.maxBy { it.lossPct }
                add(
                    when {
                        answered.isEmpty() -> HealthCheck("ping", CheckLevel.CRITICAL, "No answer from {hosts}", mapOf("hosts" to pings.joinToString(", ") { it.host }))
                        worst.lossPct >= 20 -> HealthCheck("ping", CheckLevel.CRITICAL, "Packet loss {loss}% to {host}", mapOf("loss" to num(worst.lossPct), "host" to worst.host))
                        worst.lossPct >= 5 -> HealthCheck("ping", CheckLevel.WARNING, "Packet loss {loss}% to {host}", mapOf("loss" to num(worst.lossPct), "host" to worst.host))
                        pingMs!! > 200 -> HealthCheck("ping", CheckLevel.WARNING, "Slow responses, {ms} ms", mapOf("ms" to num(pingMs)))
                        else -> HealthCheck("ping", CheckLevel.OK, "{ms} ms, no loss", mapOf("ms" to num(pingMs)))
                    },
                )
            }
            add(
                when {
                    dnsMs == null -> HealthCheck("dns", CheckLevel.CRITICAL, "Names don't resolve")
                    dnsMs > 1000 -> HealthCheck("dns", CheckLevel.WARNING, "Slow, {ms} ms", mapOf("ms" to "$dnsMs"))
                    else -> HealthCheck("dns", CheckLevel.OK, "{ms} ms", mapOf("ms" to "$dnsMs"))
                },
            )
            cfg.monthlyTrafficLimitBytes?.let { limit ->
                val share = monthBytes.toDouble() / limit
                add(HealthCheck("traffic", level(share, 0.8, 0.95), "{used} of {limit} this month", mapOf("used" to bytes(monthBytes), "limit" to bytes(limit))))
            }
        }
        lastBuild = ::build
        latest = build(lang)
        checkedAt = now
        judge(latest, now)
    }

    /** The latest round's checks in another language. */
    @Volatile private var lastBuild: ((Lang) -> List<HealthCheck>)? = null
    fun checks(L: Lang): List<HealthCheck> = lastBuild?.invoke(L) ?: emptyList()

    /** Days the HTTPS certificate is still valid (checked hourly); MIN_VALUE if unreadable; null if not https. */
    private suspend fun certDays(now: Instant): Long? {
        val uri = URI(ctx.cfg.publicUrl)
        if (uri.scheme != "https") return null
        if (lastCertCheck?.let { Duration.between(it, now) < Duration.ofHours(1) } == true) return certDaysCached
        lastCertCheck = now
        certDaysCached = probe.certExpiry(uri.host)?.let { Duration.between(now, it).toDays() } ?: Long.MIN_VALUE
        return certDaysCached
    }

    /* ---------------- alerts ---------------- */

    private suspend fun judge(checks: List<HealthCheck>, now: Instant) {
        val open = runCatching { openAlerts() }.getOrElse { memoryAlerts.values.toList() }.associateBy { it.key }
        for (c in checks) {
            val rule = RULES[c.key] ?: continue
            val isBad = c.level == CheckLevel.WARNING || c.level == CheckLevel.CRITICAL
            if (isBad) { bad.merge(c.key, 1, Int::plus); good[c.key] = 0 } else { good.merge(c.key, 1, Int::plus); bad[c.key] = 0 }
            val current = open[c.key]
            when {
                isBad && current == null && (bad[c.key] ?: 0) >= rule.openAfter -> openAlert(c, now)
                isBad && current != null && current.level == CheckLevel.WARNING && c.level == CheckLevel.CRITICAL -> openAlert(c, now, worse = current)
                isBad && current != null && current.level == CheckLevel.CRITICAL && remindDue(current, now) -> remind(current, c, now)
                !isBad && current != null && (good[c.key] ?: 0) >= rule.closeAfter -> resolve(current, c, now)
            }
        }
    }

    /** Fallback when the database itself is down. */
    private val memoryAlerts = ConcurrentHashMap<String, MonitorAlert>()
    private val lastNotified = ConcurrentHashMap<String, Instant>()

    private suspend fun openAlerts(): List<MonitorAlert> = ctx.db.run { sql ->
        sql.query("SELECT * FROM monitor_alerts WHERE resolved_at IS NULL").map { it.toAlert() }
    }.also { list -> memoryAlerts.clear(); list.forEach { memoryAlerts[it.key] = it } }

    private suspend fun openAlert(c: HealthCheck, now: Instant, worse: MonitorAlert? = null) {
        val alert = runCatching {
            ctx.db.run { sql ->
                if (worse != null) sql.one(
                    "UPDATE monitor_alerts SET level=?, message=?, args=?, last_notified_at=? WHERE id=?::uuid RETURNING *",
                    c.level.name.lowercase(), c.message, args(c.args), now, worse.id,
                )!!.toAlert()
                else sql.one(
                    "INSERT INTO monitor_alerts (key, level, message, args, opened_at, last_notified_at) VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING RETURNING *",
                    c.key, c.level.name.lowercase(), c.message, args(c.args), now, now,
                )?.toAlert()
            }
        }.getOrElse { MonitorAlert("mem-${c.key}", c.key, c.level, c.message, c.args, now.toString()) } ?: return
        memoryAlerts[c.key] = alert
        lastNotified[c.key] = now
        val head = if (c.level == CheckLevel.CRITICAL) t("Problem") else t("Warning")
        notify("$head: ${t(NAMES.getValue(c.key))}", body(c, now))
    }

    private fun remindDue(a: MonitorAlert, now: Instant) =
        (lastNotified[a.key] ?: Instant.parse(a.openedAt)).let { Duration.between(it, now) >= Duration.ofHours(6) }

    private suspend fun remind(a: MonitorAlert, c: HealthCheck, now: Instant) {
        lastNotified[a.key] = now
        runCatching { ctx.db.run { it.exec("UPDATE monitor_alerts SET last_notified_at=? WHERE id=?::uuid", now, a.id) } }
        notify("${t("Still a problem")}: ${t(NAMES.getValue(c.key))}", body(c, now) + "\n" + t("Since {time}", "time" to stamp(Instant.parse(a.openedAt))))
    }

    private suspend fun resolve(a: MonitorAlert, c: HealthCheck, now: Instant) {
        runCatching { ctx.db.run { it.exec("UPDATE monitor_alerts SET resolved_at=? WHERE id=?::uuid", now, a.id) } }
        memoryAlerts.remove(a.key)
        lastNotified.remove(a.key)
        val lasted = Duration.between(Instant.parse(a.openedAt), now).toMinutes()
        notify("${t("Fixed")}: ${t(NAMES.getValue(c.key))}", body(c, now) + "\n" + I18n.plural(lang, lasted, "The problem lasted {n} minute.|The problem lasted {n} minutes."))
    }

    private fun body(c: HealthCheck, now: Instant): String = buildString {
        appendLine("${t(NAMES.getValue(c.key))}. ${I18n.tr(lang, c.message, *c.args.map { (k, v) -> k to v }.toTypedArray())}")
        appendLine(t("Server {host}, {time} UTC", "host" to (runCatching { URI(ctx.cfg.publicUrl).host }.getOrNull() ?: "?"), "time" to stamp(now)))
        append(t("Server status") + " " + ctx.cfg.publicUrl.trimEnd('/') + "/#/status")
    }

    private suspend fun notify(subject: String, text: String) {
        log.warn("alert: $subject | ${text.lines().first()}")
        for (s in sinks()) runCatching { s.send("[JagaNet] $subject", text) }.onFailure { log.error("alert delivery failed", it) }
    }

    suspend fun testAlert() = notify(t("Test notification"), t("Alerts from your server reach you here.") + "\n" + ctx.cfg.publicUrl.trimEnd('/') + "/#/status")

    /* ---------------- owner API ---------------- */

    suspend fun overview(range: String, readerLang: Lang = lang): MonitorRes {
        val span = when (range) { "1h" -> Duration.ofHours(1); "7d" -> Duration.ofDays(7); "30d" -> Duration.ofDays(30); else -> Duration.ofHours(24) }
        // At most ~300 points: average over buckets of this many seconds.
        val bucket = maxOf(60L, span.seconds / 300)
        val now = ctx.now()
        return ctx.db.run { sql ->
            val series = sql.query(
                """SELECT to_timestamp(floor(extract(epoch FROM ts) / ?) * ?) AS t,
                          avg(cpu) AS cpu, avg(mem_used)::bigint AS mem_used, max(mem_total) AS mem_total,
                          min(disk_free) AS disk_free, max(disk_total) AS disk_total,
                          avg(rx_bps)::bigint AS rx_bps, avg(tx_bps)::bigint AS tx_bps, max(utilization) AS utilization,
                          avg(ping_ms) AS ping_ms, max(loss_pct) AS loss_pct, max(peers) AS peers, max(online) AS online,
                          sum(errors)::bigint AS errors, sum(drops)::bigint AS drops
                     FROM server_metrics WHERE ts >= ? GROUP BY 1 ORDER BY 1""",
                bucket, bucket, now.minus(span),
            ).map { r ->
                MetricPoint(
                    r.instant("t").toString(), r.dbl("cpu") ?: 0.0, r.long("mem_used"), r.long("mem_total"), r.long("disk_free"), r.long("disk_total"),
                    r.long("rx_bps"), r.long("tx_bps"), r.dbl("utilization"), r.dbl("ping_ms"), r.dbl("loss_pct"),
                    r.int("peers"), r.int("online"), r.long("errors"), r.long("drops"),
                )
            }
            val month = sql.one(
                "SELECT coalesce(sum(rx_bytes), 0)::bigint AS rx, coalesce(sum(tx_bytes), 0)::bigint AS tx FROM server_metrics WHERE ts >= ?",
                monthStart(now),
            )!!
            val alerts = sql.query(
                "SELECT * FROM monitor_alerts ORDER BY (resolved_at IS NULL) DESC, opened_at DESC LIMIT 100",
            ).map { it.toAlert() }
            val iface = cfg.wanInterface ?: probe.wanInterface()
            val cap = cfg.channelMbps ?: iface?.let { probe.linkSpeedMbps(it) }
            MonitorRes(
                checkedAt = checkedAt?.toString(),
                overall = overall(latest),
                checks = checks(readerLang),
                network = NetworkInfo(iface, cap, if (cfg.channelMbps != null) "config" else if (cap != null) "link" else null, month.long("rx"), month.long("tx"), cfg.monthlyTrafficLimitBytes),
                series = series,
                alerts = alerts,
                notify = NotifySettings(ctx.live.alertEmails, ctx.live.smtp != null, ctx.live.alertTelegramChats.size),
            )
        }
    }

    /* ---------------- helpers ---------------- */

    private fun Row.dbl(k: String) = (this[k] as Number?)?.toDouble()

    private fun Row.toAlert() = MonitorAlert(
        id = str("id"), key = str("key"), level = if (str("level") == "critical") CheckLevel.CRITICAL else CheckLevel.WARNING,
        message = str("message"), args = json("args").mapValues { it.value.jsonPrimitive.content },
        openedAt = instant("opened_at").toString(), resolvedAt = instantOrNull("resolved_at")?.toString(),
    )

    private fun args(m: Map<String, String>) = Jsonb(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.JsonObject(m.mapValues { JsonPrimitive(it.value) })))

    private fun level(v: Double, warn: Double, crit: Double) = when { v >= crit -> CheckLevel.CRITICAL; v >= warn -> CheckLevel.WARNING; else -> CheckLevel.OK }
    private fun pct(v: Double) = num(v * 100)
    private fun num(v: Double) = if (v >= 10) "${Math.round(v)}" else "${Math.round(v * 10) / 10.0}".removeSuffix(".0")
    private fun stamp(t: Instant) = t.atZone(ZoneOffset.UTC).let { "%02d.%02d %02d:%02d".format(it.dayOfMonth, it.monthValue, it.hour, it.minute) }
    private fun monthStart(t: Instant) = t.atZone(ZoneOffset.UTC).withDayOfMonth(1).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC)

    companion object {
        fun overall(checks: List<HealthCheck>) = when {
            checks.isEmpty() -> CheckLevel.UNKNOWN
            checks.any { it.level == CheckLevel.CRITICAL } -> CheckLevel.CRITICAL
            checks.any { it.level == CheckLevel.WARNING } -> CheckLevel.WARNING
            else -> CheckLevel.OK
        }
    }
}
