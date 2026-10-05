package dev.jaganet.server.protocols

import dev.jaganet.api.Protocols
import dev.jaganet.api.WireGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Reference driver: manages peers on a WireGuard interface on the same host via
 * the `wg` CLI (needs CAP_NET_ADMIN). For remote nodes, implement the same
 * interface over a node-agent API instead.
 */
class WireGuardDriver(private val wg: suspend (List<String>) -> String = ::runWg) : ProtocolDriver {
    override val id = Protocols.WIREGUARD

    @Serializable
    private data class NodeSettings(val endpoint: String, val publicKey: String, val `interface`: String = "wg0", val keepalive: Int = 25) {
        init { require(Regex("^[a-zA-Z0-9_.-]{1,15}$").matches(`interface`)) { "bad interface name" } }
    }

    override fun parseClientParams(raw: kotlinx.serialization.json.JsonObject) =
        Protocols.encode(Protocols.decode<WireGuard.ClientParams>(raw))

    override suspend fun addPeer(node: ServerNode, deviceId: String, address: String, clientParams: kotlinx.serialization.json.JsonObject): AddedPeer {
        val s = Protocols.decode<NodeSettings>(node.settings)
        val key = Protocols.decode<WireGuard.ClientParams>(clientParams).publicKey
        wg(listOf("set", s.`interface`, "peer", key, "allowed-ips", "$address/32"))
        val params = WireGuard.ServerParams(s.publicKey, s.endpoint, listOf("0.0.0.0/0", "::/0"), s.keepalive)
        return AddedPeer(key, Protocols.encode(params))
    }

    override suspend fun removePeer(node: ServerNode, peerKey: String) {
        val s = Protocols.decode<NodeSettings>(node.settings)
        wg(listOf("set", s.`interface`, "peer", peerKey, "remove"))
    }

    override suspend fun readCounters(node: ServerNode): List<PeerCounters> =
        parseDump(wg(listOf("show", Protocols.decode<NodeSettings>(node.settings).`interface`, "dump")))

    companion object {
        /** `wg show <if> dump`: first line is the interface, then one tab-separated line per peer. */
        fun parseDump(out: String): List<PeerCounters> = out.trim().lines().drop(1)
            .map { it.split('\t') }
            .filter { it.size >= 8 }
            .map { f ->
                val hs = f[4].toLong()
                PeerCounters(f[0], rxBytes = f[5].toLong(), txBytes = f[6].toLong(), lastSeenAt = if (hs > 0) Instant.ofEpochSecond(hs) else null)
            }

        private suspend fun runWg(args: List<String>): String = withContext(Dispatchers.IO) {
            val p = ProcessBuilder(listOf("wg") + args).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "wg ${args.joinToString(" ")} failed: $out" }
            out
        }
    }
}
