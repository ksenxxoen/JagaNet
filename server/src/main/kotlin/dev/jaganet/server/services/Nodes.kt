package dev.jaganet.server.services

import dev.jaganet.api.AdminNode
import dev.jaganet.api.NodeInstallRes
import dev.jaganet.api.NodeReq
import dev.jaganet.api.NodeState
import dev.jaganet.api.Protocols
import dev.jaganet.api.ErrorCode
import dev.jaganet.server.AppError
import dev.jaganet.server.Ctx
import dev.jaganet.server.badRequest
import dev.jaganet.server.db.Jsonb
import dev.jaganet.server.db.Row
import dev.jaganet.server.db.Sql
import dev.jaganet.server.notFound
import dev.jaganet.server.protocols.AwgParams
import dev.jaganet.server.protocols.PeerCounters
import dev.jaganet.server.unauthorized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/** A node without a report for this long is offline: it gets no new devices and raises an alert. */
val NODE_OFFLINE: Duration = Duration.ofMinutes(3)

/** Wakes the agents' long polls when a node's peers change. */
class NodeHub {
    private val versions = ConcurrentHashMap<String, MutableStateFlow<Long>>()
    fun flow(serverId: String) = versions.getOrPut(serverId) { MutableStateFlow(0L) }
    fun changed(serverId: String) = flow(serverId).update { it + 1 }
}

/* What the node agent sends and gets (scripts/server/node/agent.py). */

@Serializable
data class NodeRegisterReq(val publicIp: String, val port: Int, val protocol: String)

@Serializable
data class NodeRegisterRes(
    val protocol: String,
    /** The node's identity: the same on every machine this node runs on. */
    val privateKey: String,
    val iface: String,
    /** The node's own tunnel address with prefix, e.g. 10.8.0.1/24. */
    val address: String,
    val subnet: String,
    val port: Int,
    /** AmneziaWG interface lines (Jc, S1, H1…); empty for WireGuard. */
    val obfuscation: Map<String, String> = emptyMap(),
)

@Serializable
data class NodePeer(val publicKey: String, val address: String)

@Serializable
data class NodePeersRes(val version: String, val protocol: String, val iface: String, val peers: List<NodePeer>)

@Serializable
data class NodePeerCounter(val publicKey: String, val rx: Long, val tx: Long, val lastHandshake: Long = 0)

@Serializable
data class NodeSystem(
    val cpu: Double = 0.0,
    val memUsed: Long = 0,
    val memTotal: Long = 0,
    /** Cumulative bytes on the internet interface. */
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val uptime: Long = 0,
)

@Serializable
data class NodeReportReq(val protocol: String, val peers: List<NodePeerCounter>, val system: NodeSystem = NodeSystem())

/** What the agent last said about the machine, plus speeds worked out from the previous report. */
@Serializable
private data class StoredReport(val at: Long, val system: NodeSystem, val rxBps: Long = 0, val txBps: Long = 0)

/** Peer counters for one node and protocol: reported by its agent, or read locally. */
suspend fun readCounters(ctx: Ctx, server: Row, protocol: String): List<PeerCounters> {
    if (server.strOrNull("agent_token_hash") == null) return ctx.drivers[protocol]?.readCounters(server.node(protocol)) ?: emptyList()
    return ctx.db.run { sql ->
        sql.query("SELECT * FROM node_peer_counters WHERE server_id=? AND protocol=?", server.str("id"), protocol).map {
            PeerCounters(it.str("peer_key"), it.long("rx_bytes"), it.long("tx_bytes"), it.instantOrNull("last_handshake"))
        }
    }
}

private const val IFACE_AWG = "awg0"
private const val IFACE_WG = "wg0"
private val HOST = Regex("^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")
private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")

/**
 * VPN nodes (locations) other than this machine. The owner adds one in the admin panel and
 * runs the shown command on the new server; it installs the VPN and an agent. The agent:
 *  - registers once with its token (public key, address) and gets the interface settings,
 *  - keeps a long poll open for the node's peer list and applies changes at once,
 *  - reports peer counters and machine health every minute.
 * The node needs no database and opens no port but the VPN one.
 */
class Nodes(private val ctx: Ctx) {
    private val box by lazy { SecretBox("node-keys:${ctx.cfg.authSecret}") }
    private fun hash(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    fun installCommand(token: String): String {
        val base = ctx.cfg.publicUrl.trimEnd('/')
        return "curl -fsSL $base/node/install.sh | JAGANET_URL=$base NODE_TOKEN=$token bash"
    }

    suspend fun list(): List<AdminNode> = ctx.db.run { sql ->
        val now = ctx.now()
        sql.query(
            """SELECT s.*,
                 (SELECT count(*)::int FROM tunnels t WHERE t.server_id=s.id) AS peers,
                 (SELECT count(*)::int FROM tunnels t WHERE t.server_id=s.id AND t.last_seen_at > ?) AS online
               FROM servers s ORDER BY (s.agent_token_hash IS NOT NULL), s.created_at, s.id""",
            now.minus(NODE_OFFLINE),
        ).map { toAdmin(it, now) }
    }

    private fun toAdmin(r: Row, now: Instant): AdminNode {
        val remote = r.strOrNull("agent_token_hash") != null
        val last = r.instantOrNull("last_report_at")
        val rep = r.jsonOrNull("report")?.let { runCatching { Protocols.decode<StoredReport>(it) }.getOrNull() }
        val protocols = r.json("protocols")
        val state = when {
            !r.bool("active") -> NodeState.DISABLED
            !remote -> NodeState.ONLINE
            protocols.isEmpty() -> NodeState.WAITING
            last != null && Duration.between(last, now) < NODE_OFFLINE -> NodeState.ONLINE
            else -> NodeState.OFFLINE
        }
        fun field(k: String) = protocols.values.firstNotNullOfOrNull { ((it as? JsonObject)?.get(k) as? JsonPrimitive)?.content }
        val endpoint = field("endpoint")
        return AdminNode(
            id = r.str("id"), name = r.str("name"), city = r.str("city"), countryCode = r.str("country_code").trim(),
            remote = remote, state = state, active = r.bool("active"), maxPeers = r.int("max_peers"),
            peers = r.int("peers"), online = r.int("online"), endpoint = endpoint, hostname = r.strOrNull("endpoint_host"),
            publicIp = field("publicIp"), protocols = protocols.keys.toList(),
            lastReportAt = last?.toString(), cpu = rep?.system?.cpu, memUsed = rep?.system?.memUsed, memTotal = rep?.system?.memTotal,
            rxBps = rep?.rxBps, txBps = rep?.txBps, createdAt = r.instant("created_at").toString(),
        )
    }

    private fun check(req: NodeReq): NodeReq {
        val name = req.name.trim(); val city = req.city.trim(); val cc = req.countryCode.trim().uppercase()
        if (name.isEmpty() || name.length > 60 || city.isEmpty() || city.length > 60) throw badRequest("Name must be 1 to 60 characters")
        if (!Regex("^[A-Z]{2}$").matches(cc)) throw badRequest("The country is a two-letter code, like DE")
        if (req.maxPeers !in 1..65_000) throw badRequest("Some values are out of range")
        val host = req.hostname?.trim()?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
        if (host != null && !HOST.matches(host)) throw badRequest("Enter a host name like de1.vpn.example.com")
        return req.copy(name = name, city = city, countryCode = cc, hostname = host)
    }

    /** A new location. It stays out of use until its agent registers. */
    suspend fun create(req: NodeReq): NodeInstallRes {
        val r = check(req)
        val token = Crypto.newToken()
        val id = "node-" + Crypto.newToken().filter { it.isLetterOrDigit() }.lowercase().take(6)
        val (priv, pub) = Keys.newKeyPair()
        // A /24 per node; nodes don't share addresses (each one NATs its own clients), but a
        // larger node needs a larger subnet: /22 above 250 devices.
        val subnet = if (r.maxPeers > 250) "10.8.0.0/22" else "10.8.0.0/24"
        ctx.db.run { sql ->
            sql.exec(
                """INSERT INTO servers (id, name, city, country_code, subnet, protocols, max_peers, active, agent_token_hash, node_key_enc, endpoint_host, created_at)
                   VALUES (?,?,?,?,?,'{}'::jsonb,?,?,?,?,?,?)""",
                id, r.name, r.city, r.countryCode, subnet, r.maxPeers, r.active, hash(token), "$pub:" + box.encrypt(priv), r.hostname, ctx.now(),
            )
        }
        return NodeInstallRes(get(id), installCommand(token))
    }

    suspend fun update(id: String, req: NodeReq): AdminNode {
        val r = check(req)
        val n = ctx.db.run { sql ->
            val current = sql.one("SELECT subnet FROM servers WHERE id=?", id) ?: throw notFound("Node not found")
            val capacity = 1 shl (32 - current.str("subnet").substringAfter('/').toInt())
            if (r.maxPeers > capacity - 3) throw AppError(400, ErrorCode.BAD_REQUEST, "This node has room for at most {n} devices", mapOf("n" to capacity - 3))
            sql.exec(
                "UPDATE servers SET name=?, city=?, country_code=?, max_peers=?, active=?, endpoint_host=? WHERE id=?",
                r.name, r.city, r.countryCode, r.maxPeers, r.active, r.hostname, id,
            ).also { if (it > 0) refreshEndpoint(sql, id) }
        }
        if (n == 0) throw notFound("Node not found")
        return get(id)
    }

    /** The VPN address devices get: the host name when set, else the machine's address. */
    private fun refreshEndpoint(sql: Sql, id: String) {
        val s = sql.one("SELECT * FROM servers WHERE id=?", id) ?: return
        val protocols = s.json("protocols")
        if (protocols.isEmpty()) return
        val updated = JsonObject(protocols.mapValues { (_, v) ->
            val o = v as? JsonObject ?: return@mapValues v
            val ip = (o["publicIp"] as? JsonPrimitive)?.content ?: return@mapValues v
            val port = (o["port"] as? JsonPrimitive)?.content ?: return@mapValues v
            JsonObject(o + ("endpoint" to JsonPrimitive("${s.strOrNull("endpoint_host") ?: ip}:$port")))
        })
        sql.exec("UPDATE servers SET protocols=? WHERE id=?", Jsonb(updated.toString()), id)
    }

    /**
     * A fresh install command (the old token stops working). Also the way to move a node to
     * another machine: run it there, and the node comes back with the same identity.
     */
    suspend fun newToken(id: String): NodeInstallRes {
        val token = Crypto.newToken()
        val n = ctx.db.run { it.exec("UPDATE servers SET agent_token_hash=? WHERE id=? AND agent_token_hash IS NOT NULL", hash(token), id) }
        if (n == 0) throw notFound("Node not found")
        return NodeInstallRes(get(id), installCommand(token))
    }

    private suspend fun get(id: String): AdminNode = list().firstOrNull { it.id == id } ?: throw notFound("Node not found")

    private fun bySql(sql: Sql, token: String?): Row =
        token?.takeIf { it.length in 20..100 }?.let { sql.one("SELECT * FROM servers WHERE agent_token_hash=?", hash(it)) } ?: throw unauthorized()

    /** First contact from the install script: store the node's key and address, hand out its interface settings. */
    suspend fun register(token: String?, req: NodeRegisterReq): NodeRegisterRes {
        if (req.protocol !in setOf(Protocols.AMNEZIAWG, Protocols.WIREGUARD)) throw badRequest("Unknown protocol")
        if (!IPV4.matches(req.publicIp)) throw badRequest("Invalid address")
        if (req.port !in 1..65535) throw badRequest("Port must be 1 to 65535")
        val (res, id) = ctx.db.tx { sql ->
            val s = bySql(sql, token)
            val (pub, enc) = s.str("node_key_enc").split(':', limit = 2)
            val iface = if (req.protocol == Protocols.AMNEZIAWG) IFACE_AWG else IFACE_WG
            // Keep the obfuscation profile across re-installs: clients already have it.
            val old = s.json("protocols")[req.protocol] as? JsonObject
            val obfuscation: Map<String, String> = if (req.protocol != Protocols.AMNEZIAWG) emptyMap()
            else (old?.get("obfuscation") as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content }
                ?: AwgParams.generate()
                    // Only the keys every AmneziaWG version understands, with single-value headers.
                    .filterKeys { it in setOf("Jc", "Jmin", "Jmax", "S1", "S2", "H1", "H2", "H3", "H4") }
                    .mapValues { (k, v) -> if (k.startsWith("H")) v.substringBefore('-') else v }
            val node = buildJsonObject {
                put("endpoint", "${s.strOrNull("endpoint_host") ?: req.publicIp}:${req.port}")
                put("publicKey", pub)
                put("interface", iface)
                put("publicIp", req.publicIp)
                put("port", req.port)
                if (obfuscation.isNotEmpty()) put("obfuscation", JsonObject(obfuscation.mapValues { JsonPrimitive(it.value) }))
            }
            // Counters start again from zero on the new machine.
            sql.exec("DELETE FROM node_peer_counters WHERE server_id=?", s.str("id"))
            sql.exec(
                "UPDATE servers SET protocols=?, registered_at=?, last_report_at=? WHERE id=?",
                Jsonb(JsonObject(mapOf(req.protocol to node)).toString()), ctx.now(), ctx.now(), s.str("id"),
            )
            val subnet = s.str("subnet")
            val prefix = subnet.substringAfter('/')
            val first = subnet.substringBefore('/').split('.').let { "${it[0]}.${it[1]}.${it[2]}.${it[3].toInt() + 1}" }
            NodeRegisterRes(req.protocol, box.decrypt(enc), iface, "$first/$prefix", subnet, req.port, obfuscation) to s.str("id")
        }
        ctx.nodeHub.changed(id)
        return res
    }

    private suspend fun serverIdOf(token: String?): String = ctx.db.run { bySql(it, token).str("id") }

    private suspend fun snapshot(serverId: String): NodePeersRes = ctx.db.run { sql ->
        val s = sql.one("SELECT * FROM servers WHERE id=?", serverId) ?: throw unauthorized()
        val protocol = s.json("protocols").keys.firstOrNull() ?: Protocols.AMNEZIAWG
        val peers = sql.query("SELECT peer_key, address FROM tunnels WHERE server_id=? AND protocol=? ORDER BY peer_key", serverId, protocol)
            .map { NodePeer(it.str("peer_key"), it.str("address")) }
        val version = MessageDigest.getInstance("SHA-256").digest(peers.joinToString("\n") { "${it.publicKey} ${it.address}" }.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
        NodePeersRes(version, protocol, if (protocol == Protocols.AMNEZIAWG) IFACE_AWG else IFACE_WG, peers)
    }

    /**
     * The node's peers. When the agent already has [known], waits up to [wait] for a change
     * (long poll), so a new device works on the node within a second.
     */
    suspend fun peers(token: String?, known: String?, wait: kotlin.time.Duration = 25.seconds): NodePeersRes {
        val id = serverIdOf(token)
        val flow = ctx.nodeHub.flow(id)
        val start = flow.value
        val now = snapshot(id)
        if (known == null || now.version != known) return now
        withTimeoutOrNull(wait) { flow.first { it != start } }
        return snapshot(id)
    }

    /** Counters and machine health, every minute. */
    suspend fun report(token: String?, req: NodeReportReq) {
        ctx.db.tx { sql ->
            val s = bySql(sql, token)
            val id = s.str("id")
            val now = ctx.now()
            for (p in req.peers.take(70_000)) {
                sql.exec(
                    """INSERT INTO node_peer_counters (server_id, protocol, peer_key, rx_bytes, tx_bytes, last_handshake) VALUES (?,?,?,?,?,?)
                       ON CONFLICT (server_id, protocol, peer_key) DO UPDATE SET rx_bytes=EXCLUDED.rx_bytes, tx_bytes=EXCLUDED.tx_bytes,
                         last_handshake=EXCLUDED.last_handshake""",
                    id, req.protocol, p.publicKey, p.rx, p.tx, if (p.lastHandshake > 0) Instant.ofEpochSecond(p.lastHandshake) else null,
                )
            }
            // Peers the node no longer has (removed devices) don't need counters anymore.
            sql.exec(
                "DELETE FROM node_peer_counters WHERE server_id=? AND peer_key NOT IN (SELECT peer_key FROM tunnels WHERE server_id=?)",
                id, id,
            )
            val prev = s.jsonOrNull("report")?.let { runCatching { Protocols.decode<StoredReport>(it) }.getOrNull() }
            val secs = prev?.let { (now.toEpochMilli() - it.at) / 1000.0 }?.takeIf { it > 0 }
            fun bps(a: Long, b: Long?) = if (secs == null || b == null || a < b) 0L else ((a - b) * 8 / secs).toLong()
            val stored = StoredReport(now.toEpochMilli(), req.system, bps(req.system.rxBytes, prev?.system?.rxBytes), bps(req.system.txBytes, prev?.system?.txBytes))
            sql.exec("UPDATE servers SET last_report_at=?, report=? WHERE id=?", now, Jsonb(Protocols.encode(stored).toString()), id)
        }
    }
}

/** AES-GCM with a key derived from a secret string (AUTH_SECRET based). */
private class SecretBox(secret: String) {
    private val key = javax.crypto.spec.SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()), "AES")
    private val random = java.security.SecureRandom()
    fun encrypt(plain: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv)) }
        return java.util.Base64.getEncoder().encodeToString(iv + c.doFinal(plain.toByteArray()))
    }
    fun decrypt(enc: String): String {
        val all = java.util.Base64.getDecoder().decode(enc)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, all, 0, 12)) }
        return String(c.doFinal(all, 12, all.size - 12))
    }
}
