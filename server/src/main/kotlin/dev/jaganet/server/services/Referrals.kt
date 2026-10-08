package dev.jaganet.server.services

import dev.jaganet.api.AdminReferralsRes
import dev.jaganet.api.CountBy
import dev.jaganet.api.ErrorCode
import dev.jaganet.api.Funnel
import dev.jaganet.api.MoneyAmount
import dev.jaganet.api.ReferralDay
import dev.jaganet.api.ReferralLink
import dev.jaganet.api.ReferralPeriod
import dev.jaganet.api.ReferralRes
import dev.jaganet.api.ReferralStatsRes
import dev.jaganet.api.ReferredStatus
import dev.jaganet.api.ReferredUser
import dev.jaganet.api.TopReferrer
import dev.jaganet.server.AppError
import dev.jaganet.server.Ctx
import dev.jaganet.server.badRequest
import dev.jaganet.server.db.Row
import dev.jaganet.server.db.Sql
import dev.jaganet.server.notFound
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Who brought a new user: the link and its owner. */
data class Referrer(val userId: String, val linkId: String)

/**
 * The referral program. Every user has a main link (made by the database when the user is
 * created) and can add more, one per campaign ("Instagram", "YouTube"…), to compare them.
 *
 * Funnel per link: clicks (visits of /r/<code> or Telegram /start <code>), unique visitors,
 * sign-ups (first touch: the link a new account came through), people who paid for the first
 * time, all purchases, and revenue of website / Telegram orders. No IP addresses are stored.
 */
class Referrals(private val ctx: Ctx, private val botUsername: () -> String? = { null }) {

    /* ---------------- links ---------------- */

    private fun webUrl(code: String) = "${ctx.cfg.publicUrl.trimEnd('/')}/r/${URLEncoder.encode(code, "UTF-8")}"
    private fun telegramUrl(code: String) = botUsername()?.let { "https://t.me/$it?start=${URLEncoder.encode(code, "UTF-8")}" }

    private fun Row.toLink(f: Funnel) = ReferralLink(
        id = str("id"), name = str("name"), code = str("code"), main = bool("main"),
        webUrl = webUrl(str("code")), telegramUrl = telegramUrl(str("code")),
        createdAt = instant("created_at").toString(), funnel = f,
    )

    suspend fun createLink(userId: String, name: String, customCode: String?): ReferralLink {
        val n = name.trim()
        if (n.isEmpty() || n.length > 40) throw badRequest("Name must be 1 to 40 characters")
        val custom = customCode?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
        if (custom != null && !CODE.matches(custom)) throw badRequest("Code must be 3 to 32 letters, digits or hyphens")
        return ctx.db.tx { sql ->
            val count = sql.one("SELECT count(*)::int AS n FROM referral_links WHERE user_id=?::uuid AND archived_at IS NULL", userId)!!.int("n")
            if (count >= MAX_LINKS) throw badRequest("You can have up to 50 links")
            val code = custom ?: generateSequence { randomCode() }.first { sql.one("SELECT 1 FROM referral_links WHERE code=?", it) == null }
            val row = sql.one(
                "INSERT INTO referral_links (user_id, code, name) VALUES (?::uuid,?,?) ON CONFLICT (code) DO NOTHING RETURNING *",
                userId, code, n,
            ) ?: throw AppError(409, ErrorCode.BAD_REQUEST, "This code is taken")
            row.toLink(Funnel())
        }
    }

    suspend fun rename(userId: String, id: String, name: String) {
        val n = name.trim()
        if (n.isEmpty() || n.length > 40) throw badRequest("Name must be 1 to 40 characters")
        val rows = ctx.db.run { it.exec("UPDATE referral_links SET name=? WHERE id=?::uuid AND user_id=?::uuid AND NOT main AND archived_at IS NULL", n, id, userId) }
        if (rows == 0) throw notFound("Link not found")
    }

    /** Stops counting new clicks; history stays in the statistics. The main link can't be archived. */
    suspend fun archive(userId: String, id: String) {
        val rows = ctx.db.run { it.exec("UPDATE referral_links SET archived_at=? WHERE id=?::uuid AND user_id=?::uuid AND NOT main AND archived_at IS NULL", ctx.now(), id, userId) }
        if (rows == 0) throw notFound("Link not found")
    }

    /* ---------------- tracking ---------------- */

    /** Counts a visit of a link. Returns the link's code, or null if there is no such active link. */
    suspend fun click(code: String, visitorKey: String, channel: String, source: String?): String? = ctx.db.run { sql ->
        val link = sql.one("SELECT id, code FROM referral_links WHERE code=? AND archived_at IS NULL", code.trim().uppercase()) ?: return@run null
        sql.exec(
            "INSERT INTO referral_clicks (link_id, visitor, channel, source) VALUES (?::uuid,?,?,?)",
            link.str("id"), Crypto.hmac(ctx.cfg.authSecret, "visitor:$visitorKey"), channel, source?.take(100)?.lowercase(),
        )
        link.str("code")
    }

    /* ---------------- statistics ---------------- */

    /** Old summary for the app's first version. */
    suspend fun get(p: Principal): ReferralRes = ctx.db.run { sql ->
        val code = sql.one("SELECT referral_code FROM users WHERE id=?::uuid", p.user.id)!!.str("referral_code")
        val r = sql.one(
            """SELECT count(*)::int AS invited,
                      count(*) FILTER (WHERE EXISTS (SELECT 1 FROM subscriptions s WHERE s.user_id=u.id AND s.source <> 'referral'))::int AS subscribed
                 FROM users u WHERE u.referred_by=?::uuid""",
            p.user.id,
        )!!
        ReferralRes(code, r.int("invited"), r.int("subscribed"), ctx.live.plans.referralRewardDays, daysEarned(sql, p.user.id), webUrl(code))
    }

    private fun daysEarned(sql: Sql, userId: String) = sql.one(
        "SELECT coalesce(sum(extract(epoch FROM expires_at - started_at) / 86400), 0)::int AS d FROM subscriptions WHERE user_id=?::uuid AND source='referral'",
        userId,
    )!!.int("d")

    private fun since(period: ReferralPeriod): Instant = when (period) {
        ReferralPeriod.D7 -> ctx.now().minus(Duration.ofDays(7))
        ReferralPeriod.D30 -> ctx.now().minus(Duration.ofDays(30))
        ReferralPeriod.D90 -> ctx.now().minus(Duration.ofDays(90))
        ReferralPeriod.ALL -> Instant.EPOCH
    }

    suspend fun stats(userId: String, period: ReferralPeriod): ReferralStatsRes = ctx.db.run { sql ->
        val from = since(period)
        val scope = Scope("l.user_id = ?::uuid", listOf(userId))
        // Archived links leave the list but their history stays in the totals.
        val all = linkFunnels(sql, scope, from, includeArchived = true)
        ReferralStatsRes(
            period = period,
            totals = totals(sql, scope, from, all.map { it.second }),
            days = days(sql, scope, from),
            links = all.filter { it.first.instantOrNull("archived_at") == null }.map { (row, f) -> row.toLink(f) },
            sources = sources(sql, scope, from),
            channels = channels(sql, scope, from),
            recent = recent(sql, scope, from),
            rewardDays = ctx.live.plans.referralRewardDays,
            daysEarned = daysEarned(sql, userId),
        )
    }

    suspend fun admin(period: ReferralPeriod): AdminReferralsRes = ctx.db.run { sql ->
        val from = since(period)
        val all = Scope("TRUE", emptyList())
        val links = linkFunnels(sql, all, from, includeArchived = true)
        val top = links.groupBy { it.first.str("user_id") }.map { (_, ls) ->
            TopReferrer(ls.first().first.str("email"), ls.size, sum(ls.map { it.second }))
        }.filter { it.funnel.clicks + it.funnel.signups > 0 }
            .sortedWith(compareByDescending<TopReferrer> { it.funnel.paidUsers }.thenByDescending { it.funnel.signups }.thenByDescending { it.funnel.clicks })
            .take(20)
        AdminReferralsRes(period, totals(sql, all, from, links.map { it.second }), days(sql, all, from), sources(sql, all, from), channels(sql, all, from), top)
    }

    /** A filter on referral_links aliased "l". */
    private class Scope(val where: String, val args: List<Any?>)

    private fun linkFunnels(sql: Sql, scope: Scope, from: Instant, includeArchived: Boolean): List<Pair<Row, Funnel>> {
        val rows = sql.query(
            """SELECT l.*, o.email,
                 (SELECT count(*) FROM referral_clicks c WHERE c.link_id=l.id AND c.created_at >= ?)::int AS clicks,
                 (SELECT count(DISTINCT c.visitor) FROM referral_clicks c WHERE c.link_id=l.id AND c.created_at >= ?)::int AS visitors,
                 (SELECT count(*) FROM users u WHERE u.referral_link_id=l.id AND u.created_at >= ?)::int AS signups,
                 (SELECT count(*) FROM users u WHERE u.referral_link_id=l.id
                     AND (SELECT min(s.created_at) FROM subscriptions s WHERE s.user_id=u.id AND s.source <> 'referral') >= ?)::int AS paid,
                 (SELECT count(*) FROM subscriptions s JOIN users u ON u.id=s.user_id
                   WHERE u.referral_link_id=l.id AND s.source <> 'referral' AND s.created_at >= ?)::int AS purchases
               FROM referral_links l JOIN users o ON o.id = l.user_id
              WHERE ${scope.where} ${if (includeArchived) "" else "AND l.archived_at IS NULL"}
              ORDER BY l.main DESC, l.created_at""",
            from, from, from, from, from, *scope.args.toTypedArray(),
        )
        val revenue = sql.query(
            """SELECT u.referral_link_id::text AS link, o.currency, sum(o.amount_minor)::bigint AS total
                 FROM orders o JOIN users u ON u.id = o.user_id JOIN referral_links l ON l.id = u.referral_link_id
                WHERE o.status='paid' AND o.paid_at >= ? AND ${scope.where}
                GROUP BY 1, 2""",
            from, *scope.args.toTypedArray(),
        ).groupBy({ it.str("link") }, { MoneyAmount(it.long("total"), it.str("currency")) })
        return rows.map { r ->
            r to Funnel(r.int("clicks"), r.int("visitors"), r.int("signups"), r.int("paid"), r.int("purchases"), revenue[r.str("id")].orEmpty().sortedBy { it.currency })
        }
    }

    private fun sum(fs: List<Funnel>) = Funnel(
        clicks = fs.sumOf { it.clicks }, visitors = fs.sumOf { it.visitors }, signups = fs.sumOf { it.signups },
        paidUsers = fs.sumOf { it.paidUsers }, purchases = fs.sumOf { it.purchases },
        revenue = fs.flatMap { it.revenue }.groupBy { it.currency }.map { (c, l) -> MoneyAmount(l.sumOf { it.minor }, c) }.sortedBy { it.currency },
    )

    /** Like [sum], but unique visitors counted once across links. */
    private fun totals(sql: Sql, scope: Scope, from: Instant, fs: List<Funnel>): Funnel {
        val visitors = sql.one(
            """SELECT count(DISTINCT c.visitor)::int AS n FROM referral_clicks c JOIN referral_links l ON l.id=c.link_id
                WHERE c.created_at >= ? AND ${scope.where}""",
            from, *scope.args.toTypedArray(),
        )!!.int("n")
        return sum(fs).copy(visitors = visitors)
    }

    private fun days(sql: Sql, scope: Scope, from: Instant): List<ReferralDay> {
        val today = LocalDate.ofInstant(ctx.now(), ZoneOffset.UTC)
        // "All time" shows the last year at most, starting at the first activity.
        val first = sql.one("SELECT min(l.created_at) AS t FROM referral_links l WHERE ${scope.where}", *scope.args.toTypedArray())?.instantOrNull("t")
        val startAt = if (from == Instant.EPOCH) maxOf(first ?: ctx.now(), ctx.now().minus(Duration.ofDays(365))) else from.plus(Duration.ofDays(1))
        val start = LocalDate.ofInstant(startAt, ZoneOffset.UTC)
        fun byDay(q: String): Map<LocalDate, Int> = sql.query(q, startAt, *scope.args.toTypedArray()).associate { (it["d"] as LocalDate) to it.int("n") }
        val clicks = byDay(
            """SELECT (c.created_at AT TIME ZONE 'UTC')::date AS d, count(*)::int AS n FROM referral_clicks c
                 JOIN referral_links l ON l.id=c.link_id WHERE c.created_at >= ? AND ${scope.where} GROUP BY 1""",
        )
        val signups = byDay(
            """SELECT (u.created_at AT TIME ZONE 'UTC')::date AS d, count(*)::int AS n FROM users u
                 JOIN referral_links l ON l.id=u.referral_link_id WHERE u.created_at >= ? AND ${scope.where} GROUP BY 1""",
        )
        // First paid purchase per referred person, counted on the day it happened.
        val paid = sql.query(
            """SELECT (f.first AT TIME ZONE 'UTC')::date AS d, count(*)::int AS n FROM (
                   SELECT min(s.created_at) AS first FROM subscriptions s JOIN users u ON u.id=s.user_id
                     JOIN referral_links l ON l.id=u.referral_link_id
                    WHERE s.source <> 'referral' AND ${scope.where} GROUP BY u.id
               ) f WHERE f.first >= ? GROUP BY 1""",
            *scope.args.toTypedArray(), startAt,
        ).associate { (it["d"] as LocalDate) to it.int("n") }
        return generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }
            .map { ReferralDay(it.toString(), clicks[it] ?: 0, signups[it] ?: 0, paid[it] ?: 0) }.toList()
    }

    private fun sources(sql: Sql, scope: Scope, from: Instant): List<CountBy> = sql.query(
        """SELECT coalesce(c.source, CASE WHEN c.channel = 'telegram' THEN 'telegram' ELSE 'direct' END) AS k, count(*)::int AS n
             FROM referral_clicks c JOIN referral_links l ON l.id=c.link_id
            WHERE c.created_at >= ? AND ${scope.where} GROUP BY 1 ORDER BY 2 DESC, 1 LIMIT 10""",
        from, *scope.args.toTypedArray(),
    ).map { CountBy(it.str("k"), it.int("n")) }

    private fun channels(sql: Sql, scope: Scope, from: Instant): List<CountBy> = sql.query(
        """SELECT $CHANNEL AS k, count(*)::int AS n FROM users u JOIN referral_links l ON l.id=u.referral_link_id
            WHERE u.created_at >= ? AND ${scope.where} GROUP BY 1 ORDER BY 2 DESC""",
        from, *scope.args.toTypedArray(),
    ).map { CountBy(it.str("k"), it.int("n")) }

    private fun recent(sql: Sql, scope: Scope, from: Instant): List<ReferredUser> = sql.query(
        """SELECT u.email, u.created_at, l.name, $CHANNEL AS channel,
                  (SELECT count(*) FROM subscriptions s WHERE s.user_id=u.id AND s.source <> 'referral')::int AS purchases,
                  (SELECT max(s.expires_at) FROM subscriptions s WHERE s.user_id=u.id AND s.status='active') AS pro_until
             FROM users u JOIN referral_links l ON l.id=u.referral_link_id
            WHERE u.created_at >= ? AND ${scope.where} ORDER BY u.created_at DESC LIMIT 50""",
        from, *scope.args.toTypedArray(),
    ).map { r ->
        val purchases = r.int("purchases")
        val active = r.instantOrNull("pro_until")?.isAfter(ctx.now()) == true
        ReferredUser(
            who = mask(r.str("email")), linkName = r.str("name"), joinedAt = r.instant("created_at").toString(),
            channel = r.str("channel"), purchases = purchases,
            status = when { active && purchases > 0 -> ReferredStatus.ACTIVE; purchases > 0 -> ReferredStatus.LAPSED; else -> ReferredStatus.REGISTERED },
        )
    }

    companion object {
        private val CODE = Regex("^[A-Z0-9-]{3,32}$")
        private const val MAX_LINKS = 50
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        private val random = SecureRandom()
        private fun randomCode() = (1..7).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

        /** Where a referred person signed up: Telegram, the website (browser) or an app. */
        private const val CHANNEL = """CASE WHEN u.telegram_id IS NOT NULL THEN 'telegram'
            WHEN (SELECT d.platform FROM devices d WHERE d.user_id = u.id ORDER BY d.created_at LIMIT 1) IN ('ios','android','desktop') THEN 'app'
            ELSE 'website' END"""

        /** "alex@example.com" -> "al***@example.com"; Telegram accounts -> "Telegram". */
        fun mask(email: String): String {
            if (email.endsWith("@telegram.invalid")) return "Telegram"
            val (local, domain) = email.substringBefore('@') to email.substringAfter('@')
            return local.take(2) + "***@" + domain
        }

        /** The link (and its owner) for a code typed in or carried by a link; null if unknown or archived. */
        fun resolve(sql: Sql, code: String?): Referrer? {
            val c = code?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
            return sql.one("SELECT id, user_id FROM referral_links WHERE code=? AND archived_at IS NULL", c)
                ?.let { Referrer(it.str("user_id"), it.str("id")) }
        }
    }
}
