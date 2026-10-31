package dev.jaganet.server.services

import dev.jaganet.api.ConnectionEventReq
import dev.jaganet.api.ConnectionEventType
import dev.jaganet.api.ConnectionFailure
import dev.jaganet.api.ConnectionFailuresRes
import dev.jaganet.api.NodeFailureCount
import dev.jaganet.api.Device
import dev.jaganet.api.ErrorCode
import dev.jaganet.api.PingTarget
import dev.jaganet.api.Platform
import dev.jaganet.api.ServerLocation
import dev.jaganet.api.Vless
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

/** Failure reports: one per device per [FAILURE_EVERY], kept [FAILURE_KEEP], counted per node over [FAILURE_RECENT]. */
private val FAILURE_EVERY = Duration.ofMinutes(1)
private val FAILURE_KEEP = Duration.ofDays(30)
private val FAILURE_RECENT = Duration.ofMinutes(15)
private val FAILURE_TYPES = mapOf(ConnectionEventType.HANDSHAKE_FAILED to "handshake_failed", ConnectionEventType.NO_RX to "no_rx")
private val NETWORKS = setOf("wifi", "ethernet", "mobile", "unknown")
private val ID_TEXT = Regex("^[0-9a-fA-F-]{36}$")

/** Trimmed, cut to [max] characters, null when empty. */
private fun String?.clean(max: Int) = this?.trim()?.take(max)?.takeIf { it.isNotEmpty() }

/**
 * The owner's node status as a sort key for the server's own choice (no node asked for):
 * ok first, then degraded; blocked and maintenance only when nothing else is left.
 */
internal const val STATUS_RANK = "CASE s.status WHEN 'ok' THEN 0 WHEN 'degraded' THEN 1 ELSE 2 END"

/** Active and, for a remote node, reporting in. */
internal const val USABLE = "s.active AND (s.agent_token_hash IS NULL OR s.last_report_at > ?)"

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

    /**
     * The nodes for the apps. [supported]: the protocols the asking app runs (its
     * `supportedProtocols`); null (apps from before VLESS) = everything but [Protocols.OPT_IN].
     * A node left with no protocol for the app is not listed at all. VLESS is left out on nodes the
     * owner hid from subscriptions (an exit only for the cascade, issue #41). In the owner's order.
     */
    suspend fun servers(supported: Set<String>? = null): List<ServerLocation> = ctx.db.run { sql ->
        sql.query("SELECT s.*, (SELECT count(*)::int FROM tunnels t WHERE t.server_id=s.id) AS peers FROM servers s WHERE $USABLE ORDER BY s.sort_order, s.name", ctx.now().minus(NODE_OFFLINE))
            .map { s ->
                val offered = usable(s).filter { if (supported == null) it !in Protocols.OPT_IN else it in supported }
                    .filter { it != Protocols.VLESS || s.bool("in_subscription") }
                val ping = offered.firstOrNull()?.let { pingTarget(s, it) }
                val host = ping?.host ?: s.json("protocols").values.firstNotNullOfOrNull { ((it as? JsonObject)?.get("endpoint") as? JsonPrimitive)?.content }?.substringBeforeLast(':')
                val cc = s.str("country_code").trim()
                ServerLocation(
                    s.str("id"), s.str("name"), s.str("city"), cc, minOf(1.0, s.int("peers").toDouble() / s.int("max_peers")), offered, host,
                    status = s.str("status"), notice = s.strOrNull("notice"),
                    countryName = if (Protocols.VLESS in offered && VlessLocations.isBalancingEntry(ctx, s)) VlessLocations.entryLabel(s)
                    else s.strOrNull("display_name") ?: s.strOrNull("country_name") ?: Countries.ru(cc),
                    ping = ping,
                )
            }
            .filter { it.protocols.isNotEmpty() }
    }

    /** Where the apps measure the ping: TCP to the first transport for VLESS, ICMP to the endpoint host otherwise. */
    private fun pingTarget(s: Row, protocol: String): PingTarget? {
        val o = s.json("protocols")[protocol] as? JsonObject ?: return null
        if (protocol == Protocols.VLESS) {
            val v = runCatching { Protocols.decode<Vless.NodeSettings>(o) }.getOrNull() ?: return null
            val t = v.transports.firstOrNull() ?: return null
            return PingTarget(t.host ?: v.host, t.port, PingTarget.TCP)
        }
        val endpoint = (o["endpoint"] as? JsonPrimitive)?.content ?: return null
        return PingTarget(endpoint.substringBeforeLast(':'), endpoint.substringAfterLast(':').toIntOrNull(), PingTarget.ICMP)
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
        // The UUID was on [to] as a replica already, and now goes on [from] as one.
        if (proto == Protocols.VLESS) VlessLocations.syncReplicas(ctx, sql, t.str("device_id"))
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
        val params = ctx.drivers[proto]?.currentParams(server.node(proto), t.str("peer_key")) ?: return@run TunnelState(rekey = t.bool("rekey"))
        val cfg = TunnelConfig(
            protocol = proto,
            serverId = server.str("id"),
            location = "${server.str("city")}, ${server.str("country_code").trim()}",
            address = t.str("address") + "/32",
            dns = server.strings("dns"),
            mtu = server.int("mtu"),
            params = params,
        ).withEndpointIp(publicIp(server, proto))
        // The note is left out of the version: a new note is nothing the tunnel has to apply.
        val version = java.security.MessageDigest.getInstance("SHA-256")
            .digest((Protocols.encode(cfg).toString() + "|" + t.str("peer_key")).toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
        val notice = server.strOrNull("notice")
        TunnelState(cfg.copy(notice = notice), version, t.bool("rekey"), server.str("country_code").trim(), notice)
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
                VlessLocations.replicaServers(sql, deviceId).forEach { ctx.nodeHub.changed(it) }
                sql.exec("DELETE FROM tunnels WHERE device_id=?::uuid", deviceId)
                ctx.nodeHub.changed(existing.str("server_id"))
            }

            val added = driver.addPeer(server.node(req.protocol), deviceId, address, clientParams)
            sql.exec(
                "INSERT INTO tunnels (device_id, server_id, protocol, address, peer_key, client_params) VALUES (?::uuid,?,?,?,?,?)",
                deviceId, server.str("id"), req.protocol, address, added.peerKey, clientParams,
            )
            ctx.nodeHub.changed(server.str("id"))
            // The same UUID on every other listed VLESS node (issue #41).
            if (req.protocol == Protocols.VLESS) VlessLocations.syncReplicas(ctx, sql, deviceId)
            TunnelConfig(
                protocol = req.protocol,
                serverId = server.str("id"),
                location = "${server.str("city")}, ${server.str("country_code")}",
                address = "$address/32",
                dns = server.strings("dns"),
                mtu = server.int("mtu"),
                params = added.params,
                notice = server.strOrNull("notice"),
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
                  AND (s.in_subscription OR ? <> 'vless')
                  AND (SELECT count(*) FROM tunnels t WHERE t.server_id=s.id) < s.max_peers
                ORDER BY $STATUS_RANK, (SELECT count(*) FROM tunnels t WHERE t.server_id=s.id)::float / s.max_peers LIMIT 1""",
            ctx.now().minus(NODE_OFFLINE), protocol, protocol,
        ) ?: throw AppError(503, ErrorCode.SERVER_FULL, "No server offers {protocol}", mapOf("protocol" to protocol))
    }

    suspend fun connectionEvent(p: Principal, ev: ConnectionEventReq) {
        val at = minOf(runCatching { Instant.parse(ev.at) }.getOrElse { throw badRequest("bad timestamp") }, ctx.now())
        if (ev.type.failure) return connectionFailure(p, ev, at)
        val (t, server) = ctx.db.run { sql ->
            val t = sql.one("SELECT * FROM tunnels WHERE device_id=?::uuid", p.deviceId) ?: throw notFound("No tunnel for this device")
            when (ev.type) {
                ConnectionEventType.HANDSHAKE_FAILED, ConnectionEventType.NO_RX -> error("failures are stored by connectionFailure")
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

    /**
     * A failed attempt (handshake_failed, no_rx): stored for the admin panel, nothing else.
     * Sessions and the node's driver are not touched. At most one per device per minute;
     * the rest are dropped without an error, so an app never retries them. Works without
     * a tunnel too (a device whose key was just removed may still report).
     */
    private suspend fun connectionFailure(p: Principal, ev: ConnectionEventReq, at: Instant) {
        val now = ctx.now()
        // A report kept on the device for a long time is no longer news.
        if (at.isBefore(now.minus(FAILURE_KEEP))) return
        ctx.db.run { sql ->
            val tunnelServer = sql.one("SELECT server_id FROM tunnels WHERE device_id=?::uuid", p.deviceId)?.str("server_id")
            val asked = ev.serverId?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 }
            val serverId = asked?.takeIf { sql.one("SELECT 1 AS one FROM servers WHERE id=?", it) != null } ?: tunnelServer
            sql.exec(
                """INSERT INTO connection_failures (device_id, user_id, server_id, type, reason, endpoint, network, app_version, platform, at, received_at)
                   SELECT ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM connection_failures WHERE device_id=?::uuid AND received_at > ?)""",
                p.deviceId, p.user.id, serverId, FAILURE_TYPES.getValue(ev.type),
                ev.reason.clean(40), ev.endpoint.clean(64), ev.network?.trim()?.lowercase()?.let { if (it in NETWORKS) it else "unknown" },
                ev.appVersion.clean(32), ev.platform.clean(16), at, now,
                p.deviceId, now.minus(FAILURE_EVERY),
            )
        }
    }

    /** Failures older than 30 days go. Runs every hour. */
    suspend fun pruneFailures(): Int = ctx.db.run { it.exec("DELETE FROM connection_failures WHERE at < ?", ctx.now().minus(FAILURE_KEEP)) }

    /** The admin panel's list: the latest 200, by node and/or user (e-mail or id), and every node's count for 15 minutes. */
    suspend fun failures(server: String?, user: String?): ConnectionFailuresRes = ctx.db.run { sql ->
        val where = mutableListOf("TRUE")
        val args = mutableListOf<Any?>()
        server?.trim()?.takeIf { it.isNotEmpty() }?.let { where += "f.server_id = ?"; args += it }
        user?.trim()?.takeIf { it.isNotEmpty() }?.let { q ->
            if (ID_TEXT.matches(q)) { where += "f.user_id = ?::uuid"; args += q } else { where += "u.email ILIKE ?"; args += "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" }
        }
        val list = sql.query(
            """SELECT f.*, u.email, d.name AS device_name, s.name AS server_name
                 FROM connection_failures f
                 LEFT JOIN users u ON u.id = f.user_id
                 LEFT JOIN devices d ON d.id = f.device_id
                 LEFT JOIN servers s ON s.id = f.server_id
                WHERE ${where.joinToString(" AND ")}
                ORDER BY f.at DESC, f.id DESC LIMIT 200""",
            *args.toTypedArray(),
        ).map { r ->
            ConnectionFailure(
                at = r.instant("at").toString(),
                type = FAILURE_TYPES.entries.first { it.value == r.str("type") }.key,
                userId = r.strOrNull("user_id"),
                email = r.strOrNull("email"),
                deviceId = r.str("device_id"),
                deviceName = r.strOrNull("device_name"),
                serverId = r.strOrNull("server_id"),
                serverName = r.strOrNull("server_name"),
                reason = r.strOrNull("reason"),
                endpoint = r.strOrNull("endpoint"),
                network = r.strOrNull("network"),
                appVersion = r.strOrNull("app_version"),
                platform = r.strOrNull("platform"),
            )
        }
        val nodes = sql.query(
            """SELECT s.id, s.name, (SELECT count(*)::int FROM connection_failures f WHERE f.server_id = s.id AND f.at > ?) AS n
                 FROM servers s WHERE s.active ORDER BY s.name""",
            ctx.now().minus(FAILURE_RECENT),
        ).map { NodeFailureCount(it.str("id"), it.str("name"), it.int("n")) }
        ConnectionFailuresRes(list, nodes)
    }

    /** [withVless]: the app runs VLESS (supportedProtocols), so the user's VLESS key is listed too. */
    suspend fun devices(p: Principal, withVless: Boolean = false): List<Device> = ctx.db.run { sql ->
        val now = ctx.now()
        sql.query(
            """SELECT d.*, t.protocol, t.address, t.last_seen_at AS tunnel_seen, t.server_id
                 FROM devices d LEFT JOIN tunnels t ON t.device_id=d.id
                WHERE d.user_id=?::uuid AND d.removed_at IS NULL AND (d.kind = 'device' OR ?) ORDER BY d.created_at""",
            p.user.id, withVless,
        ).map { r ->
            val seen = r.instantOrNull("tunnel_seen")
            val bonus = r.str("kind") != "device"
            val name = if (!bonus) r.str("name")
            else r.strOrNull("server_id")?.let { sql.one("SELECT * FROM servers WHERE id=?", it) }?.let(VlessKeys::displayName) ?: r.str("name")
            Device(
                id = r.str("id"),
                name = name,
                platform = runCatching { Platform.valueOf(r.str("platform").uppercase()) }.getOrDefault(Platform.OTHER),
                protocol = r.strOrNull("protocol"),
                tunnelAddress = r.strOrNull("address"),
                lastSeenAt = seen?.toString(),
                online = seen != null && Duration.between(seen, now) < ONLINE,
                isCurrent = r.str("id") == p.deviceId,
                createdAt = r.instant("created_at").toString(),
                outsideLimit = bonus,
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
            else it.exec("UPDATE devices SET name=? WHERE id=?::uuid AND user_id=?::uuid AND removed_at IS NULL AND kind = 'device'", n, id, userId)
        }
        if (rows == 0) throw notFound("Device not found")
    }

    /** Removing a device revokes its peer and signs it out. */
    suspend fun remove(p: Principal, id: String) = removeFor(p.user.id, id)

    suspend fun removeFor(userId: String, id: String) = ctx.db.tx { sql ->
        val d = sql.one("SELECT kind FROM devices WHERE id=?::uuid AND user_id=?::uuid AND removed_at IS NULL", id, userId) ?: throw notFound("Device not found")
        // The VLESS key comes with access itself (VlessKeys): it is not the user's to delete.
        if (d.str("kind") != "device") throw badRequest("This key comes with your access and can't be deleted")
        revoke(sql, id)
        sql.exec("UPDATE devices SET removed_at=? WHERE id=?::uuid", ctx.now(), id)
        sql.exec("UPDATE sessions SET revoked_at=? WHERE device_id=?::uuid AND revoked_at IS NULL", ctx.now(), id)
    }

    suspend fun revoke(sql: Sql, deviceId: String) {
        // Its VLESS replicas go with the tunnel (ON DELETE CASCADE): their nodes drop the UUID too.
        VlessLocations.replicaServers(sql, deviceId).forEach { ctx.nodeHub.changed(it) }
        val t = sql.one("DELETE FROM tunnels WHERE device_id=?::uuid RETURNING *", deviceId) ?: return
        ctx.nodeHub.changed(t.str("server_id"))
        val server = sql.one("SELECT * FROM servers WHERE id=?", t.str("server_id")) ?: return
        val proto = t.str("protocol")
        ctx.drivers[proto]?.removePeer(server.node(proto), t.str("peer_key"))
    }
}
