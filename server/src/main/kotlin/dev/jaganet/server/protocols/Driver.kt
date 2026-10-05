package dev.jaganet.server.protocols

import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** A VPN node, with its settings for one protocol (servers.protocols[protocolId]). */
data class ServerNode(val id: String, val name: String, val settings: JsonObject)

/** Cumulative counters for one peer, as the node sees them. */
data class PeerCounters(val peerKey: String, val rxBytes: Long, val txBytes: Long, val lastSeenAt: Instant?)

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

    /** Optional hook: a device reported connect/disconnect (the simulator uses it). */
    fun onConnectionEvent(node: ServerNode, peerKey: String, connected: Boolean) {}
}

class DriverRegistry {
    private val drivers = linkedMapOf<String, ProtocolDriver>()
    fun register(d: ProtocolDriver) = apply { drivers[d.id] = d }
    operator fun get(id: String): ProtocolDriver? = drivers[id]
    fun ids(): List<String> = drivers.keys.toList()
}
