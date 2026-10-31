package dev.jaganet.server.telegram

import dev.jaganet.api.DeviceInfo
import dev.jaganet.api.ErrorCode
import dev.jaganet.api.SessionRes
import dev.jaganet.api.i18n.Lang
import dev.jaganet.server.AppError
import dev.jaganet.server.Ctx
import dev.jaganet.server.db.Sql
import dev.jaganet.server.services.AuthService
import dev.jaganet.server.services.Billing
import dev.jaganet.server.services.Crypto
import dev.jaganet.server.services.ReferralRewards
import dev.jaganet.server.services.Referrals
import dev.jaganet.server.services.Tunnels
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The Telegram user as Telegram describes them (message.from, or the Login Widget). */
data class TgUser(val id: Long, val username: String? = null, val firstName: String? = null)

/** An account found or made for a Telegram user. */
data class TgAccount(val userId: String, val email: String, val lang: Lang?, val created: Boolean)

/** What /start link_<code> did. */
enum class LinkResult {
    LINKED,
    /** This Telegram was already linked to that account. */
    ALREADY,
    /** The code is wrong, used or expired. */
    INVALID,
    /** The account already has another Telegram: unlink it on the website first. */
    ACCOUNT_HAS_OTHER,
    /** This Telegram belongs to another e-mail account. */
    TELEGRAM_ON_OTHER,
    /** This Telegram has its own account with purchases or keys: merging would lose them. */
    TELEGRAM_HAS_PURCHASES,
}

/**
 * Accounts of Telegram users (issue #38). `users.telegram_id` (unique) is the only link, so there
 * is never more than one account per Telegram user:
 *  - the first contact with the bot (or the Login Widget) makes an account with the placeholder
 *    e-mail `tg<id>@telegram.invalid`, unless the Telegram is already linked;
 *  - a one-time code from the website's account page (`/start link_<code>`) links Telegram to an
 *    existing e-mail account. If the Telegram user already had an empty bot-made account, that one
 *    is removed; one with purchases or keys is left alone and the link is refused.
 */
class TelegramAccounts(private val ctx: Ctx, private val auth: AuthService, private val tunnels: Tunnels) {
    private fun hash(v: String) = Crypto.hmac(ctx.cfg.authSecret, v)

    /**
     * The account of [tg], made on first contact. [ref] is the referral code from `/start ref_<code>`:
     * a new account remembers it raw (`users.tg_start_ref`) and, when it is a known link, who invited
     * them (`referred_by`, `referral_link_id`), as on the website. Existing accounts keep their inviter.
     */
    suspend fun findOrCreate(tg: TgUser, ref: String? = null): TgAccount = ctx.db.tx { sql ->
        sql.one("SELECT id, email, lang, tg_username, tg_blocked_at FROM users WHERE telegram_id=?", tg.id)?.let { r ->
            if (r.strOrNull("tg_username") != tg.username || r["tg_blocked_at"] != null) {
                sql.exec("UPDATE users SET tg_username=?, tg_blocked_at=NULL WHERE id=?::uuid", tg.username, r.str("id"))
            }
            return@tx TgAccount(r.str("id"), r.str("email"), Lang.of(r.strOrNull("lang")), false)
        }
        val email = placeholderEmail(tg.id)
        val code = ref?.trim()?.take(64)?.takeIf { it.isNotEmpty() }
        val referrer = Referrals.resolve(sql, code)
        val r = sql.one(
            """INSERT INTO users (email, referral_code, referred_by, referral_link_id, telegram_id, tg_username, tg_start_ref, created_at)
               VALUES (?,?,?::uuid,?::uuid,?,?,?,?)
               ON CONFLICT (email) DO UPDATE SET telegram_id=EXCLUDED.telegram_id, tg_username=EXCLUDED.tg_username
               RETURNING id, email, lang, (xmax = 0) AS inserted""",
            email, Crypto.referralCode("tg@x"), referrer?.userId, referrer?.linkId, tg.id, tg.username, code?.uppercase(), ctx.now(),
        )!!
        val created = r.bool("inserted")
        if (created) {
            Billing.grantSignupPro(sql, r.str("id"), ctx.cfg.signupProDays, ctx.now())
            ReferralRewards.onSignup(sql, ctx, r.str("id"))
        }
        TgAccount(r.str("id"), r.str("email"), Lang.of(r.strOrNull("lang")), created)
    }

    /* ---------------- linking an e-mail account ---------------- */

    /** A one-time code for `t.me/<bot>?start=link_<code>`, valid [LINK_CODE_TTL]. */
    suspend fun createLinkCode(userId: String): Pair<String, Instant> {
        val code = Crypto.newToken()
        val expires = ctx.now().plus(LINK_CODE_TTL)
        ctx.db.run { sql ->
            sql.exec("DELETE FROM telegram_link_codes WHERE user_id=?::uuid OR expires_at < ?", userId, ctx.now())
            sql.exec("INSERT INTO telegram_link_codes (code_hash, user_id, expires_at) VALUES (?,?::uuid,?)", hash("tglink:$code"), userId, expires)
        }
        return code to expires
    }

    /** `/start link_<code>`: link [tg] to the account that made the code. The code works once. */
    suspend fun link(code: String, tg: TgUser): Pair<LinkResult, String?> = ctx.db.tx { sql ->
        val userId = sql.one(
            "UPDATE telegram_link_codes SET used_at=? WHERE code_hash=? AND used_at IS NULL AND expires_at > ? RETURNING user_id",
            ctx.now(), hash("tglink:$code"), ctx.now(),
        )?.str("user_id") ?: return@tx LinkResult.INVALID to null
        val target = sql.one("SELECT id, email, telegram_id FROM users WHERE id=?::uuid FOR UPDATE", userId) ?: return@tx LinkResult.INVALID to null
        val email = target.str("email")
        when (target.longOrNull("telegram_id")) {
            tg.id -> return@tx LinkResult.ALREADY to email
            null -> {}
            else -> return@tx LinkResult.ACCOUNT_HAS_OTHER to email
        }
        val other = sql.one("SELECT id, email FROM users WHERE telegram_id=? FOR UPDATE", tg.id)
        if (other != null) {
            if (!isPlaceholder(other.str("email"))) return@tx LinkResult.TELEGRAM_ON_OTHER to email
            if (hasPurchases(sql, other.str("id"))) return@tx LinkResult.TELEGRAM_HAS_PURCHASES to email
            deleteAccount(sql, other.str("id"))
        }
        sql.exec("UPDATE users SET telegram_id=?, tg_username=?, tg_blocked_at=NULL WHERE id=?::uuid", tg.id, tg.username, userId)
        LinkResult.LINKED to email
    }

    /** Website: forget the Telegram of an e-mail account. A Telegram-only account can't: it has nothing else to sign in with. */
    suspend fun unlink(userId: String) = ctx.db.run { sql ->
        val r = sql.one("SELECT email FROM users WHERE id=?::uuid", userId) ?: throw AppError(404, ErrorCode.NOT_FOUND, "Account not found")
        if (isPlaceholder(r.str("email"))) throw AppError(400, ErrorCode.BAD_REQUEST, "This account signs in only with Telegram")
        sql.exec("UPDATE users SET telegram_id=NULL, tg_username=NULL WHERE id=?::uuid", userId)
    }

    /** Anything that would be lost by removing the account: payments, keys, invited people. */
    private fun hasPurchases(sql: Sql, userId: String): Boolean = sql.one(
        """SELECT EXISTS (SELECT 1 FROM orders WHERE user_id=?::uuid AND status='paid')
               OR EXISTS (SELECT 1 FROM subscriptions WHERE user_id=?::uuid AND NOT (source='test' AND external_id LIKE 'gift:%'))
               OR EXISTS (SELECT 1 FROM access_keys WHERE user_id=?::uuid)
               OR EXISTS (SELECT 1 FROM users WHERE referred_by=?::uuid) AS v""",
        userId, userId, userId, userId,
    )!!.bool("v")

    /** As DELETE /v1/me: tunnels off the nodes first, then the rows. */
    private suspend fun deleteAccount(sql: Sql, userId: String) {
        sql.query("SELECT id FROM devices WHERE user_id=?::uuid", userId).forEach { tunnels.revoke(sql, it.str("id")) }
        sql.exec("DELETE FROM users WHERE id=?::uuid", userId)
    }

    /* ---------------- Login Widget ---------------- */

    /** Website sign-in with the Telegram Login Widget's data, checked against the bot token. */
    suspend fun signInWithWidget(fields: Map<String, String>, device: DeviceInfo, ref: String?): SessionRes {
        val token = ctx.cfg.telegramBotToken ?: throw AppError(404, ErrorCode.NOT_FOUND, "Telegram sign-in is off")
        val tgId = verifyWidget(fields, token, ctx.now())
            ?: throw AppError(400, ErrorCode.INVALID_CODE, "Telegram sign-in failed, try again")
        val acc = findOrCreate(TgUser(tgId, fields["username"], fields["first_name"]), ref)
        return ctx.db.tx { sql -> auth.createSession(sql, sql.one("SELECT * FROM users WHERE id=?::uuid", acc.userId)!!, device) }
    }

    companion object {
        val LINK_CODE_TTL: Duration = Duration.ofMinutes(15)
        /** How old the Login Widget's auth_date may be. */
        val WIDGET_MAX_AGE: Duration = Duration.ofHours(1)

        fun placeholderEmail(tgId: Long) = "tg$tgId@telegram.invalid"
        fun isPlaceholder(email: String) = email.endsWith("@telegram.invalid")

        /**
         * The Login Widget check (core.telegram.org/widgets/login#checking-authorization):
         * hash = hex(HMAC-SHA256(data_check_string, SHA256(bot token))), where data_check_string is
         * every other field as key=value, sorted by key, joined by newlines. auth_date must be recent
         * (and not in the future). Returns the Telegram user id, or null.
         */
        fun verifyWidget(fields: Map<String, String>, botToken: String, now: Instant, maxAge: Duration = WIDGET_MAX_AGE): Long? {
            val hash = fields["hash"]?.lowercase() ?: return null
            val id = fields["id"]?.toLongOrNull() ?: return null
            val authDate = fields["auth_date"]?.toLongOrNull()?.let(Instant::ofEpochSecond) ?: return null
            if (authDate.isAfter(now.plusSeconds(60)) || Duration.between(authDate, now) > maxAge) return null
            val check = fields.filterKeys { it != "hash" }.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
            val expected = widgetHash(check, botToken)
            return id.takeIf { MessageDigest.isEqual(expected.toByteArray(), hash.toByteArray()) }
        }

        fun widgetHash(dataCheckString: String, botToken: String): String {
            val secret = MessageDigest.getInstance("SHA-256").digest(botToken.toByteArray())
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
            return mac.doFinal(dataCheckString.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
