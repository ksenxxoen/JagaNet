package dev.jaganet.server.protocols

import dev.jaganet.api.AmneziaWG
import dev.jaganet.api.Protocols
import dev.jaganet.api.WireGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * Drivers for the WireGuard family. Both manage peers on a local interface via
 * their CLI (needs CAP_NET_ADMIN): `wg` for WireGuard, `awg` (amneziawg-tools)
 * for AmneziaWG. Remote nodes apply peers through their agent (services/Nodes.kt), so
 * here they only get their client parameters.
 */
abstract class WgFamilyDriver(private val tool: String, private val run: suspend (List<String>) -> String) : ProtocolDriver {
    @Serializable
    protected data class NodeSettings(
        val endpoint: String,
        val publicKey: String,
        val `interface`: String = "wg0",
        val keepalive: Int = 25,
        /** AmneziaWG only: the node's obfuscation settings, awg-quick key names. */
        val obfuscation: Map<String, String> = emptyMap(),
    ) {
        init { require(Regex("^[a-zA-Z0-9_.-]{1,15}$").matches(`interface`)) { "bad interface name" } }
    }

    protected fun settings(node: ServerNode) = Protocols.decode<NodeSettings>(node.settings)

    /** Builds TunnelConfig.params for this protocol. */
    protected abstract fun params(s: NodeSettings): JsonObject

    override fun parseClientParams(raw: JsonObject) = Protocols.encode(Protocols.decode<WireGuard.ClientParams>(raw))

    override suspend fun addPeer(node: ServerNode, deviceId: String, address: String, clientParams: JsonObject): AddedPeer {
        val s = settings(node)
        val key = Protocols.decode<WireGuard.ClientParams>(clientParams).publicKey
        if (!node.remote) run(listOf(tool, "set", s.`interface`, "peer", key, "allowed-ips", "$address/32"))
        return AddedPeer(key, params(s))
    }

    override suspend fun removePeer(node: ServerNode, peerKey: String) {
        if (node.remote) return
        run(listOf(tool, "set", settings(node).`interface`, "peer", peerKey, "remove"))
    }

    override suspend fun readCounters(node: ServerNode): List<PeerCounters> =
        if (node.remote) emptyList() else parseDump(run(listOf(tool, "show", settings(node).`interface`, "dump")))

    companion object {
        /** `wg|awg show <if> dump`: first line is the interface, then one tab-separated line per peer. */
        fun parseDump(out: String): List<PeerCounters> = out.trim().lines().drop(1)
            .map { it.split('\t') }
            .filter { it.size >= 8 }
            .map { f ->
                val hs = f[4].toLong()
                PeerCounters(f[0], rxBytes = f[5].toLong(), txBytes = f[6].toLong(), lastSeenAt = if (hs > 0) Instant.ofEpochSecond(hs) else null)
            }

        suspend fun exec(cmd: List<String>): String = withContext(Dispatchers.IO) {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "${cmd.joinToString(" ")} failed: $out" }
            out
        }
    }
}

class WireGuardDriver(run: suspend (List<String>) -> String = ::exec) : WgFamilyDriver("wg", run) {
    override val id = Protocols.WIREGUARD
    override fun params(s: NodeSettings) =
        Protocols.encode(WireGuard.ServerParams(s.publicKey, s.endpoint, listOf("0.0.0.0/0", "::/0"), s.keepalive))

    companion object {
        fun parseDump(out: String) = WgFamilyDriver.parseDump(out)
    }
}

/**
 * AmneziaWG node: an amneziawg-go (or kernel module) interface configured with
 * the same S1–S4/H1–H4 values that are listed in the node's `obfuscation` settings.
 */
class AmneziaWgDriver(run: suspend (List<String>) -> String = ::exec) : WgFamilyDriver("awg", run) {
    override val id = Protocols.AMNEZIAWG
    override fun params(s: NodeSettings) =
        Protocols.encode(AmneziaWG.ServerParams(s.publicKey, s.endpoint, listOf("0.0.0.0/0", "::/0"), s.keepalive, s.obfuscation))
}
