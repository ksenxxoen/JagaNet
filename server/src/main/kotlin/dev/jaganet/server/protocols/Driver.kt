package dev.jaganet.server.protocols

import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** A VPN node, with its settings for one protocol (servers.protocols[protocolId]). */
data class ServerNode(
    val id: String,
    val name: String,
    val settings: JsonObject,
    /** Runs elsewhere with an agent: its peers are applied there (services/Nodes.kt), not here. */
    val remote: Boolean = false,
)

/** Cumulative counters for one peer, as the node sees them. */
/** Cumulative counters of one peer. torrent*: the BitTorrent part (traffic.py), 0 when unknown. */
data class PeerCounters(
    val peerKey: String,
    val rxBytes: Long,
    val txBytes: Long,
    val lastSeenAt: Instant?,
    val torrentRx: Long = 0,
    val torrentTx: Long = 0,
    /** Where the client connects from (AmneziaWG / WireGuard peer endpoint, port cut off); null when unknown. */
    val endpoint: String? = null,
    /** VLESS: the user's online IPs and when each was last seen (Xray's online-IP stats); empty when unknown. */
    val ips: Map<String, Instant> = emptyMap(),
)

data class AddedPeer(val peerKey: String, val params: JsonObject)

/**
 * One implementation per tunnel protocol. The core (accounts, plans, devices,
 * quotas, stats) only talks to this interface, so WireGuard, a custom protocol
 * or a remote node agent all plug in the same way.
 */
interface ProtocolDriver {
    val id: String

    /** Validate the device's clientParams; throw IllegalArgumentException / SerializationException if invalid. */
    fun parseClientParams(raw: JsonObject): JsonObject

    /** Make the node accept this peer. Must be idempotent. */
    suspend fun addPeer(node: ServerNode, deviceId: String, address: String, clientParams: JsonObject): AddedPeer

    suspend fun removePeer(node: ServerNode, peerKey: String)

    suspend fun readCounters(node: ServerNode): List<PeerCounters>

    /**
     * TunnelConfig.params for a peer on this node with the node's current settings (endpoint,
     * obfuscation), or null if the driver can't tell. Key links use it, so a changed node
     * profile reaches devices that import their key again.
     */
    fun currentParams(node: ServerNode): JsonObject? = null

    /**
     * The same for one peer: protocols whose params carry the peer's own credential (VLESS: the
     * UUID) answer here. The default is [currentParams] without the peer.
     */
    fun currentParams(node: ServerNode, peerKey: String): JsonObject? = currentParams(node)

    /** Optional hook: a device reported connect/disconnect (the simulator uses it). */
    fun onConnectionEvent(node: ServerNode, peerKey: String, connected: Boolean) {}
}

class DriverRegistry {
    private val drivers = linkedMapOf<String, ProtocolDriver>()
    fun register(d: ProtocolDriver) = apply { drivers[d.id] = d }
    operator fun get(id: String): ProtocolDriver? = drivers[id]
    fun ids(): List<String> = drivers.keys.toList()
}
