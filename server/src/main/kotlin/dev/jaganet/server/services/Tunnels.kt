package dev.jaganet.server.services

import dev.jaganet.api.ConnectionEventReq
import dev.jaganet.api.ConnectionEventType
import dev.jaganet.api.Device
import dev.jaganet.api.ErrorCode
import dev.jaganet.api.Platform
import dev.jaganet.api.ServerLocation
import dev.jaganet.api.TunnelConfig
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
            .map { s -> ServerLocation(s.str("id"), s.str("name"), s.str("city"), s.str("country_code"), minOf(1.0, s.int("peers").toDouble() / s.int("max_peers")), usable(s)) }
            .filter { it.protocols.isNotEmpty() }
    }

    suspend fun provision(p: Principal, req: TunnelProvisionReq): TunnelConfig = provisionFor(p.user.id, p.deviceId, req)

    /** Protocol for server-made keys: the most preferred one any active node offers. */
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

    suspend fun rename(p: Principal, id: String, name: String) {
        val n = name.trim()
        if (n.isEmpty() || n.length > 60) throw badRequest("Name must be 1 to 60 characters")
        val rows = ctx.db.run { it.exec("UPDATE devices SET name=? WHERE id=?::uuid AND user_id=?::uuid AND removed_at IS NULL", n, id, p.user.id) }
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
