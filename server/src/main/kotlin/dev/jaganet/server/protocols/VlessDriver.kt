package dev.jaganet.server.protocols

import dev.jaganet.api.Protocols
import dev.jaganet.api.Vless
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * VLESS+Reality nodes (Xray-core). There is no client key: [parseClientParams] makes the user's
 * UUID v4 here, on the server, and the tunnel keeps it in `client_params`, so every later
 * [addPeer] for the same device (resync, moving to another node) gives the same UUID back. The
 * peer key is that UUID; on the node it is both the Xray user's `id` and its `email`.
 *
 * Users are applied on the node by its agent (`xray api adu` / `rmu`, agent.py), and counters
 * come from the agent's report, as for AmneziaWG nodes. A VLESS node always runs an agent: there
 * is no local mode, so for a node without one this driver only hands out parameters.
 */
class VlessDriver(private val newUuid: () -> String = { UUID.randomUUID().toString() }) : ProtocolDriver {
    override val id = Protocols.VLESS

    /** Whatever the device sent, the UUID is made here. */
    override fun parseClientParams(raw: JsonObject): JsonObject = JsonObject(mapOf("uuid" to JsonPrimitive(newUuid())))

    private fun uuidOf(clientParams: JsonObject): String =
        (clientParams["uuid"] as? JsonPrimitive)?.content?.lowercase()?.takeIf(Vless::isUuid) ?: newUuid()

    private fun settings(node: ServerNode): Vless.NodeSettings = Protocols.decode(node.settings)

    override suspend fun addPeer(node: ServerNode, deviceId: String, address: String, clientParams: JsonObject): AddedPeer {
        val s = settings(node)
        val uuid = uuidOf(clientParams)
        return AddedPeer(uuid, Protocols.encode(s.params(uuid)))
    }

    override suspend fun removePeer(node: ServerNode, peerKey: String) {}

    /** Counters arrive with the agent's report (services/Nodes.kt), never read here. */
    override suspend fun readCounters(node: ServerNode): List<PeerCounters> = emptyList()

    /** Without the peer there is no UUID to put in, so only the peer-aware version answers. */
    override fun currentParams(node: ServerNode): JsonObject? = null

    override fun currentParams(node: ServerNode, peerKey: String): JsonObject? =
        runCatching { Protocols.encode(settings(node).params(peerKey)) }.getOrNull()
}
