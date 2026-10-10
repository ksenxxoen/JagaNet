package dev.jaganet.server.services

import dev.jaganet.api.CountBy
import dev.jaganet.api.FinanceDay
import dev.jaganet.api.FinanceRes
import dev.jaganet.api.MoneyAmount
import dev.jaganet.api.PaymentRow
import dev.jaganet.api.ReferralPeriod
import dev.jaganet.server.Ctx
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The owner's view of money: payments received (website and Telegram orders, per currency),
 * subscriptions bought and renewed on every channel and tariff, active subscribers and recurring revenue.
 */
class Finance(private val ctx: Ctx) {
    private fun since(p: ReferralPeriod): Instant = when (p) {
        ReferralPeriod.D7 -> ctx.now().minus(Duration.ofDays(7))
        ReferralPeriod.D30 -> ctx.now().minus(Duration.ofDays(30))
        ReferralPeriod.D90 -> ctx.now().minus(Duration.ofDays(90))
        ReferralPeriod.ALL -> Instant.EPOCH
    }

    suspend fun get(period: ReferralPeriod): FinanceRes = ctx.db.run { sql ->
        val from = since(period)
        val now = ctx.now()
        val revenue = sql.query(
            "SELECT currency, sum(amount_minor)::bigint AS total, count(*)::int AS n FROM orders WHERE status='paid' AND paid_at >= ? GROUP BY 1 ORDER BY 1",
            from,
        )
        val subs = sql.one(
            """SELECT count(*) FILTER (WHERE NOT is_renewal)::int AS new, count(*) FILTER (WHERE is_renewal)::int AS renewals
                 FROM subscriptions WHERE source <> 'referral' AND created_at >= ?""",
            from,
        )!!
        val active = sql.one(
            "SELECT count(DISTINCT user_id)::int AS n FROM subscriptions WHERE source <> 'referral' AND status='active' AND started_at <= ? AND expires_at > ?",
            now, now,
        )!!.int("n")
        // Recurring revenue of subscriptions running now: the order's amount spread over its length, per month (30.44 days).
        val mrr = sql.query(
            """SELECT o.currency, sum(o.amount_minor * 2629800.0 / greatest(86400, extract(epoch FROM s.expires_at - s.started_at)))::bigint AS total
                 FROM subscriptions s JOIN orders o ON s.external_id = 'order:' || o.id::text
                WHERE s.status='active' AND s.started_at <= ? AND s.expires_at > ? GROUP BY 1 ORDER BY 1""",
            now, now,
        ).map { MoneyAmount(it.long("total"), it.str("currency")) }
        val byChannel = sql.query(
            "SELECT source AS k, count(*)::int AS n FROM subscriptions WHERE source <> 'referral' AND created_at >= ? GROUP BY 1 ORDER BY 2 DESC",
            from,
        ).map { CountBy(it.str("k"), it.int("n")) }
        val byProduct = sql.query(
            "SELECT coalesce(tariff_name, 'Pro') AS k, count(*)::int AS n FROM subscriptions WHERE source <> 'referral' AND created_at >= ? GROUP BY 1 ORDER BY 2 DESC",
            from,
        ).map { CountBy(it.str("k"), it.int("n")) }
        val unpaid = sql.one("SELECT count(*)::int AS n FROM orders WHERE status='pending' AND created_at >= ?", from)!!.int("n")

        // Daily series: from the first activity for "all time", at most a year.
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        val first = sql.one("SELECT min(created_at) AS t FROM subscriptions WHERE source <> 'referral'")?.instantOrNull("t")
        val startAt = if (from == Instant.EPOCH) maxOf(first ?: now, now.minus(Duration.ofDays(365))) else from.plus(Duration.ofDays(1))
        val start = LocalDate.ofInstant(startAt, ZoneOffset.UTC)
        val dayRevenue = sql.query(
            """SELECT (paid_at AT TIME ZONE 'UTC')::date AS d, currency, sum(amount_minor)::bigint AS total, count(*)::int AS n
                 FROM orders WHERE status='paid' AND paid_at >= ? GROUP BY 1, 2""",
            startAt,
        ).groupBy { it["d"] as LocalDate }
        val daySubs = sql.query(
            """SELECT (created_at AT TIME ZONE 'UTC')::date AS d, count(*) FILTER (WHERE NOT is_renewal)::int AS new,
                      count(*) FILTER (WHERE is_renewal)::int AS renewals
                 FROM subscriptions WHERE source <> 'referral' AND created_at >= ? GROUP BY 1""",
            startAt,
        ).associateBy { it["d"] as LocalDate }
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.map { d ->
            val r = dayRevenue[d].orEmpty()
            FinanceDay(
                d.toString(), r.map { MoneyAmount(it.long("total"), it.str("currency")) }.sortedBy { it.currency }, r.sumOf { it.int("n") },
                daySubs[d]?.int("new") ?: 0, daySubs[d]?.int("renewals") ?: 0,
            )
        }.toList()

        val recent = sql.query(
            """SELECT s.created_at, u.email, coalesce(s.tariff_name, 'Pro') AS tariff, s.source, s.is_renewal, o.amount_minor, o.currency
                 FROM subscriptions s JOIN users u ON u.id = s.user_id
                 LEFT JOIN orders o ON s.external_id = 'order:' || o.id::text
                WHERE s.source <> 'referral' AND s.created_at >= ? ORDER BY s.created_at DESC LIMIT 100""",
            from,
        ).map { r ->
            PaymentRow(
                at = r.instant("created_at").toString(),
                email = r.str("email").let { if (it.endsWith("@telegram.invalid")) "Telegram " + it.removeSuffix("@telegram.invalid").removePrefix("tg") else it },
                product = r.str("tariff"),
                amount = r.longOrNull("amount_minor")?.let { MoneyAmount(it, r.str("currency")) },
                channel = r.str("source"),
                renewal = r.bool("is_renewal"),
            )
        }
        FinanceRes(
            period = period,
            revenue = revenue.map { MoneyAmount(it.long("total"), it.str("currency")) },
            paidOrders = revenue.sumOf { it.int("n") },
            newSubscriptions = subs.int("new"),
            renewals = subs.int("renewals"),
            activeSubscribers = active,
            mrr = mrr,
            byChannel = byChannel,
            byProduct = byProduct,
            days = days,
            recent = recent,
            unpaidOrders = unpaid,
        )
    }
}
