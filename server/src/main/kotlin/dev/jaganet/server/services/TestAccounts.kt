package dev.jaganet.server.services

import dev.jaganet.api.AssignTestAccountRes
import dev.jaganet.api.KeyActivity
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
private val UUID = Regex("^[0-9a-fA-F-]{36}$")

class TestAccounts(private val ctx: Ctx, private val keys: Keys, private val tunnels: Tunnels) {
    suspend fun list(): List<TestAccount> {
        val rows = ctx.db.run { sql ->
            sql.query(
                """SELECT u.id, u.email, u.note, u.created_at, s.device_limit, s.expires_at
                     FROM users u JOIN subscriptions s ON s.user_id = u.id AND s.source = 'test' AND s.external_id = 'test:' || u.id::text
                    ORDER BY u.created_at, u.id""",
            )
        }
        return rows.map { r ->
            val id = r.str("id")
            val list = keys.list(id)
            val stats = ctx.db.run { sql ->
                sql.query(
                    """SELECT d.id, t.last_seen_at,
                         coalesce((SELECT sum(s.rx_bytes) FROM traffic_samples s WHERE s.device_id = d.id), 0)::bigint AS rx,
                         coalesce((SELECT sum(s.tx_bytes) FROM traffic_samples s WHERE s.device_id = d.id), 0)::bigint AS tx
                       FROM devices d LEFT JOIN tunnels t ON t.device_id = d.id WHERE d.user_id = ?::uuid""",
                    id,
                ).associate { it.str("id") to KeyActivity(it.instantOrNull("last_seen_at")?.toString(), it.long("rx"), it.long("tx")) }
            }
            TestAccount(
                id, r.strOrNull("note") ?: "", r.str("email"), r.int("device_limit"), r.instant("expires_at").toString(),
                list.map { it.pageUrl }, r.instant("created_at").toString(),
                list.mapNotNull { k -> keys.config(id, k.id)?.let { Keys.vpnLink(it.text) } },
                list.map { stats[it.id] ?: KeyActivity() },
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
        for ((i, a) in req.accounts.withIndex()) {
            val email = "test-" + Crypto.newToken().filter { it.isLetterOrDigit() }.lowercase().take(8) + "@jaganet.invalid"
            // A microsecond apart, so the list keeps the order they were given in.
            val now = ctx.now().plusNanos(i * 1000L)
            val id = ctx.db.tx { sql ->
                val id = sql.one(
                    "INSERT INTO users (email, referral_code, note, created_at) VALUES (?,?,?,?) RETURNING id",
                    email, Crypto.referralCode(email), a.name.trim(), now,
                )!!.str("id")
                sql.exec(
                    """INSERT INTO subscriptions (user_id, product_id, source, external_id, started_at, expires_at, auto_renew, tariff_name, device_limit, created_at)
                       VALUES (?::uuid,'test','test',?,?,?,false,'Тест',?,?)""",
                    id, "test:$id", now, now.plus(Duration.ofDays(req.days.toLong())), a.devices, now,
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

    /**
     * Gives the account to a registered user: its keys (same links, same VPN peers, so nothing
     * changes for the person using them) and its test period become theirs; the account is removed.
     */
    suspend fun assign(id: String, email: String): AssignTestAccountRes = ctx.db.tx { sql ->
        if (!UUID.matches(id)) throw notFound("Account not found")
        sql.one("SELECT 1 FROM subscriptions WHERE user_id=?::uuid AND source='test' AND external_id='test:'||user_id::text FOR UPDATE", id) ?: throw notFound("Account not found")
        val to = sql.one("SELECT id, email FROM users WHERE lower(email)=lower(?)", email.trim())
            ?: throw notFound("No user with this e-mail")
        val toId = to.str("id")
        if (toId == id || sql.one("SELECT 1 FROM subscriptions WHERE user_id=?::uuid AND source='test' AND external_id='test:'||user_id::text", toId) != null) {
            throw badRequest("Choose a registered user, not a test account")
        }
        // Their keys go on after the user's own: VPN key, VPN key 2...
        var n = sql.one("SELECT count(*)::int AS n FROM access_keys WHERE user_id=?::uuid", toId)!!.int("n")
        val moved = sql.query("SELECT device_id FROM access_keys WHERE user_id=?::uuid ORDER BY created_at, device_id", id)
        for (k in moved) {
            n++
            sql.exec("UPDATE devices SET name=? WHERE id=?::uuid", if (n == 1) "VPN key" else "VPN key $n", k.str("device_id"))
        }
        sql.exec("UPDATE devices SET user_id=?::uuid WHERE user_id=?::uuid", toId, id)
        sql.exec("UPDATE access_keys SET user_id=?::uuid WHERE user_id=?::uuid", toId, id)
        sql.exec("UPDATE subscriptions SET user_id=?::uuid WHERE user_id=?::uuid", toId, id)
        sql.exec("DELETE FROM users WHERE id=?::uuid AND role='user'", id)
        AssignTestAccountRes(to.str("email"), moved.size)
    }

    /** Removes the account, its keys (from the VPN too) and its data. */
    suspend fun delete(id: String) {
        val ok = ctx.db.run { it.one("SELECT 1 FROM subscriptions WHERE user_id=?::uuid AND source='test' AND external_id='test:'||user_id::text", id) } != null
        if (!ok) throw notFound("Account not found")
        for (k in keys.list(id)) keys.remove(id, k.id)
        ctx.db.run { it.exec("DELETE FROM users WHERE id=?::uuid AND role='user'", id) }
    }
}
