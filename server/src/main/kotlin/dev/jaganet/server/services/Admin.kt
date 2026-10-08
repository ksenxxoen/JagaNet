package dev.jaganet.server.services

import com.sun.management.OperatingSystemMXBean
import dev.jaganet.api.AdminOverviewRes
import dev.jaganet.api.ProductId
import dev.jaganet.api.i18n.Lang
import dev.jaganet.api.DayCount
import dev.jaganet.api.Revenue
import dev.jaganet.api.ServerHealth
import dev.jaganet.server.Ctx
import java.lang.management.ManagementFactory
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

private const val PAID = "source IN ('apple','google','dev')"
private const val ACTIVE = "status='active' AND started_at <= ? AND expires_at > ?"

/** Owner dashboard. Business numbers from the DB; host numbers from this machine for now. */
class Admin(private val ctx: Ctx) {
    /** Revenue in the owner's language currency (rubles for Russian, euros otherwise). */
    suspend fun overview(lang: Lang = Lang.DEFAULT): AdminOverviewRes = ctx.db.run { sql ->
        val now = ctx.now()
        fun count(where: String, vararg p: Any?) = sql.one("SELECT count(DISTINCT user_id)::int AS n FROM subscriptions WHERE $where", *p)!!.int("n")
        val paying = count("$ACTIVE AND $PAID", now, now)
        val yearly = count("$ACTIVE AND $PAID AND product_id='pro_yearly'", now, now)
        val monthly = count("$ACTIVE AND $PAID AND product_id='pro_monthly'", now, now)
        val total = sql.one("SELECT count(*)::int AS n FROM users")!!.int("n")
        val fromRef = sql.one(
            """SELECT count(DISTINCT s.user_id)::int AS n FROM subscriptions s JOIN users u ON u.id=s.user_id
                WHERE s.status='active' AND s.started_at <= ? AND s.expires_at > ? AND s.$PAID AND u.referred_by IS NOT NULL""",
            now, now,
        )!!.int("n")
        val newSubs = sql.one("SELECT count(*)::int AS n FROM subscriptions WHERE $PAID AND NOT is_renewal AND started_at >= ?", now.minus(Duration.ofDays(7)))!!.int("n")
        val cancelled = sql.one("SELECT count(*)::int AS n FROM subscriptions WHERE cancelled_at >= ?", monthStart(now))!!.int("n")

        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        val daily = sql.query(
            """SELECT (started_at AT TIME ZONE 'UTC')::date AS day, count(*)::int AS n
                 FROM subscriptions WHERE $PAID AND NOT is_renewal AND started_at >= ? GROUP BY 1""",
            today.minusDays(6).atStartOfDay().toInstant(ZoneOffset.UTC),
        ).associate { (it["day"] as LocalDate) to it.int("n") }
        val last7 = (6 downTo 0).map { today.minusDays(it.toLong()) }.map { DayCount(it.toString(), daily[it] ?: 0) }

        val p = ctx.cfg.plans
        val cur = p.currencyFor(lang)
        val mrr = monthly * (p.price(ProductId.PRO_MONTHLY, cur) ?: 0) + yearly * (p.price(ProductId.PRO_YEARLY, cur) ?: 0) / 12

        // Single-node view for now: the first active server.
        val server = sql.one(
            """SELECT s.*,
                 (SELECT count(*)::int FROM tunnels t WHERE t.server_id=s.id AND t.last_seen_at > ?) AS connected,
                 (SELECT coalesce(sum(ts.rx_bytes+ts.tx_bytes), 0)::bigint FROM traffic_samples ts JOIN tunnels t ON t.device_id=ts.device_id
                   WHERE t.server_id=s.id AND ts.bucket >= ?) AS traffic
               FROM servers s WHERE s.active ORDER BY s.id LIMIT 1""",
            now.minus(Duration.ofMinutes(3)), monthStart(now),
        )
        val os = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
        val memTotal = os.totalMemorySize
        val memUsed = memTotal - os.freeMemorySize
        val cpu = os.cpuLoad.takeIf { it >= 0 } ?: 0.0
        val traffic = server?.long("traffic") ?: 0
        val limit = server?.longOrNull("monthly_traffic_limit_bytes")
        val warnings = buildList {
            if (memUsed.toDouble() / memTotal > 0.75) add("Memory is near its limit. Pause new sign-ups or add a second server before you grow further.")
            if (server != null && server.int("connected").toDouble() / server.int("max_peers") > 0.8) add("Over 80% of peer slots are in use.")
            if (limit != null && traffic.toDouble() / limit > 0.8) add("Over 80% of this month’s traffic allowance is used.")
        }

        AdminOverviewRes(
            revenue = Revenue(
                mrrMinor = mrr, currency = cur, paying = paying, free = total - paying,
                conversion = if (total > 0) paying.toDouble() / total else 0.0,
                newSubsThisWeek = newSubs, cancelledThisMonth = cancelled, yearly = yearly, monthly = monthly,
                fromReferrals = fromRef, newPayingLast7Days = last7,
            ),
            server = ServerHealth(
                id = server?.str("id") ?: "-",
                name = server?.str("name") ?: "No server",
                connectedNow = server?.int("connected") ?: 0,
                maxPeers = server?.int("max_peers") ?: 0,
                cpu = cpu,
                memUsedBytes = memUsed,
                memTotalBytes = memTotal,
                trafficThisMonthBytes = traffic,
                trafficLimitBytes = limit,
                uptimeSeconds = ManagementFactory.getRuntimeMXBean().uptime / 1000,
                paidThrough = (server?.get("paid_through") as LocalDate?)?.toString(),
                warnings = warnings,
            ),
        )
    }
}
