package dev.jaganet.server.services

import dev.jaganet.api.Protocols
import dev.jaganet.api.Vless
import dev.jaganet.api.i18n.I18n
import dev.jaganet.api.i18n.Lang
import dev.jaganet.server.Ctx
import dev.jaganet.server.db.Row
import dev.jaganet.server.db.Sql
import java.time.Duration

/** servers.cascade_role (V22): an entry balances its users over the exits (server-side balancer, behind VLESS_BALANCER=1). */
const val CASCADE_ENTRY = "entry"
const val CASCADE_EXIT = "exit"

/** One location of a user's VLESS key: a node, its name for people and the user's parameters there. */
data class VlessLocation(
    val serverId: String,
    /** "🇫🇮 Финляндия", the owner's display name, or "⚡ Авто" for a balancing entry. */
    val name: String,
    val countryCode: String?,
    val params: Vless.ServerParams,
    /** A cascade entry with the server-side balancer: listed first, as "Auto". */
    val entry: Boolean = false,
) {
    /** The location's variants in the apps' order, each with its name: the first is [name], the next are backups. */
    fun named(lang: Lang = Lang.DEFAULT): List<Pair<Vless.Variant, String>> =
        VlessLocations.ordered(params.variants).mapIndexed { i, v -> v to VlessLocations.variantName(name, i, lang) }

    fun links(lang: Lang = Lang.DEFAULT): List<String> = named(lang).map { (v, n) -> Vless.link(params.uuid, v, n) }
}

/**
 * Several VLESS locations in one key (issue #41).
 *
 * The user's UUID lives on every listed VLESS node: the tunnel keeps it on its own node (as
 * before, issue #33), and `vless_replicas` (V22) puts the same UUID on every other active node
 * with `in_subscription`. The node agents get replicas with their peers (Nodes.snapshot), so a
 * new node gets everyone, and a node switched off or hidden loses them. A replica goes with its
 * tunnel (ON DELETE CASCADE): ended access or a removed device drops the UUID everywhere.
 */
object VlessLocations {
    /** Nodes that carry every VLESS user and appear in subscriptions (SQL over `servers s`). */
    const val LISTED = "s.active AND s.in_subscription AND (s.protocols -> 'vless') IS NOT NULL"

    /**
     * A remote node silent for this long (or never installed) is left out of subscriptions. Longer
     * than NODE_OFFLINE on purpose: a subscription is refreshed every few hours, a short outage
     * should not take a location out of it until the next refresh.
     */
    val SILENT: Duration = Duration.ofDays(1)

    /** Vision (TCP) first, then XHTTP, then anything else in the node's order. */
    fun ordered(variants: List<Vless.Variant>): List<Vless.Variant> =
        variants.sortedBy { if (it.transport == Vless.TCP && it.flow == Vless.FLOW_VISION) 0 else if (it.transport == Vless.XHTTP) 1 else 2 }

    /** "🇫🇮 Финляндия", then "🇫🇮 Финляндия (запасной)", "🇫🇮 Финляндия (запасной 2)"… */
    fun variantName(name: String, index: Int, lang: Lang = Lang.DEFAULT): String = when (index) {
        0 -> name
        1 -> I18n.tr(lang, "{name} (backup)", "name" to name)
        else -> I18n.tr(lang, "{name} (backup {n})", "name" to name, "n" to index)
    }

    /** "🇫🇮 Финляндия": the flag and the owner's display name, else the country (the owner's name for it, else the Russian one). */
    fun displayName(server: Row): String {
        val cc = server.str("country_code").trim()
        val label = server.strOrNull("display_name") ?: server.strOrNull("country_name") ?: Countries.ru(cc)
        return listOfNotNull(VlessKeys.flag(cc), label).joinToString(" ")
    }

    /** The name of a balancing entry: the owner's display name, else "⚡ Авто". */
    fun entryName(server: Row, lang: Lang = Lang.DEFAULT): String = server.strOrNull("display_name") ?: ("⚡ " + I18n.tr(lang, "Auto"))

    /** The same for the apps' list, which puts its own flag next to it: no emoji. */
    fun entryLabel(server: Row, lang: Lang = Lang.DEFAULT): String = server.strOrNull("display_name") ?: I18n.tr(lang, "Auto")

    /** Names must differ (Clash and sing-box refuse two proxies with one name): the second "X" becomes "X 2". */
    fun unique(names: List<String>): List<String> {
        val seen = HashMap<String, Int>()
        return names.map { n ->
            val k = (seen[n] ?: 0) + 1
            seen[n] = k
            if (k == 1) n else "$n $k"
        }
    }

    /** True when the server-side balancer is on and this row is a cascade entry. */
    fun isBalancingEntry(ctx: Ctx, server: Row): Boolean = ctx.cfg.vlessBalancer && server.strOrNull("cascade_role") == CASCADE_ENTRY

    /**
     * Makes `vless_replicas` match the rule: every VLESS tunnel's UUID on every other listed node.
     * [deviceId] limits it to one device. Wakes the agents of the nodes whose list changed and
     * returns how many replicas were added or removed. Idempotent.
     */
    fun syncReplicas(ctx: Ctx, sql: Sql, deviceId: String? = null): Int {
        if (ctx.drivers[Protocols.VLESS] == null) return 0
        val added = sql.query(
            """INSERT INTO vless_replicas (device_id, server_id, peer_key)
               SELECT t.device_id, s.id, t.peer_key FROM tunnels t JOIN servers s ON s.id <> t.server_id AND $LISTED
                WHERE t.protocol = 'vless' AND (?::uuid IS NULL OR t.device_id = ?::uuid)
               ON CONFLICT (device_id, server_id) DO UPDATE SET peer_key = EXCLUDED.peer_key
                WHERE vless_replicas.peer_key <> EXCLUDED.peer_key
               RETURNING server_id""",
            deviceId, deviceId,
        ).map { it.str("server_id") }
        val removed = sql.query(
            """DELETE FROM vless_replicas r WHERE (?::uuid IS NULL OR r.device_id = ?::uuid) AND NOT EXISTS (
                 SELECT 1 FROM tunnels t JOIN servers s ON s.id = r.server_id AND s.id <> t.server_id AND $LISTED
                  WHERE t.device_id = r.device_id AND t.protocol = 'vless')
               RETURNING server_id""",
            deviceId, deviceId,
        ).map { it.str("server_id") }
        (added + removed).toSet().forEach { ctx.nodeHub.changed(it) }
        return added.size + removed.size
    }

    /** The nodes where a device's replicas are, for waking their agents before the tunnel goes. */
    fun replicaServers(sql: Sql, deviceId: String): List<String> =
        sql.query("SELECT server_id FROM vless_replicas WHERE device_id=?::uuid", deviceId).map { it.str("server_id") }

    /**
     * The device's VLESS locations for its subscription, in the owner's order (a balancing entry
     * first): its tunnel's node and the nodes with its replica, if listed and not long silent.
     */
    fun of(ctx: Ctx, sql: Sql, deviceId: String, lang: Lang = Lang.DEFAULT): List<VlessLocation> {
        val t = sql.one("SELECT server_id, peer_key FROM tunnels WHERE device_id=?::uuid AND protocol = 'vless'", deviceId) ?: return emptyList()
        val driver = ctx.drivers[Protocols.VLESS] ?: return emptyList()
        val rows = sql.query(
            """SELECT s.* FROM servers s WHERE $LISTED AND (s.agent_token_hash IS NULL OR s.last_report_at > ?)
                 AND (s.id = ? OR EXISTS (SELECT 1 FROM vless_replicas r WHERE r.device_id = ?::uuid AND r.server_id = s.id))
                ORDER BY s.sort_order, s.name, s.id""",
            ctx.now().minus(SILENT), t.str("server_id"), deviceId,
        ).sortedBy { if (isBalancingEntry(ctx, it)) 0 else 1 }
        val found = rows.mapNotNull { s ->
            val params = driver.currentParams(s.node(Protocols.VLESS), t.str("peer_key"))
                ?.let { runCatching { Protocols.decode<Vless.ServerParams>(it) }.getOrNull() }
                ?.takeIf { it.variants.isNotEmpty() } ?: return@mapNotNull null
            val entry = isBalancingEntry(ctx, s)
            VlessLocation(s.str("id"), if (entry) entryName(s, lang) else displayName(s), s.str("country_code").trim().takeIf { !entry }, params, entry)
        }
        return found.zip(unique(found.map { it.name })) { l, n -> l.copy(name = n) }
    }
}
