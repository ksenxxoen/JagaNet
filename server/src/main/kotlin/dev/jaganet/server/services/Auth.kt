package dev.jaganet.server.services

import dev.jaganet.api.DeviceInfo
import dev.jaganet.api.ErrorCode
import dev.jaganet.api.PairingCodeRes
import dev.jaganet.api.Role
import dev.jaganet.api.SessionRes
import dev.jaganet.api.User
import dev.jaganet.server.AppError
import dev.jaganet.server.Ctx
import dev.jaganet.server.db.Row
import dev.jaganet.server.db.Sql
import dev.jaganet.server.unauthorized
import java.time.Duration

private val OTP_TTL = Duration.ofMinutes(10)
private const val OTP_MAX_ATTEMPTS = 5
private val PAIRING_TTL = Duration.ofMinutes(10)

data class Principal(val user: User, val deviceId: String, val tokenHash: String)

fun Row.toUser() = User(str("id"), str("email"), if (str("role") == "owner") Role.OWNER else Role.USER, instant("created_at").toString())

class AuthService(private val ctx: Ctx) {
    private fun hash(v: String) = Crypto.hmac(ctx.cfg.authSecret, v)

    suspend fun startEmailLogin(email: String, referral: String?): String {
        val code = Crypto.sixDigits()
        ctx.db.run {
            it.exec(
                """INSERT INTO otp_codes (email, code_hash, referral_code, attempts, expires_at) VALUES (?,?,?,0,?)
                   ON CONFLICT (email) DO UPDATE SET code_hash=EXCLUDED.code_hash,
                     referral_code=COALESCE(EXCLUDED.referral_code, otp_codes.referral_code), attempts=0, expires_at=EXCLUDED.expires_at""",
                email, hash("otp:$email:$code"), referral?.uppercase(), ctx.now().plus(OTP_TTL),
            )
        }
        ctx.mailer.sendLoginCode(email, code)
        return code
    }

    suspend fun verifyEmailLogin(email: String, code: String, device: DeviceInfo): SessionRes {
        val h = hash("otp:$email:$code")
        ctx.db.run { sql ->
            val otp = sql.one("SELECT * FROM otp_codes WHERE email=?", email)
            if (otp == null || otp.instant("expires_at") < ctx.now()) throw AppError(400, ErrorCode.INVALID_CODE, "Code expired, ask for a new one")
            if (otp.int("attempts") >= OTP_MAX_ATTEMPTS) throw AppError(429, ErrorCode.TOO_MANY_ATTEMPTS, "Too many attempts, ask for a new code")
            if (!Crypto.safeEqual(otp.str("code_hash"), h)) {
                // Autocommit, outside any transaction, so the failed attempt is never rolled back.
                sql.exec("UPDATE otp_codes SET attempts=attempts+1 WHERE email=?", email)
                throw AppError(400, ErrorCode.INVALID_CODE, "Wrong code")
            }
        }
        return ctx.db.tx { sql ->
            // Consume atomically: of two concurrent requests with the right code only one wins.
            val used = sql.one("DELETE FROM otp_codes WHERE email=? AND code_hash=? AND attempts < ? RETURNING referral_code", email, h, OTP_MAX_ATTEMPTS)
                ?: throw AppError(400, ErrorCode.INVALID_CODE, "Code expired, ask for a new one")
            val user = sql.one("SELECT * FROM users WHERE email=?", email) ?: run {
                val referrer = Referrals.resolve(sql, used.strOrNull("referral_code"))
                val role = if (ctx.cfg.ownerEmail == email) "owner" else "user"
                sql.one(
                    "INSERT INTO users (email, role, referral_code, referred_by, referral_link_id) VALUES (?,?,?,?::uuid,?::uuid) RETURNING *",
                    email, role, Crypto.referralCode(email), referrer?.userId, referrer?.linkId,
                )!!
            }
            createSession(sql, user, device)
        }
    }

    private fun createSession(sql: Sql, user: Row, device: DeviceInfo): SessionRes {
        val deviceId = sql.one(
            "INSERT INTO devices (user_id, name, platform, last_seen_at) VALUES (?::uuid,?,?,?) RETURNING id",
            user.str("id"), device.name.trim().take(60), device.platform.name.lowercase(), ctx.now(),
        )!!.str("id")
        val token = Crypto.newToken()
        sql.exec("INSERT INTO sessions (token_hash, user_id, device_id) VALUES (?,?::uuid,?::uuid)", hash(token), user.str("id"), deviceId)
        return SessionRes(token, user.toUser(), deviceId)
    }

    suspend fun authenticate(header: String?): Principal {
        val token = header?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ") ?: throw unauthorized()
        val th = hash(token)
        return ctx.db.run { sql ->
            val row = sql.one(
                """SELECT u.*, s.device_id FROM sessions s
                     JOIN users u ON u.id = s.user_id
                     JOIN devices d ON d.id = s.device_id
                    WHERE s.token_hash=? AND s.revoked_at IS NULL AND d.removed_at IS NULL""",
                th,
            ) ?: throw unauthorized()
            sql.exec("UPDATE sessions SET last_used_at=? WHERE token_hash=?", ctx.now(), th)
            sql.exec("UPDATE devices SET last_seen_at=? WHERE id=?::uuid", ctx.now(), row.str("device_id"))
            Principal(row.toUser(), row.str("device_id"), th)
        }
    }

    suspend fun logout(p: Principal) = ctx.db.run { it.exec("UPDATE sessions SET revoked_at=? WHERE token_hash=?", ctx.now(), p.tokenHash) }

    /** "Add device": a signed-in device shows a code, the new device types it in. */
    suspend fun createPairingCode(p: Principal): PairingCodeRes = createPairingCodeFor(p.user.id)

    suspend fun createPairingCodeFor(userId: String): PairingCodeRes = ctx.db.run { sql ->
        val expires = ctx.now().plus(PAIRING_TTL)
        sql.exec("DELETE FROM pairing_codes WHERE user_id=?::uuid OR expires_at < ?", userId, ctx.now())
        repeat(5) {
            val code = Crypto.sixDigits()
            val ok = sql.query(
                "INSERT INTO pairing_codes (code_hash, user_id, expires_at) VALUES (?,?::uuid,?) ON CONFLICT DO NOTHING RETURNING 1",
                hash("pair:$code"), userId, expires,
            )
            if (ok.isNotEmpty()) return@run PairingCodeRes(code, expires.toString())
        }
        throw AppError(503, ErrorCode.INTERNAL, "Try again")
    }

    suspend fun redeemPairingCode(code: String, device: DeviceInfo): SessionRes = ctx.db.tx { sql ->
        val user = sql.one(
            """UPDATE pairing_codes pc SET used_at=?
                 FROM users u
                WHERE pc.code_hash=? AND pc.used_at IS NULL AND pc.expires_at > ? AND u.id = pc.user_id
                RETURNING u.*""",
            ctx.now(), hash("pair:$code"), ctx.now(),
        ) ?: throw AppError(400, ErrorCode.INVALID_CODE, "Code is wrong or has expired")
        createSession(sql, user, device)
    }
}
