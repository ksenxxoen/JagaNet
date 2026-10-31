package dev.jaganet.server.services

import dev.jaganet.api.Protocols
import dev.jaganet.api.Vless
import dev.jaganet.api.VlessKey
import dev.jaganet.api.i18n.Lang
import dev.jaganet.server.Ctx
import dev.jaganet.server.db.Row
import dev.jaganet.server.db.Sql
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.util.UUID

/** devices.kind of the user's VLESS key (V18). Ordinary devices and key links are 'device'. */
const val KIND_VLESS_BONUS = "vless_bonus"

/** What one sync did for a user. */
enum class VlessSync { NONE, CREATED, ENABLED, DISABLED, MOVED }

data class VlessSyncResult(val created: Int = 0, val enabled: Int = 0, val disabled: Int = 0, val moved: Int = 0, val failed: Int = 0)

/**
 * A VLESS key's links for one user, for the key page, the subscription and the website.
 * [locations]: every node the key works on, in the subscription's order (issue #41); [links] are
 * theirs one after another, and [name] is the first location's.
 */
data class VlessLinks(
    val userId: String, val deviceId: String, val token: String, val name: String, val countryCode: String?, val links: List<String>, val expiresAt: String?,
    val locations: List<VlessLocation> = emptyList(),
)

/**
 * One VLESS key per user, on top of the plan's devices (issue #33).
 *
 * The key is a system device (`devices.kind = 'vless_bonus'`) with a tunnel on a VLESS node. The
 * device limit counts only `kind = 'device'` ([Entitlements.activeTunnelCount]), and users can't
 * delete it. Its UUID and link token are kept in `vless_keys`, so the UUID never changes: the
 * tunnel is dropped while the user has no access (the node's agent then removes the UUID, as for
 * AmneziaWG peers) and made again, with the same UUID, when access is back.
 *
 * [sync] is idempotent and runs at startup and every few minutes (Main.kt); [ensureFor] runs for
 * one user when they open their keys, so a purchase shows the key at once.
 *
 * The tunnel's node is the key's home; the same UUID is also on every other listed VLESS node
 * (VlessLocations, issue #41), and the subscription lists them all.
 */
class VlessKeys(private val ctx: Ctx, private val ent: Entitlements, private val tunnels: Tunnels) {
    private val log = LoggerFactory.getLogger("vless-keys")

    /** The same rule as for AmneziaWG keys (Tunnels.provisionFor): free period not over, data not used up. */
    private fun hasAccess(sql: Sql, userId: String): Boolean {
        val e = ent.entitlement(sql, userId)
        if (e.freeEnded) return false
        val limit = e.monthlyDataLimitBytes
        return limit == null || ent.monthlyUsageBytes(sql, userId) < limit
    }

    /** Every user: make, turn on, turn off or move their VLESS key. Safe to run any number of times. */
    suspend fun sync(): VlessSyncResult {
        if (ctx.drivers[Protocols.VLESS] == null) return VlessSyncResult()
        val users = ctx.db.run { sql -> sql.query("SELECT id FROM users ORDER BY created_at, id").map { it.str("id") } }
        var r = VlessSyncResult()
        // Every VLESS tunnel's UUID on every listed node: nodes switched on, off or hidden since the last run.
        runCatching { ctx.db.tx { sql -> VlessLocations.syncReplicas(ctx, sql) } }.onFailure { log.warn("VLESS replicas: {}", it.message) }
        for (u in users) {
            r = runCatching { ensureFor(u) }.fold(
                { when (it) {
                    VlessSync.NONE -> r
                    VlessSync.CREATED -> r.copy(created = r.created + 1)
                    VlessSync.ENABLED -> r.copy(enabled = r.enabled + 1)
                    VlessSync.DISABLED -> r.copy(disabled = r.disabled + 1)
                    VlessSync.MOVED -> r.copy(moved = r.moved + 1)
                } },
                { e -> log.warn("VLESS key for {}: {}", u, e.message); r.copy(failed = r.failed + 1) },
            )
        }
        return r
    }

    /** One user's key, as [sync] does it. */
    suspend fun ensureFor(userId: String): VlessSync {
        if (ctx.drivers[Protocols.VLESS] == null) return VlessSync.NONE
        return ctx.db.tx { sql ->
            // One at a time per user: the timer and a request may meet here.
            sql.one("SELECT id FROM users WHERE id=?::uuid FOR UPDATE", userId) ?: return@tx VlessSync.NONE
            val access = hasAccess(sql, userId)
            val k = sql.one("SELECT * FROM vless_keys WHERE user_id=?::uuid", userId)
            if (k == null) {
                if (!access) return@tx VlessSync.NONE
                val server = pick(sql, null) ?: return@tx VlessSync.NONE
                val deviceId = sql.one(
                    "INSERT INTO devices (user_id, name, platform, kind) VALUES (?::uuid, 'VLESS', 'other', ?) RETURNING id",
                    userId, KIND_VLESS_BONUS,
                )!!.str("id")
                val uuid = UUID.randomUUID().toString()
                sql.exec("INSERT INTO vless_keys (user_id, device_id, uuid, link_token) VALUES (?::uuid, ?::uuid, ?, ?)", userId, deviceId, uuid, Crypto.newToken())
                addTunnel(sql, deviceId, uuid, server)
                VlessLocations.syncReplicas(ctx, sql, deviceId)
                return@tx VlessSync.CREATED
            }
            val deviceId = k.str("device_id")
            val t = sql.one("SELECT * FROM tunnels WHERE device_id=?::uuid FOR UPDATE", deviceId)
            if (!access) {
                if (t == null) return@tx VlessSync.NONE
                tunnels.revoke(sql, deviceId)
                return@tx VlessSync.DISABLED
            }
            if (t == null) {
                val server = pick(sql, null) ?: return@tx VlessSync.NONE
                addTunnel(sql, deviceId, k.str("uuid"), server)
                VlessLocations.syncReplicas(ctx, sql, deviceId)
                return@tx VlessSync.ENABLED
            }
            // Its node was switched off, hidden from subscriptions (or no longer runs VLESS): to another one, same UUID.
            val cur = sql.one("SELECT * FROM servers WHERE id=?", t.str("server_id"))
            if (cur == null || !cur.bool("active") || !cur.bool("in_subscription") || Protocols.VLESS !in cur.json("protocols")) {
                val to = pick(sql, t.str("server_id")) ?: return@tx VlessSync.NONE
                tunnels.revoke(sql, deviceId)
                addTunnel(sql, deviceId, k.str("uuid"), to)
                VlessLocations.syncReplicas(ctx, sql, deviceId)
                return@tx VlessSync.MOVED
            }
            VlessLocations.syncReplicas(ctx, sql, deviceId)
            VlessSync.NONE
        }
    }

    /**
     * The least loaded active VLESS node that reports in (healthy ones first), [except] left out.
     * The node's max_peers is not a cap here: everyone gets the key, VLESS needs no addresses.
     */
    private fun pick(sql: Sql, except: String?): Row? = sql.one(
        """SELECT s.* FROM servers s WHERE $USABLE AND s.in_subscription AND (s.protocols -> ?) IS NOT NULL AND s.id <> coalesce(?, '')
            ORDER BY $STATUS_RANK, (SELECT count(*) FROM tunnels t WHERE t.server_id=s.id)::float / greatest(s.max_peers, 1), s.id LIMIT 1""",
        ctx.now().minus(NODE_OFFLINE), Protocols.VLESS, except,
    )

    private suspend fun addTunnel(sql: Sql, deviceId: String, uuid: String, server: Row) {
        val driver = ctx.drivers[Protocols.VLESS]!!
        val used = sql.query("SELECT address FROM tunnels WHERE server_id=?", server.str("id")).map { it.str("address") }
        val address = Ipam.allocate(server.str("subnet"), used) ?: error("no free address on ${server.str("id")}")
        val cp = JsonObject(mapOf("uuid" to JsonPrimitive(uuid)))
        val added = driver.addPeer(server.node(Protocols.VLESS), deviceId, address, cp)
        sql.exec(
            "INSERT INTO tunnels (device_id, server_id, protocol, address, peer_key, client_params) VALUES (?::uuid,?,?,?,?,?)",
            deviceId, server.str("id"), Protocols.VLESS, address, added.peerKey, cp,
        )
        ctx.nodeHub.changed(server.str("id"))
    }

    /** The user whose key a /k/ token opens: a VLESS key's own token or one of their AmneziaWG key links. */
    suspend fun userByToken(token: String): String? = ctx.db.run { sql ->
        sql.one("SELECT user_id FROM vless_keys WHERE link_token=?", token)?.str("user_id")
            ?: sql.one(
                "SELECT k.user_id FROM access_keys k JOIN devices d ON d.id = k.device_id WHERE k.link_token=? AND d.removed_at IS NULL", token,
            )?.str("user_id")
    }

    /** True when [token] is a VLESS key's own link token. */
    suspend fun isOwnToken(token: String): Boolean = ctx.db.run { it.one("SELECT 1 AS one FROM vless_keys WHERE link_token=?", token) != null }

    /**
     * The user's key with its links now: every location it works on (issue #41). Links are empty
     * while the key is off. Null if they have none. [lang]: the backup links' names.
     */
    suspend fun links(userId: String, lang: Lang = Lang.DEFAULT): VlessLinks? = ctx.db.run { sql ->
        val k = sql.one(
            "SELECT k.* FROM vless_keys k JOIN devices d ON d.id = k.device_id WHERE k.user_id=?::uuid AND d.removed_at IS NULL", userId,
        ) ?: return@run null
        val locations = VlessLocations.of(ctx, sql, k.str("device_id"), lang)
        // While the key is off it is named after the node it would get.
        val home = if (locations.isEmpty()) pick(sql, null) else null
        val name = locations.firstOrNull()?.name ?: home?.let { VlessLocations.displayName(it) } ?: "Boop"
        val cc = if (locations.isEmpty()) home?.str("country_code")?.trim() else locations.first().countryCode
        val e = ent.entitlement(sql, userId)
        VlessLinks(userId, k.str("device_id"), k.str("link_token"), name, cc, locations.flatMap { it.links(lang) }, e.expiresAt, locations)
    }

    /** This month's bytes of the user (all devices: the data limit counts them all): upload and download, as the user sees them. */
    suspend fun usage(userId: String): Pair<Long, Long> = ctx.db.run { sql ->
        val r = sql.one(
            """SELECT coalesce(sum(t.tx_bytes), 0)::bigint AS up, coalesce(sum(t.rx_bytes), 0)::bigint AS down
                 FROM traffic_samples t JOIN devices d ON d.id = t.device_id WHERE d.user_id=?::uuid AND t.bucket >= ?""",
            userId, monthStart(ctx.now()),
        )!!
        r.long("up") to r.long("down")
    }

    /** The user's monthly data limit in bytes, null = none. */
    suspend fun dataLimit(userId: String): Long? = ctx.db.run { sql -> ent.entitlement(sql, userId).monthlyDataLimitBytes }

    /** For the website and the apps (GET /v1/keys with vless in supportedProtocols). */
    suspend fun key(userId: String): VlessKey? {
        val l = links(userId) ?: return null
        val base = "${ctx.cfg.publicUrl.trimEnd('/')}/k/${l.token}"
        return VlessKey(l.deviceId, l.name, l.countryCode, l.links.isNotEmpty(), l.links, "$base/vless", base, "$base/vless/0.png")
    }

    companion object {
        /** "🇫🇮 Финляндия": the flag and the owner's display name, else the country. No protocol names. */
        fun displayName(server: Row): String = VlessLocations.displayName(server)

        /** The flag emoji of an ISO code, null if it is not two letters. */
        fun flag(cc: String): String? {
            val c = cc.trim().uppercase()
            if (c.length != 2 || c.any { it !in 'A'..'Z' }) return null
            return c.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
        }

        /**
         * vless:// links of one location, Vision (TCP) first, then XHTTP, then anything else in the
         * node's order. The first is named after the location, the next ones are its backups
         * ("🇫🇮 Финляндия (запасной)").
         */
        fun linksOf(p: Vless.ServerParams, name: String, lang: Lang = Lang.DEFAULT): List<String> =
            VlessLocation("", name, null, p).links(lang)

        /** A subscription as Happ, v2RayTun, Hiddify and v2rayNG read it: base64 of the links, one per line. */
        fun subscription(links: List<String>): String = VlessSubscription.base64(links)
    }
}
