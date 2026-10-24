package dev.jaganet.server.services

import dev.jaganet.api.ConnectionEventReq
import dev.jaganet.api.ConnectionEventType
import dev.jaganet.api.Device
import dev.jaganet.api.ErrorCode
import dev.jaganet.api.Platform
import dev.jaganet.api.ServerLocation
import dev.jaganet.api.TunnelConfig
import dev.jaganet.api.TunnelState
import dev.jaganet.api.Protocols
import dev.jaganet.api.TunnelProvisionReq
import dev.jaganet.server.AppError
import dev.jaganet.server.Ctx
import dev.jaganet.server.badRequest
import dev.jaganet.server.db.Row
import dev.jaganet.server.db.Sql
import dev.jaganet.server.notFound
import dev.jaganet.server.protocols.ProtocolDriver
import dev.jaganet.server.protocols.ServerNode
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant

/** A device counts as online if its peer was seen this recently. */
private val ONLINE = Duration.ofMinutes(3)

fun Row.node(protocol: String) = ServerNode(
    str("id"), str("name"), json("protocols")[protocol] as? JsonObject ?: JsonObject(emptyMap()),
    remote = strOrNull("agent_token_hash") != null,
)

/** Active and, for a remote node, reporting in. */
private const val USABLE = "s.active AND (s.agent_token_hash IS NULL OR s.last_report_at > ?)"

class Tunnels(private val ctx: Ctx, private val ent: Entitlements) {
    /**
     * Protocols a node offers AND this control plane has a driver for, in preference
     * order (the PROTOCOLS setting). jsonb does not keep key order, so never rely on it.
     */
    private fun usable(server: Row): List<String> {
        val offered = server.json("protocols").keys
        return ctx.drivers.ids().filter { it in offered }
    }

    private fun driver(protocol: String): ProtocolDriver =
        ctx.drivers[protocol] ?: throw AppError(400, ErrorCode.UNSUPPORTED_PROTOCOL, "Protocol {protocol} is not available", mapOf("protocol" to protocol))

    suspend fun servers(): List<ServerLocation> = ctx.db.run { sql ->
        sql.query("SELECT s.*, (SELECT count(*)::int FROM tunnels t WHERE t.server_id=s.id) AS peers FROM servers s WHERE $USABLE ORDER BY s.name", ctx.now().minus(NODE_OFFLINE))
            .map { s ->
                val host = s.json("protocols").values.firstNotNullOfOrNull { ((it as? JsonObject)?.get("endpoint") as? JsonPrimitive)?.content }?.substringBeforeLast(':')
                ServerLocation(s.str("id"), s.str("name"), s.str("city"), s.str("country_code"), minOf(1.0, s.int("peers").toDouble() / s.int("max_peers")), usable(s), host)
            }
            .filter { it.protocols.isNotEmpty() }
    }

    suspend fun provision(p: Principal, req: TunnelProvisionReq): TunnelConfig = provisionFor(p.user.id, p.deviceId, req)

    /** Protocol for server-made keys: the most preferred one any active node offers. */
    /** Free places for new devices with [protocol] on the servers in use. */
    suspend fun freePlaces(protocol: String): Int = ctx.db.run { sql ->
        sql.one(
            """SELECT coalesce(sum(greatest(s.max_peers - (SELECT count(*) FROM tunnels t WHERE t.server_id=s.id), 0)), 0)::int AS n
                 FROM servers s WHERE $USABLE AND s.protocols -> ? IS NOT NULL""",
            ctx.now().minus(NODE_OFFLINE), protocol,
        )!!.int("n")
    }

    /**
     * Puts every device of this machine's own VPN back into its interface (idempotent). Runs at
     * startup, so peers survive an interface that was rebuilt or reconfigured. Returns how many.
     */
    suspend fun resyncLocalPeers(): Int {
        val rows = ctx.db.run { sql ->
            sql.query(
                """SELECT t.*, s.id AS sid FROM tunnels t JOIN servers s ON s.id = t.server_id
                    WHERE s.agent_token_hash IS NULL""",
            ).map { t -> t to sql.one("SELECT * FROM servers WHERE id=?", t.str("sid"))!! }
        }
        var n = 0
        for ((t, server) in rows) {
            val proto = t.str("protocol")
            val driver = ctx.drivers[proto] ?: continue
            runCatching { driver.addPeer(server.node(proto), t.str("device_id"), t.str("address"), t.json("client_params")) }
                .onSuccess { n++ }
        }
        return n
    }

    /**
     * Moves every device of node [fromId] to node [toId]: a new address there, the peer added
     * there and removed here. Key links then give the new node's settings (devices with key
     * files add their key again; the Boop VPN app follows on its next connect). All or nothing.
     */
    suspend fun moveDevices(fromId: String, toId: String): Int {
        if (fromId == toId) throw badRequest("Choose another node")
        val moved = ctx.db.tx { sql ->
            val from = sql.one("SELECT * FROM servers WHERE id=?", fromId) ?: throw notFound("Node not found")
            val to = sql.one("SELECT * FROM servers WHERE id=?", toId) ?: throw notFound("Node not found")
            val tunnels = sql.query("SELECT * FROM tunnels WHERE server_id=? ORDER BY created_at FOR UPDATE", fromId)
            val used = sql.query("SELECT address FROM tunnels WHERE server_id=?", toId).map { it.str("address") }.toMutableList()
            val free = to.int("max_peers") - used.size
            if (tunnels.size > free) {
                throw AppError(400, ErrorCode.BAD_REQUEST, "Not enough room on {name}: {free} free places, {need} needed", mapOf("name" to to.str("name"), "free" to maxOf(free, 0), "need" to tunnels.size))
            }
            for (proto in tunnels.map { it.str("protocol") }.distinct()) if (proto !in to.json("protocols")) {
                throw AppError(400, ErrorCode.UNSUPPORTED_PROTOCOL, "{server} does not offer {protocol}", mapOf("server" to to.str("name"), "protocol" to proto))
            }
            for (t in tunnels) moveOne(sql, t, from, to, used)
            tunnels.size
        }
        ctx.nodeHub.changed(fromId)
        ctx.nodeHub.changed(toId)
        return moved
    }

    /** One tunnel to node [to]: a new address there, the peer added there and removed from [from]. */
    private suspend fun moveOne(sql: Sql, t: Row, from: Row, to: Row, used: MutableList<String>) {
        val proto = t.str("protocol")
        val driver = driver(proto)
        val address = Ipam.allocate(to.str("subnet"), used) ?: throw AppError(503, ErrorCode.SERVER_FULL, "Server is full")
        used += address
        driver.addPeer(to.node(proto), t.str("device_id"), address, t.json("client_params"))
        runCatching { driver.removePeer(from.node(proto), t.str("peer_key")) }
        sql.exec(
            "UPDATE tunnels SET server_id=?, address=?, last_rx=0, last_tx=0, last_torrent_rx=0, last_torrent_tx=0 WHERE device_id=?::uuid",
            to.str("id"), address, t.str("device_id"),
        )
    }

    /**
     * One device to another node. The Boop apps notice on their next check (GET /tunnel) and
     * switch without disconnecting; key links give the new settings at once.
     */
    suspend fun moveDevice(deviceId: String, toId: String) {
        val fromId = ctx.db.tx { sql ->
            val t = sql.one("SELECT * FROM tunnels WHERE device_id=?::uuid FOR UPDATE", deviceId) ?: throw notFound("No tunnel for this device")
            if (t.str("server_id") == toId) throw badRequest("Choose another node")
            val from = sql.one("SELECT * FROM servers WHERE id=?", t.str("server_id")) ?: throw notFound("Node not found")
            val to = sql.one("SELECT * FROM servers WHERE id=?", toId) ?: throw notFound("Node not found")
            val used = sql.query("SELECT address FROM tunnels WHERE server_id=?", toId).map { it.str("address") }.toMutableList()
            if (used.size >= to.int("max_peers")) throw AppError(503, ErrorCode.SERVER_FULL, "Server is full")
            if (t.str("protocol") !in to.json("protocols")) {
                throw AppError(400, ErrorCode.UNSUPPORTED_PROTOCOL, "{server} does not offer {protocol}", mapOf("server" to to.str("name"), "protocol" to t.str("protocol")))
            }
            moveOne(sql, t, from, to, used)
            from.str("id")
        }
        ctx.nodeHub.changed(fromId)
        ctx.nodeHub.changed(toId)
    }

    /** Asks a device's Boop app for a new key pair: it makes one and provisions again on its next check. */
    suspend fun requestRekey(deviceId: String) = ctx.db.run { sql ->
        val d = sql.one("SELECT d.platform FROM tunnels t JOIN devices d ON d.id = t.device_id WHERE t.device_id=?::uuid", deviceId)
            ?: throw notFound("No tunnel for this device")
        if (d.str("platform") !in setOf("android", "ios", "desktop")) throw badRequest("Only the Boop apps can change their key")
        sql.exec("UPDATE tunnels SET rekey=true WHERE device_id=?::uuid", deviceId)
    }

    /**
     * The device's tunnel with its node's current settings. [TunnelState.version] changes with
     * anything a client must apply (node, address, keys, obfuscation), so the apps can follow
     * moves and key changes while connected.
     */
    suspend fun current(p: Principal): TunnelState = ctx.db.run { sql ->
        val t = sql.one("SELECT * FROM tunnels WHERE device_id=?::uuid", p.deviceId) ?: return@run TunnelState()
        val server = sql.one("SELECT * FROM servers WHERE id=?", t.str("server_id")) ?: return@run TunnelState(rekey = true)
        val proto = t.str("protocol")
        val params = ctx.drivers[proto]?.currentParams(server.node(proto)) ?: return@run TunnelState(rekey = t.bool("rekey"))
        val cfg = TunnelConfig(
            protocol = proto,
            serverId = server.str("id"),
            location = "${server.str("city")}, ${server.str("country_code").trim()}",
            address = t.str("address") + "/32",
            dns = server.strings("dns"),
            mtu = server.int("mtu"),
            params = params,
        )
        val version = java.security.MessageDigest.getInstance("SHA-256")
            .digest((Protocols.encode(cfg).toString() + "|" + t.str("peer_key")).toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
        TunnelState(cfg, version, t.bool("rekey"), server.str("country_code").trim())
    }

    suspend fun defaultProtocol(): String? = servers().flatMap { it.protocols }.toSet().let { offered -> ctx.drivers.ids().firstOrNull { it in offered } }

    suspend fun provisionFor(userId: String, deviceId: String, req: TunnelProvisionReq): TunnelConfig {
        val driver = driver(req.protocol)
        val clientParams = try {
            driver.parseClientParams(req.clientParams)
        } catch (e: IllegalArgumentException) {
            throw badRequest(e.message ?: "Invalid clientParams")
        } catch (e: SerializationException) {
            throw badRequest(e.message ?: "Invalid clientParams")
        }

        return ctx.db.tx { sql ->
            val e = ent.entitlement(sql, userId)
            if (e.freeEnded) throw AppError(402, ErrorCode.FREE_ENDED, "Your free period has ended, choose a plan")
            val limit = e.monthlyDataLimitBytes
            if (limit != null && ent.monthlyUsageBytes(sql, userId) >= limit) throw AppError(403, ErrorCode.DATA_LIMIT, "Monthly data used up")
            if (ent.activeTunnelCount(sql, userId, deviceId) >= e.deviceLimit) {
                throw AppError(403, ErrorCode.DEVICE_LIMIT, "Your plan allows {n} device|Your plan allows {n} devices", mapOf("n" to e.deviceLimit))
            }

            val server = pickServer(sql, req.protocol, req.serverId)
            val existing = sql.one("SELECT * FROM tunnels WHERE device_id=?::uuid FOR UPDATE", deviceId)
            // Allocate before touching the old peer, so a full server leaves the old tunnel intact.
            val address = existing?.takeIf { it.str("server_id") == server.str("id") }?.str("address")
                ?: Ipam.allocate(server.str("subnet"), sql.query("SELECT address FROM tunnels WHERE server_id=?", server.str("id")).map { it.str("address") })
                ?: throw AppError(503, ErrorCode.SERVER_FULL, "Server is full")
            if (existing != null) {
                // Re-provisioning (new key, other protocol or other server): drop the old peer.
                val oldServer = sql.one("SELECT * FROM servers WHERE id=?", existing.str("server_id"))
                val oldProto = existing.str("protocol")
                if (oldServer != null) ctx.drivers[oldProto]?.removePeer(oldServer.node(oldProto), existing.str("peer_key"))
                sql.exec("DELETE FROM tunnels WHERE device_id=?::uuid", deviceId)
                ctx.nodeHub.changed(existing.str("server_id"))
            }

            val added = driver.addPeer(server.node(req.protocol), deviceId, address, clientParams)
            sql.exec(
                "INSERT INTO tunnels (device_id, server_id, protocol, address, peer_key, client_params) VALUES (?::uuid,?,?,?,?,?)",
                deviceId, server.str("id"), req.protocol, address, added.peerKey, clientParams,
            )
            ctx.nodeHub.changed(server.str("id"))
            TunnelConfig(
                protocol = req.protocol,
                serverId = server.str("id"),
                location = "${server.str("city")}, ${server.str("country_code")}",
                address = "$address/32",
                dns = server.strings("dns"),
                mtu = server.int("mtu"),
                params = added.params,
            )
        }
    }

    private fun pickServer(sql: Sql, protocol: String, serverId: String?): Row {
        if (serverId != null) {
            val s = sql.one("SELECT * FROM servers s WHERE s.id=? AND $USABLE", serverId, ctx.now().minus(NODE_OFFLINE)) ?: throw notFound("Server not found")
            if (protocol !in s.json("protocols")) throw AppError(400, ErrorCode.UNSUPPORTED_PROTOCOL, "{server} does not offer {protocol}", mapOf("server" to s.str("name"), "protocol" to protocol))
            return s
        }
        return sql.one(
            """SELECT s.* FROM servers s WHERE $USABLE AND (s.protocols -> ?) IS NOT NULL
                  AND (SELECT count(*) FROM tunnels t WHERE t.server_id=s.id) < s.max_peers
                ORDER BY (SELECT count(*) FROM tunnels t WHERE t.server_id=s.id)::float / s.max_peers LIMIT 1""",
            ctx.now().minus(NODE_OFFLINE), protocol,
        ) ?: throw AppError(503, ErrorCode.SERVER_FULL, "No server offers {protocol}", mapOf("protocol" to protocol))
    }

    suspend fun connectionEvent(p: Principal, ev: ConnectionEventReq) {
        val at = minOf(runCatching { Instant.parse(ev.at) }.getOrElse { throw badRequest("bad timestamp") }, ctx.now())
        val (t, server) = ctx.db.run { sql ->
            val t = sql.one("SELECT * FROM tunnels WHERE device_id=?::uuid", p.deviceId) ?: throw notFound("No tunnel for this device")
            when (ev.type) {
                ConnectionEventType.CONNECTED -> {
                    sql.exec("UPDATE connection_sessions SET ended_at=? WHERE device_id=?::uuid AND ended_at IS NULL", at, p.deviceId)
                    sql.exec("INSERT INTO connection_sessions (device_id, protocol, started_at) VALUES (?::uuid,?,?)", p.deviceId, t.str("protocol"), at)
                }
                ConnectionEventType.DISCONNECTED -> sql.exec(
                    "UPDATE connection_sessions SET ended_at=?, peak_down_bps=? WHERE device_id=?::uuid AND ended_at IS NULL",
                    at, ev.peakDownBps, p.deviceId,
                )
            }
            t to sql.one("SELECT * FROM servers WHERE id=?", t.str("server_id"))
        }
        val proto = t.str("protocol")
        if (server != null) ctx.drivers[proto]?.onConnectionEvent(server.node(proto), t.str("peer_key"), ev.type == ConnectionEventType.CONNECTED)
    }

    suspend fun devices(p: Principal): List<Device> = ctx.db.run { sql ->
        val now = ctx.now()
        sql.query(
            """SELECT d.*, t.protocol, t.address, t.last_seen_at AS tunnel_seen
                 FROM devices d LEFT JOIN tunnels t ON t.device_id=d.id
                WHERE d.user_id=?::uuid AND d.removed_at IS NULL ORDER BY d.created_at""",
            p.user.id,
        ).map { r ->
            val seen = r.instantOrNull("tunnel_seen")
            Device(
                id = r.str("id"),
                name = r.str("name"),
                platform = runCatching { Platform.valueOf(r.str("platform").uppercase()) }.getOrDefault(Platform.OTHER),
                protocol = r.strOrNull("protocol"),
                tunnelAddress = r.strOrNull("address"),
                lastSeenAt = seen?.toString(),
                online = seen != null && Duration.between(seen, now) < ONLINE,
                isCurrent = r.str("id") == p.deviceId,
                createdAt = r.instant("created_at").toString(),
            )
        }
    }

    suspend fun rename(p: Principal, id: String, name: String) = renameFor(p.user.id, id, name)

    /** userId null: the owner renames anyone's device or key. */
    suspend fun renameFor(userId: String?, id: String, name: String) {
        val n = name.trim()
        if (n.isEmpty() || n.length > 60) throw badRequest("Name must be 1 to 60 characters")
        if (!Regex("^[0-9a-fA-F-]{36}$").matches(id)) throw notFound("Device not found")
        val rows = ctx.db.run {
            if (userId == null) it.exec("UPDATE devices SET name=? WHERE id=?::uuid AND removed_at IS NULL", n, id)
            else it.exec("UPDATE devices SET name=? WHERE id=?::uuid AND user_id=?::uuid AND removed_at IS NULL", n, id, userId)
        }
        if (rows == 0) throw notFound("Device not found")
    }

    /** Removing a device revokes its peer and signs it out. */
    suspend fun remove(p: Principal, id: String) = removeFor(p.user.id, id)

    suspend fun removeFor(userId: String, id: String) = ctx.db.tx { sql ->
        sql.one("SELECT 1 FROM devices WHERE id=?::uuid AND user_id=?::uuid AND removed_at IS NULL", id, userId) ?: throw notFound("Device not found")
        revoke(sql, id)
        sql.exec("UPDATE devices SET removed_at=? WHERE id=?::uuid", ctx.now(), id)
        sql.exec("UPDATE sessions SET revoked_at=? WHERE device_id=?::uuid AND revoked_at IS NULL", ctx.now(), id)
    }

    suspend fun revoke(sql: Sql, deviceId: String) {
        val t = sql.one("DELETE FROM tunnels WHERE device_id=?::uuid RETURNING *", deviceId) ?: return
        ctx.nodeHub.changed(t.str("server_id"))
        val server = sql.one("SELECT * FROM servers WHERE id=?", t.str("server_id")) ?: return
        val proto = t.str("protocol")
        ctx.drivers[proto]?.removePeer(server.node(proto), t.str("peer_key"))
    }
}
