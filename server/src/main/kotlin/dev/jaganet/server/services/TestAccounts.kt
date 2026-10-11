package dev.jaganet.server.services

import dev.jaganet.api.TestAccount
import dev.jaganet.api.TestAccountsReq
import dev.jaganet.api.ErrorCode
import dev.jaganet.server.AppError
import dev.jaganet.server.Ctx
import dev.jaganet.server.badRequest
import dev.jaganet.server.notFound
import java.time.Duration

/**
 * Accounts the owner makes for testers who don't sign in themselves: a made-up address that
 * can't get mail, free access for a number of days and one VPN key link per device.
 */
class TestAccounts(private val ctx: Ctx, private val keys: Keys, private val tunnels: Tunnels) {
    suspend fun list(): List<TestAccount> {
        val rows = ctx.db.run { sql ->
            sql.query(
                """SELECT u.id, u.email, u.note, u.created_at, s.device_limit, s.expires_at
                     FROM users u JOIN subscriptions s ON s.user_id = u.id AND s.source = 'test'
                    ORDER BY u.created_at, u.id""",
            )
        }
        return rows.map { r ->
            val id = r.str("id")
            TestAccount(
                id, r.strOrNull("note") ?: "", r.str("email"), r.int("device_limit"), r.instant("expires_at").toString(),
                keys.list(id).map { it.pageUrl }, r.instant("created_at").toString(),
            )
        }
    }

    /** Makes the accounts in order; stops at the first one that fails (earlier ones stay). */
    suspend fun create(req: TestAccountsReq): List<TestAccount> {
        if (req.accounts.isEmpty() || req.accounts.size > 200) throw badRequest("Add 1 to 200 accounts at a time")
        if (req.days !in 1..365) throw badRequest("The test lasts 1 to 365 days")
        for (a in req.accounts) {
            if (a.name.trim().isEmpty() || a.name.trim().length > 80) throw badRequest("Name must be 1 to 80 characters")
            if (a.devices !in 1..20) throw badRequest("Devices: 1 to 20 per account")
        }
        // All or nothing: check the room first, so a big list doesn't stop half-way.
        val need = req.accounts.sumOf { it.devices }
        val free = tunnels.defaultProtocol()?.let { tunnels.freePlaces(it) } ?: 0
        if (need > free) throw AppError(400, ErrorCode.BAD_REQUEST, "Not enough room on the VPN servers: {free} free places, {need} needed", mapOf("free" to free, "need" to need))
        val made = mutableListOf<String>()
        for (a in req.accounts) {
            val email = "test-" + Crypto.newToken().filter { it.isLetterOrDigit() }.lowercase().take(8) + "@jaganet.invalid"
            val now = ctx.now()
            val id = ctx.db.tx { sql ->
                val id = sql.one(
                    "INSERT INTO users (email, referral_code, note) VALUES (?,?,?) RETURNING id",
                    email, Crypto.referralCode(email), a.name.trim(),
                )!!.str("id")
                sql.exec(
                    """INSERT INTO subscriptions (user_id, product_id, source, external_id, started_at, expires_at, auto_renew, tariff_name, device_limit)
                       VALUES (?::uuid,'test','test',?,?,?,false,'Тест',?)""",
                    id, "test:$id", now, now.plus(Duration.ofDays(req.days.toLong())), a.devices,
                )
                id
            }
            try {
                repeat(a.devices) { keys.create(id) }
            } catch (e: Throwable) {
                runCatching { delete(id) }
                throw e
            }
            made += id
        }
        return list().filter { it.id in made }
    }

    /** Removes the account, its keys (from the VPN too) and its data. */
    suspend fun delete(id: String) {
        val ok = ctx.db.run { it.one("SELECT 1 FROM subscriptions WHERE user_id=?::uuid AND source='test'", id) } != null
        if (!ok) throw notFound("Account not found")
        for (k in keys.list(id)) keys.remove(id, k.id)
        ctx.db.run { it.exec("DELETE FROM users WHERE id=?::uuid AND role='user'", id) }
    }
}
