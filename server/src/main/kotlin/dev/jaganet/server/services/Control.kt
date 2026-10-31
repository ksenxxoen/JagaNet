package dev.jaganet.server.services

import dev.jaganet.api.MoneyAmount
import dev.jaganet.api.PlanId
import dev.jaganet.api.Protocols
import dev.jaganet.server.Ctx
import dev.jaganet.server.badRequest
import dev.jaganet.server.db.Jsonb
import dev.jaganet.server.db.Row
import dev.jaganet.server.db.Sql
import dev.jaganet.server.notFound
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
private val DIGITS = Regex("^\\d{3,20}$")
private val TOKEN = Regex("^[A-Za-z0-9_-]{16,128}$")
private val ONLINE = Duration.ofMinutes(3)
private val DAY = Duration.ofDays(1)
/** Sources that are money: everything but invite rewards and test / gift / admin time. */
private const val BOUGHT = "source NOT IN ('referral','test')"
private const val SETTINGS_KEY = "control"
private const val APP_PLATFORMS = "('android','ios','desktop')"

/**
 * #38 adds the user's Telegram username in parallel; whichever of these columns exists is searched
 * and shown. Without one, search by Telegram covers the numeric id only (users.telegram_id, V2).
 */
private val TELEGRAM_USERNAME_COLUMNS = listOf("telegram_username", "tg_username")

/** Who does an admin action (for the log). */
data class Actor(val id: String, val email: String)

/**
 * The admin panel's control over users, payments, subscriptions and connections (issue #39):
 * search and the user card, admin actions (each written to `admin_audit`), the payments table with
 * totals and CSV, and the service dashboard. Owner only (http/ControlApi.kt checks the role).
 */
class Control(
    private val ctx: Ctx,
    private val ent: Entitlements,
    private val tunnels: Tunnels,
    private val keys: Keys,
    private val vlessKeys: VlessKeys,
    private val nodes: Nodes,
) {
    /* ---------------- settings ---------------- */

    suspend fun settings(): CtlSettings = ctx.db.run { sql -> settings(sql) }

    private fun settings(sql: Sql): CtlSettings =
        sql.one("SELECT value FROM settings WHERE key=?", SETTINGS_KEY)?.json("value")?.let { runCatching { Protocols.decode<CtlSettings>(it) }.getOrNull() }
            ?: CtlSettings()

    suspend fun saveSettings(actor: Actor, s: CtlSettings): CtlSettings {
        if (s.sharingThreshold !in 1..100) throw badRequest("Some values are out of range")
        ctx.db.tx { sql ->
            val old = settings(sql)
            sql.exec(
                "INSERT INTO settings (key, value, updated_at) VALUES (?,?,?) ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value, updated_at=EXCLUDED.updated_at",
                SETTINGS_KEY, Jsonb(Protocols.encode(s).toString()), ctx.now(),
            )
            audit(sql, actor, null, "settings", "sharingThreshold" to JsonPrimitive(old.sharingThreshold), "to" to JsonPrimitive(s.sharingThreshold))
        }
        return s
    }

    /* ---------------- "paid until" ---------------- */

    /** The end of bought time: the latest end of an active paid subscription that hasn't ended yet. */
    fun paidUntil(sql: Sql, userId: String, now: Instant = ctx.now()): Instant? =
        sql.one("SELECT max(expires_at) AS e FROM subscriptions WHERE user_id=?::uuid AND status='active' AND $BOUGHT AND expires_at > ?", userId, now)?.instantOrNull("e")

    /** The end of any access (paid, gifts, invite rewards, admin grants), or null when there is none now or later. */
    fun accessUntil(sql: Sql, userId: String, now: Instant = ctx.now()): Instant? =
        sql.one("SELECT max(expires_at) AS e FROM subscriptions WHERE user_id=?::uuid AND status='active' AND expires_at > ?", userId, now)?.instantOrNull("e")

    /**
     * When the current access started: the start of the subscription running now, followed back
     * through subscriptions that ran into it without a gap (renewals stack end to start).
     */
    fun currentStart(sql: Sql, userId: String, now: Instant = ctx.now()): Instant? {
        val subs = sql.query("SELECT started_at, expires_at FROM subscriptions WHERE user_id=?::uuid AND status='active' ORDER BY started_at", userId)
            .map { it.instant("started_at") to it.instant("expires_at") }
        var start = subs.filter { it.first <= now && it.second > now }.minOfOrNull { it.first } ?: return null
        while (true) {
            val earlier = subs.filter { it.first < start && it.second >= start }.minOfOrNull { it.first } ?: return start
            start = earlier
        }
    }

    /* ---------------- search and the user card ---------------- */

    private var usernameColumn: String? = null
    private var usernameChecked = false

    /** The Telegram username column if #38 (or later) added one. */
    private fun usernameColumn(sql: Sql): String? {
        if (!usernameChecked) {
            usernameColumn = TELEGRAM_USERNAME_COLUMNS.firstOrNull { c ->
                sql.one("SELECT 1 AS one FROM information_schema.columns WHERE table_schema = current_schema() AND table_name='users' AND column_name=?", c) != null
            }
            usernameChecked = true
        }
        return usernameColumn
    }

    private fun like(q: String) = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    /**
     * By e-mail (part of it), Telegram id or username, user id, device id, VLESS key UUID, key link
     * token (or the whole link), client IP, or the admin's note. Empty: the newest 50 users.
     */
    suspend fun search(query: String?): CtlUsersRes = ctx.db.run { sql ->
        val q = query?.trim().orEmpty().take(200)
        val threshold = settings(sql).sharingThreshold
        val uname = usernameColumn(sql)
        val found = linkedMapOf<String, String?>()
        fun add(kind: String, sqlText: String, vararg args: Any?) {
            for (r in sql.query(sqlText, *args)) found.putIfAbsent(r.str("id"), kind)
        }
        if (q.isEmpty()) {
            add("", "SELECT id FROM users ORDER BY created_at DESC, id LIMIT 50")
        } else {
            val token = q.substringAfter("/k/", q).substringBefore('/').substringBefore('?').trim()
            if (UUID_RE.matches(q)) {
                val u = q.lowercase()
                add("id", "SELECT id FROM users WHERE id=?::uuid", u)
                add("uuid", "SELECT user_id AS id FROM vless_keys WHERE lower(uuid)=?", u)
                add("uuid", "SELECT d.user_id AS id FROM tunnels t JOIN devices d ON d.id=t.device_id WHERE lower(t.peer_key)=?", u)
                add("id", "SELECT user_id AS id FROM devices WHERE id=?::uuid", u)
            }
            ClientIps.hostOf(q)?.let { ip ->
                add("ip", "SELECT DISTINCT d.user_id AS id FROM client_ips c JOIN devices d ON d.id=c.device_id WHERE c.ip=?", ip)
                add("ip", "SELECT DISTINCT d.user_id AS id FROM tunnels t JOIN devices d ON d.id=t.device_id WHERE t.last_ip=?", ip)
            }
            if (DIGITS.matches(q)) add("telegram", "SELECT id FROM users WHERE telegram_id=?", q.toLong())
            if (TOKEN.matches(token)) {
                add("token", "SELECT user_id AS id FROM vless_keys WHERE link_token=?", token)
                add("token", "SELECT user_id AS id FROM access_keys WHERE link_token=?", token)
            }
            if (uname != null) {
                val name = q.removePrefix("@").removePrefix("https://t.me/")
                if (name.isNotEmpty()) add("telegram", "SELECT id FROM users WHERE $uname ILIKE ? ORDER BY created_at DESC LIMIT 50", like(name))
            }
            add("email", "SELECT id FROM users WHERE email ILIKE ? ORDER BY created_at DESC LIMIT 50", like(q))
            add("note", "SELECT id FROM users WHERE note ILIKE ? ORDER BY created_at DESC LIMIT 20", like(q))
        }
        val now = ctx.now()
        CtlUsersRes(found.entries.take(50).mapNotNull { (id, kind) -> userRow(sql, id, kind?.ifEmpty { null }, threshold, now) }, threshold, uname != null)
    }

    private fun userRow(sql: Sql, id: String, matched: String?, threshold: Int, now: Instant): CtlUserRow? {
        val uname = usernameColumn(sql)
        val u = sql.one("SELECT u.*${if (uname != null) ", u.$uname AS tg_username" else ""} FROM users u WHERE u.id=?::uuid", id) ?: return null
        val e = ent.entitlement(sql, id)
        val owner = u.str("role") == "owner"
        val access = accessUntil(sql, id, now)
        val state = when {
            owner -> "owner"
            e.plan == PlanId.PRO -> "active"
            e.freeEnded -> "free_ended"
            sql.one("SELECT 1 AS one FROM subscriptions WHERE user_id=?::uuid", id) != null -> "expired"
            else -> "free"
        }
        val dev = sql.one(
            """SELECT count(*)::int AS n, count(*) FILTER (WHERE t.last_seen_at > ?)::int AS online
                 FROM devices d LEFT JOIN tunnels t ON t.device_id = d.id WHERE d.user_id=?::uuid AND d.removed_at IS NULL""",
            now.minus(ONLINE), id,
        )!!
        val ips = ips24hCount(sql, id, now)
        return CtlUserRow(
            id = id, email = u.str("email"), telegramId = u.longOrNull("telegram_id"), telegramUsername = u.strOrNull("tg_username"),
            role = u.str("role"), createdAt = u.instant("created_at").toString(), state = state,
            tariffName = if (e.plan == PlanId.PRO && !owner) e.tariffName ?: "Pro" else null,
            accessUntil = if (owner) null else access?.toString() ?: e.expiresAt,
            paidUntil = paidUntil(sql, id, now)?.toString(),
            blocked = u["blocked_at"] != null, devices = dev.int("n"), online = dev.int("online") > 0,
            ips24h = ips, sharing = ips > threshold, note = u.strOrNull("note"), matched = matched,
        )
    }

    private fun ips24hCount(sql: Sql, userId: String, now: Instant): Int = sql.one(
        "SELECT count(DISTINCT c.ip)::int AS n FROM client_ips c JOIN devices d ON d.id = c.device_id WHERE d.user_id=?::uuid AND c.last_seen > ?",
        userId, now.minus(DAY),
    )!!.int("n")

    private fun checkId(id: String) { if (!UUID_RE.matches(id)) throw notFound("User not found") }

    suspend fun card(id: String): CtlUserCard = ctx.db.run { sql ->
        checkId(id)
        val now = ctx.now()
        val threshold = settings(sql).sharingThreshold
        val row = userRow(sql, id, null, threshold, now) ?: throw notFound("User not found")
        val u = sql.one("SELECT * FROM users WHERE id=?::uuid", id)!!
        val e = ent.entitlement(sql, id)
        val subs = sql.query(
            """SELECT s.*, o.amount_minor, o.currency FROM subscriptions s LEFT JOIN orders o ON s.external_id = 'order:' || o.id::text
                WHERE s.user_id=?::uuid ORDER BY s.started_at DESC, s.created_at DESC LIMIT 200""",
            id,
        ).map { r -> subscription(r, now) }
        val payments = sql.query(
            "SELECT o.*, u.email FROM orders o JOIN users u ON u.id = o.user_id WHERE o.user_id=?::uuid ORDER BY o.created_at DESC LIMIT 200", id,
        ).map(::payment)
        val ips = sql.query(
            """SELECT c.ip, c.device_id, c.server_id, c.last_seen FROM client_ips c JOIN devices d ON d.id = c.device_id
                WHERE d.user_id=?::uuid AND c.last_seen > ? ORDER BY c.last_seen DESC LIMIT 500""",
            id, now.minus(DAY),
        ).map { CtlIp(it.str("ip"), it.instant("last_seen").toString(), it.str("device_id"), it.strOrNull("server_id")) }
        val devices = devices(sql, id, now, ips, u["blocked_at"] != null)
        val ref = u.strOrNull("referred_by")?.let { rid ->
            sql.one(
                "SELECT u.id, u.email, l.name AS link, l.main FROM users u LEFT JOIN referral_links l ON l.id = ?::uuid WHERE u.id=?::uuid",
                u.strOrNull("referral_link_id"), rid,
            )?.let { CtlReferrer(it.str("id"), it.str("email"), it.strOrNull("link")?.takeUnless { _ -> it["main"] == true }) }
        }
        CtlUserCard(
            user = row, lang = u.strOrNull("lang"), startedAt = currentStart(sql, id, now)?.toString(),
            deviceLimit = e.deviceLimit, monthlyBytes = e.monthlyDataLimitBytes, usageThisMonth = ent.monthlyUsageBytes(sql, id),
            blockedAt = u.instantOrNull("blocked_at")?.toString(), blockedReason = u.strOrNull("blocked_reason"),
            subscriptions = subs, payments = payments, devices = devices, ips24h = ips,
            devicesActive24h = devices.count { d -> d.lastSeenAt?.let { Instant.parse(it) > now.minus(DAY) } == true },
            sharingThreshold = threshold, referrer = ref,
            referrals = sql.one("SELECT count(*)::int AS n FROM users WHERE referred_by=?::uuid", id)!!.int("n"),
            audit = auditRows(sql, id, 100),
            tariffs = sql.query("SELECT id, name, status FROM tariffs WHERE status <> 'archived' ORDER BY sort, created_at").map { CtlTariff(it.str("id"), it.str("name"), it.str("status")) },
        )
    }

    private fun subscription(r: Row, now: Instant): CtlSubscription {
        val ext = r.strOrNull("external_id").orEmpty()
        val source = r.str("source")
        val kind = when {
            ext.startsWith("order:") -> "order"
            ext.startsWith("admin:") -> "admin"
            ext.startsWith("gift:") -> "gift"
            source == "test" -> "test"
            source == "referral" -> "referral"
            source in setOf("apple", "google") -> "store"
            else -> "other"
        }
        val status = r.str("status").let { st ->
            when {
                st != "active" -> st
                r.instant("expires_at") <= now -> "expired"
                r.instant("started_at") > now -> "upcoming"
                else -> "active"
            }
        }
        return CtlSubscription(
            id = r.str("id"), source = source, kind = kind,
            tariffName = r.strOrNull("tariff_name") ?: "Pro".takeIf { source != "referral" },
            startedAt = r.instant("started_at").toString(), expiresAt = r.instant("expires_at").toString(), status = status,
            deviceLimit = r.longOrNull("device_limit")?.toInt(), monthlyBytes = r.longOrNull("monthly_bytes"), renewal = r.bool("is_renewal"),
            amount = r.longOrNull("amount_minor")?.let { MoneyAmount(it, r.str("currency")) },
        )
    }

    private fun payment(r: Row): CtlPayment {
        val terms = r.jsonOrNull("terms")?.get("name")?.let { (it as? JsonPrimitive)?.content }
        return CtlPayment(
            id = r.str("id"), createdAt = r.instant("created_at").toString(), paidAt = r.instantOrNull("paid_at")?.toString(),
            userId = r.str("user_id"), email = r.str("email"), product = terms ?: legacyProduct(r.str("product_id")),
            amount = MoneyAmount(r.long("amount_minor"), r.str("currency")), channel = r.str("channel"), provider = r.str("provider"),
            status = r.str("status"), externalId = r.strOrNull("external_id"),
        )
    }

    private fun legacyProduct(p: String) = if (p == "pro_yearly") "Pro, 12" else "Pro"

    private fun devices(sql: Sql, userId: String, now: Instant, ips: List<CtlIp>, blocked: Boolean): List<CtlDevice> = sql.query(
        """SELECT d.*, t.protocol, t.server_id, t.address, t.last_seen_at AS seen, t.last_ip, t.last_ip_at, t.rekey, t.peer_key,
                  s.name AS server_name, v.uuid, coalesce(v.link_token, k.link_token) AS token, k.device_id AS key_id,
                  (SELECT coalesce(sum(ts.rx_bytes + ts.tx_bytes), 0)::bigint FROM traffic_samples ts WHERE ts.device_id = d.id AND ts.bucket >= ?) AS day,
                  (SELECT coalesce(sum(ts.rx_bytes + ts.tx_bytes), 0)::bigint FROM traffic_samples ts WHERE ts.device_id = d.id) AS total
             FROM devices d LEFT JOIN tunnels t ON t.device_id = d.id LEFT JOIN servers s ON s.id = t.server_id
             LEFT JOIN vless_keys v ON v.device_id = d.id LEFT JOIN access_keys k ON k.device_id = d.id
            WHERE d.user_id=?::uuid AND d.removed_at IS NULL ORDER BY d.kind, d.created_at""",
        now.minus(DAY), userId,
    ).map { r ->
        val seen = r.instantOrNull("seen")
        val kind = when {
            r.str("kind") != "device" -> "vless"
            r["key_id"] != null -> "key"
            else -> "device"
        }
        CtlDevice(
            id = r.str("id"), name = r.str("name"), kind = kind, platform = r.str("platform"), createdAt = r.instant("created_at").toString(),
            protocol = r.strOrNull("protocol"), serverId = r.strOrNull("server_id"), serverName = r.strOrNull("server_name"), address = r.strOrNull("address"),
            lastSeenAt = seen?.toString(), online = seen != null && Duration.between(seen, now) < ONLINE && !blocked,
            lastIp = r.strOrNull("last_ip"), lastIpAt = r.instantOrNull("last_ip_at")?.toString(),
            ips24h = ips.filter { it.deviceId == r.str("id") },
            traffic24h = r.long("day"), trafficTotal = r.long("total"),
            uuid = r.strOrNull("uuid") ?: r.strOrNull("peer_key")?.takeIf { r.strOrNull("protocol") == Protocols.VLESS },
            tokenHint = r.strOrNull("token")?.take(6)?.plus("…"),
            rekeyPending = r["rekey"] == true,
            suspended = blocked || r.strOrNull("protocol") == null,
        )
    }

    /* ---------------- admin actions ---------------- */

    private fun userFor(sql: Sql, id: String): Row {
        checkId(id)
        return sql.one("SELECT * FROM users WHERE id=?::uuid FOR UPDATE", id) ?: throw notFound("User not found")
    }

    private fun days(n: Int) { if (n !in 1..3650) throw badRequest("Enter 1 to 3650 days") }

    private fun comment(c: String?): JsonElement = c?.trim()?.take(500)?.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull

    /**
     * Adds [days] on the user's current terms (their running or last subscription; Pro defaults
     * if they never had one), after any time left. Stored as an admin grant: not counted as a sale.
     */
    suspend fun extend(actor: Actor, userId: String, days: Int, comment: String? = null): CtlUserCard {
        days(days)
        ctx.db.tx { sql ->
            val u = userFor(sql, userId)
            val now = ctx.now()
            val last = sql.one(
                """SELECT * FROM subscriptions WHERE user_id=?::uuid AND status='active' AND source <> 'referral'
                    ORDER BY (started_at <= ? AND expires_at > ?) DESC, expires_at DESC LIMIT 1""",
                userId, now, now,
            )
            val (start, end) = grant(sql, userId, days, now, last?.strOrNull("tariff_id"), last?.strOrNull("tariff_name") ?: "Pro", last?.longOrNull("device_limit"), last?.longOrNull("monthly_bytes"))
            audit(sql, actor, u, "extend", "days" to JsonPrimitive(days), "from" to JsonPrimitive(start.toString()), "until" to JsonPrimitive(end.toString()), "comment" to comment(comment))
        }
        vlessKeys.ensureFor(userId)
        return card(userId)
    }

    /** A free period of [days] on [tariffId]'s terms (Pro defaults without one), after any time left. */
    suspend fun grantFree(actor: Actor, userId: String, days: Int, tariffId: String?, comment: String? = null): CtlUserCard {
        days(days)
        ctx.db.tx { sql ->
            val u = userFor(sql, userId)
            val t = tariffId?.trim()?.takeIf { it.isNotEmpty() }?.let { tariffRow(sql, it) }
            val terms = t?.let(Tariffs::terms)
            val (start, end) = grant(sql, userId, days, ctx.now(), terms?.tariffId, terms?.name ?: "Pro", terms?.deviceLimit?.toLong(), terms?.monthlyBytes)
            audit(
                sql, actor, u, "grant", "days" to JsonPrimitive(days), "tariff" to JsonPrimitive(terms?.name ?: "Pro"),
                "from" to JsonPrimitive(start.toString()), "until" to JsonPrimitive(end.toString()), "comment" to comment(comment),
            )
        }
        vlessKeys.ensureFor(userId)
        return card(userId)
    }

    private fun tariffRow(sql: Sql, id: String): Row =
        (if (UUID_RE.matches(id)) sql.one("SELECT * FROM tariffs WHERE id=?::uuid", id) else null) ?: throw notFound("Tariff not found")

    private fun grant(sql: Sql, userId: String, days: Int, now: Instant, tariffId: String?, name: String, deviceLimit: Long?, monthlyBytes: Long?): Pair<Instant, Instant> {
        val last = sql.one("SELECT max(expires_at) AS e FROM subscriptions WHERE user_id=?::uuid AND status='active'", userId)?.instantOrNull("e")
        val start = maxOf(now, last ?: now)
        val end = start.plus(Duration.ofDays(days.toLong()))
        sql.exec(
            """INSERT INTO subscriptions (user_id, product_id, source, external_id, started_at, expires_at, auto_renew, tariff_id, tariff_name, device_limit, monthly_bytes, created_at)
               VALUES (?::uuid, 'admin', 'test', ?, ?, ?, false, ?::uuid, ?, ?, ?, ?)""",
            userId, "admin:" + UUID.randomUUID(), start, end, tariffId, name, deviceLimit?.toInt(), monthlyBytes, now,
        )
        return start to end
    }

    /**
     * Moves the user to [tariffId]'s devices and data on their current and later subscriptions (the
     * dates stay). The old terms are kept in the log.
     */
    suspend fun changePlan(actor: Actor, userId: String, tariffId: String, comment: String? = null): CtlUserCard {
        ctx.db.tx { sql ->
            val u = userFor(sql, userId)
            val terms = Tariffs.terms(tariffRow(sql, tariffId.trim()))
            val now = ctx.now()
            val old = sql.query(
                "SELECT DISTINCT tariff_name FROM subscriptions WHERE user_id=?::uuid AND status='active' AND source <> 'referral' AND expires_at > ?",
                userId, now,
            ).mapNotNull { it.strOrNull("tariff_name") }
            val n = sql.exec(
                """UPDATE subscriptions SET tariff_id=?::uuid, tariff_name=?, device_limit=?, monthly_bytes=?
                    WHERE user_id=?::uuid AND status='active' AND source <> 'referral' AND expires_at > ?""",
                terms.tariffId, terms.name, terms.deviceLimit, terms.monthlyBytes, userId, now,
            )
            if (n == 0) throw badRequest("No subscription to change, grant a period instead")
            audit(sql, actor, u, "plan", "from" to JsonPrimitive(old.joinToString(", ").ifEmpty { "Pro" }), "to" to JsonPrimitive(terms.name), "comment" to comment(comment))
        }
        vlessKeys.ensureFor(userId)
        return card(userId)
    }

    /**
     * Blocks the user: the API refuses their sessions, their peers leave every node (remote agents
     * through their peer lists, this machine's own interface directly) and the VLESS key turns off.
     * Keys, devices and subscriptions stay, so unblocking brings everything back.
     */
    suspend fun block(actor: Actor, userId: String, reason: String?): CtlUserCard {
        val peers = ctx.db.tx { sql ->
            val u = userFor(sql, userId)
            if (u.str("role") == "owner") throw badRequest("The owner can't be blocked")
            if (u["blocked_at"] != null) throw badRequest("Already blocked")
            val why = reason?.trim()?.take(500)?.takeIf { it.isNotEmpty() }
            sql.exec("UPDATE users SET blocked_at=?, blocked_reason=? WHERE id=?::uuid", ctx.now(), why, userId)
            audit(sql, actor, u, "block", "reason" to (why?.let(::JsonPrimitive) ?: JsonNull))
            userPeers(sql, userId)
        }
        for ((server, t) in peers) {
            ctx.nodeHub.changed(server.str("id"))
            val proto = t.str("protocol")
            if (server.strOrNull("agent_token_hash") == null) runCatching { ctx.drivers[proto]?.removePeer(server.node(proto), t.str("peer_key")) }
        }
        vlessKeys.ensureFor(userId)
        return card(userId)
    }

    suspend fun unblock(actor: Actor, userId: String): CtlUserCard {
        val peers = ctx.db.tx { sql ->
            val u = userFor(sql, userId)
            if (u["blocked_at"] == null) throw badRequest("Not blocked")
            sql.exec("UPDATE users SET blocked_at=NULL, blocked_reason=NULL WHERE id=?::uuid", userId)
            audit(sql, actor, u, "unblock", "since" to JsonPrimitive(u.instant("blocked_at").toString()))
            userPeers(sql, userId)
        }
        for ((server, t) in peers) {
            ctx.nodeHub.changed(server.str("id"))
            val proto = t.str("protocol")
            if (server.strOrNull("agent_token_hash") == null) {
                runCatching { ctx.drivers[proto]?.addPeer(server.node(proto), t.str("device_id"), t.str("address"), t.json("client_params")) }
            }
        }
        vlessKeys.ensureFor(userId)
        return card(userId)
    }

    private fun userPeers(sql: Sql, userId: String): List<Pair<Row, Row>> = sql.query(
        "SELECT t.* FROM tunnels t JOIN devices d ON d.id = t.device_id WHERE d.user_id=?::uuid", userId,
    ).mapNotNull { t -> sql.one("SELECT * FROM servers WHERE id=?", t.str("server_id"))?.let { it to t } }

    suspend fun note(actor: Actor, userId: String, note: String?): CtlUserCard {
        val text = note?.trim()?.takeIf { it.isNotEmpty() }
        if (text != null && text.length > 2000) throw badRequest("The note is too long")
        ctx.db.tx { sql ->
            val u = userFor(sql, userId)
            sql.exec("UPDATE users SET note=? WHERE id=?::uuid", text, userId)
            audit(sql, actor, u, "note", "from" to (u.strOrNull("note")?.let(::JsonPrimitive) ?: JsonNull), "to" to (text?.let(::JsonPrimitive) ?: JsonNull))
        }
        return card(userId)
    }

    private suspend fun device(userId: String, deviceId: String): Row {
        checkId(userId)
        if (!UUID_RE.matches(deviceId)) throw notFound("Device not found")
        return ctx.db.run { sql ->
            sql.one(
                """SELECT d.*, k.device_id AS key_id, t.peer_key, t.protocol FROM devices d LEFT JOIN access_keys k ON k.device_id = d.id
                     LEFT JOIN tunnels t ON t.device_id = d.id WHERE d.id=?::uuid AND d.user_id=?::uuid AND d.removed_at IS NULL""",
                deviceId, userId,
            )
        } ?: throw notFound("Device not found")
    }

    /** Removes a device or key: its peer leaves the node and its sessions end. The VLESS key can't be removed (it comes with access). */
    suspend fun deleteDevice(actor: Actor, userId: String, deviceId: String): CtlUserCard {
        val d = device(userId, deviceId)
        if (d.str("kind") != "device") throw badRequest("The VLESS key comes with access, reissue it instead")
        tunnels.removeFor(userId, deviceId)
        ctx.db.tx { sql -> audit(sql, actor, userFor(sql, userId), "delete_device", "device" to JsonPrimitive(d.str("name")), "deviceId" to JsonPrimitive(deviceId)) }
        return card(userId)
    }

    /**
     * A new key: VLESS key: new UUID and link; key for other apps: new key pair and link; both
     * replace the old one on the node at once. A Boop app makes its own keys: it is asked for a new
     * pair (its old peer goes when it provisions the new one).
     */
    suspend fun reissue(actor: Actor, userId: String, deviceId: String): CtlReissueRes {
        val d = device(userId, deviceId)
        val old = d.strOrNull("peer_key")?.take(8)
        val res = when {
            d.str("kind") != "device" -> CtlReissueRes("vless", vlessKeys.reissue(userId))
            d["key_id"] != null -> { keys.reissue(userId, deviceId); CtlReissueRes("key") }
            d.str("platform") in setOf("android", "ios", "desktop") -> { tunnels.requestRekey(deviceId); CtlReissueRes("app") }
            else -> throw badRequest("This device has no key to reissue")
        }
        ctx.db.tx { sql ->
            audit(
                sql, actor, userFor(sql, userId), "reissue", "device" to JsonPrimitive(d.str("name")), "deviceId" to JsonPrimitive(deviceId),
                "mode" to JsonPrimitive(res.mode), "old" to (old?.let { JsonPrimitive("$it…") } ?: JsonNull),
            )
        }
        return res
    }

    /* ---------------- the log ---------------- */

    private fun audit(sql: Sql, actor: Actor, user: Row?, action: String, vararg details: Pair<String, JsonElement>) {
        sql.exec(
            "INSERT INTO admin_audit (admin_id, admin_email, user_id, user_email, action, details, at) VALUES (?::uuid,?,?::uuid,?,?,?,?)",
            actor.id, actor.email, user?.str("id"), user?.str("email"), action,
            Jsonb(JsonObject(details.filter { it.second !is JsonNull }.toMap()).toString()), ctx.now(),
        )
    }

    private fun auditRows(sql: Sql, userId: String?, limit: Int): List<CtlAudit> = sql.query(
        "SELECT * FROM admin_audit WHERE (?::uuid IS NULL OR user_id = ?::uuid) ORDER BY at DESC, id DESC LIMIT ?",
        userId, userId, limit,
    ).map {
        CtlAudit(it.long("id"), it.instant("at").toString(), it.str("admin_email"), it.strOrNull("user_id"), it.strOrNull("user_email"), it.str("action"), it.json("details"))
    }

    suspend fun auditLog(userId: String?): CtlAuditRes = ctx.db.run { sql ->
        val u = userId?.trim()?.takeIf { it.isNotEmpty() }?.also { if (!UUID_RE.matches(it)) throw notFound("User not found") }
        CtlAuditRes(auditRows(sql, u, 500))
    }

    /* ---------------- payments ---------------- */

    private fun day(s: String?): LocalDate? = s?.trim()?.takeIf { it.isNotEmpty() }?.let {
        runCatching { LocalDate.parse(it) }.getOrElse { throw badRequest("Dates look like 2026-10-31") }
    }

    data class PaymentFilter(val from: LocalDate?, val to: LocalDate?, val status: String?, val q: String?)

    fun filter(from: String?, to: String?, status: String?, q: String?): PaymentFilter {
        val st = status?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "all" }
        if (st != null && st !in setOf("paid", "pending", "cancelled")) throw badRequest("Unknown status")
        return PaymentFilter(day(from), day(to), st, q?.trim()?.takeIf { it.isNotEmpty() }?.take(200))
    }

    /**
     * Every order with its buyer, what was bought (the tariff as it was), amount, channel, payment
     * service and status. Period by payment day (the order's day while unpaid; UTC, both ends included), newest first.
     */
    suspend fun payments(f: PaymentFilter, limit: Int = 500): CtlPaymentsRes = ctx.db.run { sql ->
        val where = mutableListOf("TRUE")
        val args = mutableListOf<Any?>()
        f.from?.let { where += "coalesce(o.paid_at, o.created_at) >= ?"; args += it.atStartOfDay().toInstant(ZoneOffset.UTC) }
        f.to?.let { where += "coalesce(o.paid_at, o.created_at) < ?"; args += it.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC) }
        f.status?.let { where += "o.status = ?"; args += it }
        f.q?.let { q -> where += "(u.email ILIKE ? OR o.id::text = ? OR o.external_id = ?)"; args += like(q); args += q.lowercase(); args += q }
        val w = where.joinToString(" AND ")
        val rows = sql.query(
            "SELECT o.*, u.email FROM orders o JOIN users u ON u.id = o.user_id WHERE $w ORDER BY coalesce(o.paid_at, o.created_at) DESC, o.id LIMIT ?",
            *args.toTypedArray(), limit + 1,
        )
        val counts = sql.one(
            """SELECT count(*) FILTER (WHERE o.status='paid')::int AS paid, count(*) FILTER (WHERE o.status='pending')::int AS pending,
                      count(*) FILTER (WHERE o.status='cancelled')::int AS cancelled
                 FROM orders o JOIN users u ON u.id = o.user_id WHERE $w""",
            *args.toTypedArray(),
        )!!
        val revenue = sql.query(
            "SELECT o.currency, sum(o.amount_minor)::bigint AS total FROM orders o JOIN users u ON u.id = o.user_id WHERE $w AND o.status='paid' GROUP BY 1 ORDER BY 1",
            *args.toTypedArray(),
        ).map { MoneyAmount(it.long("total"), it.str("currency")) }
        // New payers: the user's first paid order is one of the paid orders in the filter.
        val newPayers = sql.one(
            """SELECT count(DISTINCT o.user_id)::int AS n FROM orders o JOIN users u ON u.id = o.user_id
                WHERE $w AND o.status='paid' AND NOT EXISTS (
                  SELECT 1 FROM orders p WHERE p.user_id = o.user_id AND p.status='paid' AND (p.paid_at < o.paid_at OR (p.paid_at = o.paid_at AND p.id::text < o.id::text)))""",
            *args.toTypedArray(),
        )!!.int("n")
        val now = ctx.now()
        CtlPaymentsRes(
            payments = rows.take(limit).map(::payment),
            totals = CtlTotals(
                revenueToday = revenueSince(sql, now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC)),
                revenueWeek = revenueSince(sql, now.minus(Duration.ofDays(7))),
                revenueMonth = revenueSince(sql, now.minus(Duration.ofDays(30))),
                revenue = revenue, paidCount = counts.int("paid"), pendingCount = counts.int("pending"), cancelledCount = counts.int("cancelled"),
                newPayers = newPayers,
            ),
            truncated = rows.size > limit,
        )
    }

    private fun revenueSince(sql: Sql, from: Instant): List<MoneyAmount> = sql.query(
        "SELECT currency, sum(amount_minor)::bigint AS total FROM orders WHERE status='paid' AND paid_at >= ? GROUP BY 1 ORDER BY 1", from,
    ).map { MoneyAmount(it.long("total"), it.str("currency")) }

    /** The same rows as CSV (UTF-8, comma separated, amounts in major units with a dot), at most 50 000. */
    suspend fun paymentsCsv(f: PaymentFilter): String {
        val list = payments(f, 50_000).payments
        val sb = StringBuilder("id,created_at,paid_at,user_id,email,product,amount,currency,channel,provider,status,external_id\n")
        for (p in list) {
            val amount = "%d.%02d".format(p.amount.minor / 100, p.amount.minor % 100)
            sb.append(listOf(p.id, p.createdAt, p.paidAt.orEmpty(), p.userId, p.email, p.product, amount, p.amount.currency, p.channel, p.provider, p.status, p.externalId.orEmpty())
                .joinToString(",") { csv(it) }).append('\n')
        }
        return sb.toString()
    }

    /* ---------------- dashboard ---------------- */

    suspend fun dashboard(): CtlDashboard {
        val nodeList = nodes.list()
        return ctx.db.run { sql ->
            val now = ctx.now()
            val threshold = settings(sql).sharingThreshold
            val users = sql.one(
                """SELECT count(*)::int AS total, count(*) FILTER (WHERE blocked_at IS NOT NULL)::int AS blocked,
                          count(*) FILTER (WHERE created_at > ?)::int AS d1, count(*) FILTER (WHERE created_at > ?)::int AS d7
                     FROM users""",
                now.minus(DAY), now.minus(Duration.ofDays(7)),
            )!!
            val active = sql.one(
                """SELECT count(DISTINCT s.user_id)::int AS n FROM subscriptions s JOIN users u ON u.id = s.user_id
                    WHERE u.role <> 'owner' AND s.status='active' AND s.started_at <= ? AND s.expires_at > ?""",
                now, now,
            )!!.int("n")
            val expiringIds = sql.query(
                """SELECT s.user_id AS id FROM subscriptions s JOIN users u ON u.id = s.user_id
                    WHERE u.role <> 'owner' AND s.status='active' GROUP BY s.user_id
                   HAVING max(s.expires_at) > ? AND max(s.expires_at) <= ? AND min(s.started_at) <= ? ORDER BY max(s.expires_at)""",
                now, now.plus(Duration.ofDays(3)), now,
            ).map { it.str("id") }
            val online = sql.one(
                """SELECT count(*)::int AS devices, count(DISTINCT d.user_id)::int AS users FROM tunnels t JOIN devices d ON d.id = t.device_id
                    JOIN users u ON u.id = d.user_id WHERE t.last_seen_at > ? AND u.blocked_at IS NULL""",
                now.minus(ONLINE),
            )!!
            val traffic = sql.query(
                "SELECT server_id, coalesce(sum(rx_bytes + tx_bytes), 0)::bigint AS b FROM traffic_samples WHERE bucket >= ? AND server_id IS NOT NULL GROUP BY 1",
                now.minus(DAY),
            ).associate { it.str("server_id") to it.long("b") }
            val agents = sql.query("SELECT id, report->'system'->>'agent' AS agent FROM servers").associate { it.str("id") to it.strOrNull("agent") }
            val servers = nodeList.map { n ->
                CtlServer(
                    id = n.id, name = n.name, countryCode = n.countryCode, protocols = n.protocols, state = Protocols.json.encodeToString(dev.jaganet.api.NodeState.serializer(), n.state).trim('"'),
                    remote = n.remote, peers = n.peers, maxPeers = n.maxPeers, online = n.online, traffic24h = traffic[n.id] ?: 0,
                    lastReportAt = n.lastReportAt, agentVersion = agents[n.id], cpu = n.cpu, rxBps = n.rxBps, txBps = n.txBps,
                )
            }
            val sharingIds = sql.query(
                """SELECT d.user_id AS id, count(DISTINCT c.ip)::int AS n FROM client_ips c JOIN devices d ON d.id = c.device_id
                    WHERE c.last_seen > ? GROUP BY d.user_id HAVING count(DISTINCT c.ip) > ? ORDER BY 2 DESC LIMIT 20""",
                now.minus(DAY), threshold,
            ).map { it.str("id") }
            val monthAgo = now.minus(Duration.ofDays(30))
            CtlDashboard(
                usersTotal = users.int("total"), usersActive = active, usersExpiring3d = expiringIds.size, usersBlocked = users.int("blocked"),
                newUsers24h = users.int("d1"), newUsers7d = users.int("d7"),
                onlineDevices = online.int("devices"), onlineUsers = online.int("users"), servers = servers,
                revenueToday = revenueSince(sql, now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC)),
                revenueWeek = revenueSince(sql, now.minus(Duration.ofDays(7))),
                revenueMonth = revenueSince(sql, monthAgo),
                paidOrdersMonth = sql.one("SELECT count(*)::int AS n FROM orders WHERE status='paid' AND paid_at >= ?", monthAgo)!!.int("n"),
                newPayersMonth = sql.one(
                    "SELECT count(*)::int AS n FROM (SELECT user_id FROM orders WHERE status='paid' GROUP BY user_id HAVING min(paid_at) >= ?) x", monthAgo,
                )!!.int("n"),
                sharingThreshold = threshold,
                sharing = sharingIds.mapNotNull { userRow(sql, it, null, threshold, now) },
                expiring = expiringIds.take(20).mapNotNull { userRow(sql, it, null, threshold, now) },
            )
        }
    }

    companion object {
        /** One CSV field: quoted when it holds a comma, quote or line break; a leading = + - @ is defused for spreadsheets. */
        fun csv(v: String): String {
            val s = if (v.isNotEmpty() && v[0] in "=+-@" && v.toDoubleOrNull() == null) "'$v" else v
            return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        }
    }
}

