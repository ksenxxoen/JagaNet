package dev.jaganet.server.services

import dev.jaganet.api.Protocols
import dev.jaganet.api.Vless
import dev.jaganet.api.VlessKey
import dev.jaganet.server.Ctx
import dev.jaganet.server.db.Row
import dev.jaganet.server.db.Sql
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.UUID

/** devices.kind of the user's VLESS key (V18). Ordinary devices and key links are 'device'. */
const val KIND_VLESS_BONUS = "vless_bonus"

/** What one sync did for a user. */
enum class VlessSync { NONE, CREATED, ENABLED, DISABLED, MOVED }

data class VlessSyncResult(val created: Int = 0, val enabled: Int = 0, val disabled: Int = 0, val moved: Int = 0, val failed: Int = 0)

/** A VLESS key's links for one user, for the key page, the subscription and the website. */
data class VlessLinks(val userId: String, val deviceId: String, val token: String, val name: String, val countryCode: String?, val links: List<String>, val expiresAt: String?)

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
                return@tx VlessSync.ENABLED
            }
            // Its node was switched off (or no longer runs VLESS): to another one, same UUID.
            val cur = sql.one("SELECT * FROM servers WHERE id=?", t.str("server_id"))
            if (cur == null || !cur.bool("active") || Protocols.VLESS !in cur.json("protocols")) {
                val to = pick(sql, t.str("server_id")) ?: return@tx VlessSync.NONE
                tunnels.revoke(sql, deviceId)
                addTunnel(sql, deviceId, k.str("uuid"), to)
                return@tx VlessSync.MOVED
            }
            VlessSync.NONE
        }
    }

    /**
     * The least loaded active VLESS node that reports in (healthy ones first), [except] left out.
     * The node's max_peers is not a cap here: everyone gets the key, VLESS needs no addresses.
     */
    private fun pick(sql: Sql, except: String?): Row? = sql.one(
        """SELECT s.* FROM servers s WHERE $USABLE AND (s.protocols -> ?) IS NOT NULL AND s.id <> coalesce(?, '')
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

    /** The user's key with its links now; links are empty while the key is off. Null if they have none. */
    suspend fun links(userId: String): VlessLinks? = ctx.db.run { sql ->
        val k = sql.one(
            "SELECT k.* FROM vless_keys k JOIN devices d ON d.id = k.device_id WHERE k.user_id=?::uuid AND d.removed_at IS NULL", userId,
        ) ?: return@run null
        val t = sql.one("SELECT server_id, peer_key FROM tunnels WHERE device_id=?::uuid", k.str("device_id"))
        val server = t?.let { sql.one("SELECT * FROM servers WHERE id=?", it.str("server_id")) } ?: pick(sql, null)
        val cc = server?.str("country_code")?.trim()
        val name = server?.let { displayName(it) } ?: "Boop"
        val params = if (t != null && server != null) {
            ctx.drivers[Protocols.VLESS]?.currentParams(server.node(Protocols.VLESS), t.str("peer_key"))
                ?.let { runCatching { Protocols.decode<Vless.ServerParams>(it) }.getOrNull() }
        } else null
        val e = ent.entitlement(sql, userId)
        VlessLinks(userId, k.str("device_id"), k.str("link_token"), name, cc, params?.let { linksOf(it, name) }.orEmpty(), e.expiresAt)
    }

    /** For the website and the apps (GET /v1/keys with vless in supportedProtocols). */
    suspend fun key(userId: String): VlessKey? {
        val l = links(userId) ?: return null
        val base = "${ctx.cfg.publicUrl.trimEnd('/')}/k/${l.token}"
        return VlessKey(l.deviceId, l.name, l.countryCode, l.links.isNotEmpty(), l.links, "$base/vless", base, "$base/vless/0.png")
    }

    companion object {
        /** "🇫🇮 Финляндия": the flag and the country (the owner's name for it, else the Russian one). No protocol names. */
        fun displayName(server: Row): String {
            val cc = server.str("country_code").trim()
            val country = server.strOrNull("country_name") ?: Countries.ru(cc)
            return listOfNotNull(flag(cc), country).joinToString(" ")
        }

        /** The flag emoji of an ISO code, null if it is not two letters. */
        fun flag(cc: String): String? {
            val c = cc.trim().uppercase()
            if (c.length != 2 || c.any { it !in 'A'..'Z' }) return null
            return c.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
        }

        /**
         * vless:// links, Vision (TCP) first, then XHTTP, then anything else in the node's order.
         * The first is named after the country, the next ones get a number ("🇫🇮 Финляндия 2").
         */
        fun linksOf(p: Vless.ServerParams, name: String): List<String> =
            p.variants.sortedBy { if (it.transport == Vless.TCP && it.flow == Vless.FLOW_VISION) 0 else if (it.transport == Vless.XHTTP) 1 else 2 }
                .mapIndexed { i, v -> Vless.link(p.uuid, v, if (i == 0) name else "$name ${i + 1}") }

        /** A subscription as Happ, v2RayTun, Hiddify and v2rayNG read it: base64 of the links, one per line. */
        fun subscription(links: List<String>): String = Base64.getEncoder().encodeToString(links.joinToString("\n").toByteArray())
    }
}
