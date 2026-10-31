package dev.jaganet.server.services

import dev.jaganet.api.AdminReferralRewardsRes
import dev.jaganet.api.AdminReward
import dev.jaganet.api.Format
import dev.jaganet.api.InviteRes
import dev.jaganet.api.InviteReward
import dev.jaganet.api.Protocols
import dev.jaganet.api.ReferralRewardSettings
import dev.jaganet.api.RewardTopReferrer
import dev.jaganet.api.i18n.I18n
import dev.jaganet.api.i18n.Lang
import dev.jaganet.server.Ctx
import dev.jaganet.server.Mode
import dev.jaganet.server.RewardRules
import dev.jaganet.server.badRequest
import dev.jaganet.server.db.Jsonb
import dev.jaganet.server.db.Row
import dev.jaganet.server.db.Sql
import dev.jaganet.server.notFound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * "Invite a friend, get +14 days" (issue #40), on top of the referral links ([Referrals]):
 * who invited whom is `users.referred_by`, set once when the account is made (website code, app
 * invite field or Telegram `/start ref_<code>`).
 *
 * When the invited person pays for the first time (or signs up, if the rules say so) the inviter gets
 * [RewardRules.days] of Pro, from the end of their current subscription or from now if it has ended.
 * One row per reward in `referral_rewards`, unique per invited person and kind, so a repeated webhook
 * or a second purchase never rewards twice. Over the monthly cap a reward is recorded as "capped"
 * without days. A reward whose invited person signed in from the inviter's network or browser is
 * flagged as suspicious (it is still given; the owner can cancel it).
 *
 * The recipient is told in Telegram (bot on, notifications on) or else by e-mail.
 */
class ReferralRewards(
    private val ctx: Ctx,
    private val referrals: Referrals,
    /** Sends a Telegram message; null when the bot is off. Throws when Telegram fails. */
    private val telegram: () -> (suspend (chat: Long, text: String) -> Unit)? = { null },
) {
    private val log = LoggerFactory.getLogger("referral-rewards")

    /** E-mail to one address; replaced in tests. Default: the SMTP settings in force, nothing when e-mail is off. */
    var email: suspend (to: String, subject: String, text: String) -> Boolean = { to, subject, text ->
        ctx.live.smtp?.let { EmailSender(it).send(listOf(to), subject, text); true } ?: false
    }

    val rules: RewardRules get() = ctx.live.referralRewards

    /* ---------------- settings ---------------- */

    fun settings() = rules.let { ReferralRewardSettings(it.days, it.condition, it.inviteeDays, it.monthlyCap) }

    suspend fun saveSettings(s: ReferralRewardSettings) {
        if (s.days !in 0..3650 || s.inviteeDays !in 0..3650 || s.monthlyCap !in 0..10_000 || s.condition !in RewardRules.CONDITIONS) {
            throw badRequest("Some values are out of range")
        }
        apply(s)
        ctx.db.run {
            it.exec(
                "INSERT INTO settings (key, value, updated_at) VALUES (?,?,?) ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value, updated_at=EXCLUDED.updated_at",
                SETTINGS_KEY, Jsonb(Protocols.encode(s).toString()), ctx.now(),
            )
        }
    }

    /** At start: a value saved in the admin panel wins over the REFERRAL_* variables. */
    suspend fun load() {
        val v = ctx.db.run { it.one("SELECT value FROM settings WHERE key=?", SETTINGS_KEY) }?.json("value") ?: return
        runCatching { apply(Protocols.decode<ReferralRewardSettings>(v)) }.onFailure { log.error("bad referral_rewards setting", it) }
    }

    private fun apply(s: ReferralRewardSettings) {
        ctx.live.referralRewards = RewardRules(s.days, RewardRules.condition(s.condition), s.inviteeDays, s.monthlyCap)
    }

    /* ---------------- anti-fraud fingerprints ---------------- */

    /**
     * A successful sign-in from [ip] with browser [userAgent]: remember HMACs of both (never the IP
     * itself) for 90 days, to flag invited people who sign in from the inviter's network or browser.
     */
    suspend fun remember(userId: String, ip: String?, userAgent: String?) {
        val addr = ip?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val now = ctx.now()
        // Called on every GET /me too: write each user + address + browser at most once an hour.
        val seenKey = "$userId|$addr|${userAgent.orEmpty()}"
        if (seen[seenKey]?.let { Duration.between(it, now) < Duration.ofHours(1) } == true) return
        if (seen.size > 50_000) seen.clear()
        seen[seenKey] = now
        runCatching {
            ctx.db.run { sql ->
                for ((kind, h) in fingerprints(addr, userAgent.orEmpty())) {
                    sql.exec(
                        """INSERT INTO user_fingerprints (user_id, kind, hash, last_seen_at) VALUES (?::uuid,?,?,?)
                           ON CONFLICT (user_id, kind, hash) DO UPDATE SET last_seen_at=EXCLUDED.last_seen_at""",
                        userId, kind, h, now,
                    )
                }
                sql.exec("DELETE FROM user_fingerprints WHERE user_id=?::uuid AND last_seen_at < ?", userId, now.minus(FINGERPRINT_TTL))
            }
        }.onFailure { log.warn("fingerprint not saved: ${it.message}") }
    }

    private val seen = java.util.concurrent.ConcurrentHashMap<String, Instant>()

    private fun fingerprints(ip: String, ua: String) = listOf(
        "ip" to Crypto.hmac(ctx.cfg.authSecret, "fp-ip:$ip"),
        "device" to Crypto.hmac(ctx.cfg.authSecret, "fp-device:$ip|$ua"),
    )

    /* ---------------- rewards ---------------- */

    /**
     * Called in the payment's transaction, after the purchase is recorded. Rewards the inviter of
     * [userId] if this is their first paid purchase and the rules reward the first payment.
     */
    fun onPaid(sql: Sql, userId: String, orderId: String) {
        val r = rules
        if (!r.enabled || r.condition != RewardRules.FIRST_PAYMENT) return
        val paid = sql.one("SELECT count(*)::int AS n FROM subscriptions WHERE user_id=?::uuid AND source NOT IN ('referral','test')", userId)!!.int("n")
        if (paid != 1) return
        award(sql, ctx, r, userId, RewardRules.FIRST_PAYMENT, orderId)
    }

    /** Background work (telling recipients); tests run it inline so they can check the result at once. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** [deliver] without making the caller (a payment webhook) wait for Telegram or e-mail. */
    suspend fun deliverSoon() {
        if (ctx.cfg.mode == Mode.TEST) runCatching { deliver() }.onFailure { log.warn("reward notices: ${it.message}") }
        else scope.launch { runCatching { deliver() }.onFailure { log.warn("reward notices: ${it.message}") } }
    }

    /** Tells every recipient not told yet. Safe to call any time; also runs every minute. */
    suspend fun deliver(): Int {
        val due = ctx.db.run { sql ->
            sql.query(
                """SELECT r.id, r.user_id, r.kind, r.days, r.created_at, u.email, u.lang, u.telegram_id, u.tg_notify, u.tg_blocked_at
                     FROM referral_rewards r JOIN users u ON u.id = r.user_id
                    WHERE r.notified_at IS NULL ORDER BY r.created_at LIMIT 50""",
            )
        }
        var sent = 0
        for (r in due) {
            // Claim it first, so two runs never send the same message.
            val claimed = ctx.db.run { it.exec("UPDATE referral_rewards SET notified_at=? WHERE id=?::uuid AND notified_at IS NULL", ctx.now(), r.str("id")) } == 1
            if (!claimed) continue
            val via = runCatching { tell(r) }.getOrElse { e ->
                log.warn("reward notice: ${e.message}")
                // Telegram or e-mail unreachable: try again next round, for two days.
                if (Duration.between(r.instant("created_at"), ctx.now()) < Duration.ofDays(2)) {
                    ctx.db.run { it.exec("UPDATE referral_rewards SET notified_at=NULL WHERE id=?::uuid", r.str("id")) }
                    continue
                }
                "none"
            }
            ctx.db.run { it.exec("UPDATE referral_rewards SET notified_via=? WHERE id=?::uuid", via, r.str("id")) }
            if (via != "none") sent++
        }
        return sent
    }

    private suspend fun tell(r: Row): String {
        val lang = Lang.of(r.strOrNull("lang")) ?: Lang.DEFAULT
        val until = ctx.db.run { it.one("SELECT max(expires_at) AS e FROM subscriptions WHERE user_id=?::uuid AND status='active'", r.str("user_id")) }?.instantOrNull("e")
        val days = I18n.plural(lang, r.int("days").toLong(), "{n} day|{n} days")
        val date = until?.let { Format.date(it.toString(), lang) } ?: ""
        val text = if (r.str("kind") == "invitee") I18n.tr(lang, "A welcome bonus for joining by invitation, {days} of Pro. Pro is active until {date}.", "days" to days, "date" to date)
        else I18n.tr(lang, "A friend you invited paid for a subscription, you get {days} of Pro. Pro is active until {date}.", "days" to days, "date" to date)
        val chat = r.longOrNull("telegram_id")
        val send = telegram()
        if (chat != null && send != null && r.bool("tg_notify") && r["tg_blocked_at"] == null) {
            send(chat, "🎁 $text")
            return "telegram"
        }
        val address = r.str("email")
        if (!address.endsWith(".invalid") && email(address, I18n.tr(lang, "Boop VPN, a reward for inviting a friend"), text)) return "email"
        return "none"
    }

    /* ---------------- statistics ---------------- */

    suspend fun invite(userId: String): InviteRes = ctx.db.run { sql ->
        val r = rules
        val link = sql.one("SELECT code FROM referral_links WHERE user_id=?::uuid AND main", userId)?.str("code")
            ?: sql.one("SELECT upper(referral_code) AS c FROM users WHERE id=?::uuid", userId)!!.str("c")
        val counts = sql.one(
            """SELECT count(*)::int AS invited,
                      count(*) FILTER (WHERE EXISTS (SELECT 1 FROM subscriptions s WHERE s.user_id=u.id AND s.source NOT IN ('referral','test')))::int AS paid
                 FROM users u WHERE u.referred_by=?::uuid""",
            userId,
        )!!
        val rewards = sql.query(
            """SELECT r.created_at, r.days, r.status, r.kind, CASE WHEN r.kind='invitee' THEN ref.email ELSE inv.email END AS other
                 FROM referral_rewards r JOIN users inv ON inv.id=r.invitee_id JOIN users ref ON ref.id=r.referrer_id
                WHERE r.user_id=?::uuid ORDER BY r.created_at DESC LIMIT 50""",
            userId,
        ).map { InviteReward(it.instant("created_at").toString(), Referrals.mask(it.str("other")), it.int("days"), it.str("status"), it.str("kind")) }
        InviteRes(
            enabled = r.enabled, code = link, webUrl = referrals.webUrl(link), telegramUrl = referrals.telegramUrl(link),
            rewardDays = r.days, condition = r.condition, inviteeDays = r.inviteeDays,
            invited = counts.int("invited"), paid = counts.int("paid"), daysEarned = daysEarned(sql, userId), rewards = rewards,
        )
    }

    suspend fun admin(suspiciousOnly: Boolean): AdminReferralRewardsRes = ctx.db.run { sql ->
        val totals = sql.one(
            """SELECT count(*) FILTER (WHERE status='granted')::int AS granted,
                      coalesce(sum(days) FILTER (WHERE status='granted'), 0)::int AS days,
                      count(*) FILTER (WHERE status='capped')::int AS capped,
                      count(*) FILTER (WHERE status='cancelled')::int AS cancelled,
                      count(*) FILTER (WHERE suspicion IS NOT NULL)::int AS suspicious
                 FROM referral_rewards""",
        )!!
        val top = sql.query(
            """SELECT u.id, u.email,
                      (SELECT count(*) FROM users i WHERE i.referred_by=u.id)::int AS invited,
                      (SELECT count(*) FROM users i WHERE i.referred_by=u.id
                          AND EXISTS (SELECT 1 FROM subscriptions s WHERE s.user_id=i.id AND s.source NOT IN ('referral','test')))::int AS paid,
                      (SELECT count(*) FROM referral_rewards r WHERE r.user_id=u.id AND r.kind='referrer' AND r.status='granted')::int AS rewards,
                      (SELECT coalesce(sum(r.days), 0) FROM referral_rewards r WHERE r.user_id=u.id AND r.kind='referrer' AND r.status='granted')::int AS days,
                      (SELECT count(*) FROM referral_rewards r WHERE r.referrer_id=u.id AND r.suspicion IS NOT NULL)::int AS suspicious
                 FROM users u WHERE EXISTS (SELECT 1 FROM users i WHERE i.referred_by=u.id)
                ORDER BY days DESC, paid DESC, invited DESC LIMIT 20""",
        ).map { RewardTopReferrer(it.str("id"), it.str("email"), it.int("invited"), it.int("paid"), it.int("rewards"), it.int("days"), it.int("suspicious")) }
        val rows = sql.query(
            "$ADMIN_ROWS ${if (suspiciousOnly) "WHERE r.suspicion IS NOT NULL" else ""} ORDER BY r.created_at DESC LIMIT 200",
        ).map { it.toAdmin() }
        AdminReferralRewardsRes(
            settings(), totals.int("granted"), totals.int("days"), totals.int("capped"), totals.int("cancelled"), totals.int("suspicious"), top, rows,
        )
    }

    /**
     * Takes a reward back: its days are removed and subscription time stacked after it moves back
     * by what was left of it. Cancelled rewards still count as used (the invited person doesn't count again).
     */
    suspend fun cancel(id: String): AdminReward = ctx.db.tx { sql ->
        if (runCatching { java.util.UUID.fromString(id) }.isFailure) throw notFound("Reward not found")
        val r = sql.one("SELECT * FROM referral_rewards WHERE id=?::uuid FOR UPDATE", id) ?: throw notFound("Reward not found")
        if (r.str("status") == "granted") {
            val now = ctx.now()
            r.strOrNull("subscription_id")?.let { subId ->
                val sub = sql.one("SELECT * FROM subscriptions WHERE id=?::uuid AND status='active'", subId)
                if (sub != null) {
                    val end = sub.instant("expires_at")
                    val left = Duration.between(maxOf(sub.instant("started_at"), now), end)
                    sql.exec("UPDATE subscriptions SET status='cancelled', cancelled_at=? WHERE id=?::uuid", now, subId)
                    if (!left.isNegative && !left.isZero) {
                        sql.exec(
                            """UPDATE subscriptions SET started_at = started_at - ?::interval, expires_at = expires_at - ?::interval
                                WHERE user_id=?::uuid AND status='active' AND started_at >= ? AND id <> ?::uuid""",
                            "${left.seconds} seconds", "${left.seconds} seconds", r.str("user_id"), end, subId,
                        )
                    }
                }
            }
        }
        sql.exec("UPDATE referral_rewards SET status='cancelled', cancelled_at=coalesce(cancelled_at, ?) WHERE id=?::uuid", ctx.now(), id)
        sql.one("$ADMIN_ROWS WHERE r.id=?::uuid", id)!!.toAdmin()
    }

    private fun Row.toAdmin() = AdminReward(
        id = str("id"), createdAt = instant("created_at").toString(), userId = str("user_id"), userEmail = str("user_email"),
        referrerEmail = str("referrer_email"), inviteeId = str("invitee_id"), inviteeEmail = str("invitee_email"),
        kind = str("kind"), reason = str("reason"), days = int("days"), status = str("status"), suspicion = strOrNull("suspicion"),
        notifiedVia = strOrNull("notified_via"), cancelledAt = instantOrNull("cancelled_at")?.toString(),
    )

    companion object {
        const val SETTINGS_KEY = "referral_rewards"
        val FINGERPRINT_TTL: Duration = Duration.ofDays(90)

        private const val ADMIN_ROWS = """SELECT r.*, u.email AS user_email, ref.email AS referrer_email, inv.email AS invitee_email
            FROM referral_rewards r JOIN users u ON u.id=r.user_id JOIN users ref ON ref.id=r.referrer_id JOIN users inv ON inv.id=r.invitee_id"""

        /** Reward days received, without cancelled ones (older invite rewards in subscriptions included). */
        fun daysEarned(sql: Sql, userId: String) = sql.one(
            """SELECT coalesce(sum(extract(epoch FROM expires_at - started_at) / 86400), 0)::int AS d
                 FROM subscriptions WHERE user_id=?::uuid AND source='referral' AND status <> 'cancelled'""",
            userId,
        )!!.int("d")

        /**
         * A new account was made (inside its transaction). With the "signup" rule its inviter is rewarded now.
         * A static entry point: account creation (e-mail, Telegram) has no access to the services.
         */
        fun onSignup(sql: Sql, ctx: Ctx, userId: String) {
            val r = ctx.live.referralRewards
            if (!r.enabled || r.condition != RewardRules.SIGNUP) return
            award(sql, ctx, r, userId, RewardRules.SIGNUP, null)
        }

        /**
         * Rewards the inviter of [inviteeId] (and the invitee, if the rules say so) once. Nothing for
         * accounts without an inviter, self-invites, or an invited person already rewarded for.
         */
        private fun award(sql: Sql, ctx: Ctx, r: RewardRules, inviteeId: String, reason: String, orderId: String?) {
            val invitee = sql.one("SELECT referred_by FROM users WHERE id=?::uuid", inviteeId) ?: return
            val referrerId = invitee.strOrNull("referred_by")?.takeIf { it != inviteeId } ?: return
            // One reward at a time per inviter: the monthly cap holds even for payments at the same moment.
            sql.one("SELECT id FROM users WHERE id=?::uuid FOR UPDATE", referrerId) ?: return
            val now = ctx.now()
            val suspicion = suspicion(sql, referrerId, inviteeId)
            val used = sql.one(
                "SELECT count(*)::int AS n FROM referral_rewards WHERE user_id=?::uuid AND kind='referrer' AND status='granted' AND created_at >= ?",
                referrerId, monthStart(now),
            )!!.int("n")
            val capped = r.monthlyCap > 0 && used >= r.monthlyCap
            give(sql, now, referrerId, referrerId, inviteeId, "referrer", reason, orderId, r.days, if (capped) "capped" else "granted", suspicion)
            if (r.inviteeDays > 0) give(sql, now, inviteeId, referrerId, inviteeId, "invitee", reason, orderId, r.inviteeDays, "granted", suspicion)
        }

        private fun give(
            sql: Sql, now: Instant, userId: String, referrerId: String, inviteeId: String, kind: String, reason: String,
            orderId: String?, days: Int, status: String, suspicion: String?,
        ) {
            val row = sql.one(
                """INSERT INTO referral_rewards (user_id, referrer_id, invitee_id, kind, reason, order_id, days, status, suspicion, created_at, notified_at, notified_via)
                   VALUES (?::uuid,?::uuid,?::uuid,?,?,?::uuid,?,?,?,?,?,?)
                   ON CONFLICT (invitee_id, kind) DO NOTHING RETURNING id""",
                userId, referrerId, inviteeId, kind, reason, orderId, days, status, suspicion, now,
                // Capped rewards give nothing, so there is nothing to tell.
                now.takeIf { status != "granted" }, "none".takeIf { status != "granted" },
            ) ?: return
            if (status != "granted") return
            val id = row.str("id")
            // From the end of the current subscription, or from now if it has ended.
            val last = sql.one("SELECT max(expires_at) AS e FROM subscriptions WHERE user_id=?::uuid AND status='active'", userId)?.instantOrNull("e")
            val start = maxOf(now, last ?: now)
            val sub = sql.one(
                """INSERT INTO subscriptions (user_id, product_id, source, external_id, started_at, expires_at, auto_renew, tariff_name, device_limit, created_at)
                   VALUES (?::uuid, 'referral', 'referral', ?, ?, ?, false, NULL, NULL, ?)
                   ON CONFLICT (source, external_id) DO NOTHING RETURNING id""",
                userId, "reward:$id", start, start.plus(Duration.ofDays(days.toLong())), now,
            )
            sql.exec("UPDATE referral_rewards SET subscription_id=?::uuid WHERE id=?::uuid", sub?.str("id"), id)
        }

        /** Whether the invited person signed in from the same browser ("same_device") or network ("same_ip") as the inviter. */
        private fun suspicion(sql: Sql, referrerId: String, inviteeId: String): String? {
            val r = sql.one(
                """SELECT bool_or(a.kind='device') AS device, bool_or(a.kind='ip') AS ip
                     FROM user_fingerprints a JOIN user_fingerprints b ON b.kind=a.kind AND b.hash=a.hash
                    WHERE a.user_id=?::uuid AND b.user_id=?::uuid""",
                inviteeId, referrerId,
            )
            return when {
                r?.get("device") == true -> "same_device"
                r?.get("ip") == true -> "same_ip"
                else -> null
            }
        }
    }
}
