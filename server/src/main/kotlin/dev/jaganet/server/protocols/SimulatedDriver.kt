package dev.jaganet.server.protocols

import dev.jaganet.api.Protocols
import dev.jaganet.api.WireGuard
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Stands in for any protocol in simulation and tests: accepts peers and
 * generates plausible traffic for peers whose device reported "connected".
 */
class SimulatedDriver(
    override val id: String,
    private val parse: (JsonObject) -> JsonObject = { it },
    private val peerKeyOf: (JsonObject, String) -> String = { _, deviceId -> "sim-$deviceId" },
    private val buildParams: (JsonObject) -> JsonObject = { JsonObject(emptyMap()) },
    /** Mean download rate of a connected peer, bytes per second. */
    private val rateBytesPerSec: Double = 400_000.0,
    private val now: () -> Instant = Instant::now,
) : ProtocolDriver {
    class Peer(var rx: Long = 0, var tx: Long = 0, var connected: Boolean = false, var lastSeenAt: Instant? = null)

    val peers = ConcurrentHashMap<String, Peer>()
    private var lastTick = now()

    @Synchronized private fun tick() {
        val t = now()
        val dt = (t.toEpochMilli() - lastTick.toEpochMilli()).coerceAtLeast(0) / 1000.0
        lastTick = t
        for (p in peers.values) if (p.connected) {
            val down = (rateBytesPerSec * dt * (0.4 + Random.nextDouble() * 1.2)).toLong()
            p.tx += down // node sends = user downloads
            p.rx += (down * 0.19).toLong()
            p.lastSeenAt = t
        }
    }

    override fun parseClientParams(raw: JsonObject) = parse(raw)

    override suspend fun addPeer(node: ServerNode, deviceId: String, address: String, clientParams: JsonObject): AddedPeer {
        val key = peerKeyOf(clientParams, deviceId)
        peers.putIfAbsent(key, Peer())
        return AddedPeer(key, buildParams(node.settings))
    }

    override suspend fun removePeer(node: ServerNode, peerKey: String) {
        peers.remove(peerKey)
    }

    override suspend fun readCounters(node: ServerNode): List<PeerCounters> {
        tick()
        return peers.map { (k, p) -> PeerCounters(k, p.rx, p.tx, p.lastSeenAt) }
    }

    override fun onConnectionEvent(node: ServerNode, peerKey: String, connected: Boolean) {
        tick()
        peers[peerKey]?.let {
            it.connected = connected
            if (connected) it.lastSeenAt = now()
        }
    }

    companion object {
        private val random = SecureRandom()

        /** Drivers used by `./gradlew :server:sim` and the tests. */
        fun registry(now: () -> Instant = Instant::now) = DriverRegistry()
            // Same request/response shapes as the real WireGuard driver, no kernel needed.
            .register(SimulatedDriver(
                id = Protocols.WIREGUARD,
                parse = { Protocols.encode(Protocols.decode<WireGuard.ClientParams>(it)) },
                peerKeyOf = { cp, _ -> cp["publicKey"]!!.jsonPrimitive.content },
                buildParams = { s ->
                    Protocols.encode(WireGuard.ServerParams(
                        serverPublicKey = s["publicKey"]!!.jsonPrimitive.content,
                        endpoint = s["endpoint"]!!.jsonPrimitive.content,
                        allowedIps = listOf("0.0.0.0/0", "::/0"),
                        persistentKeepalive = 25,
                    ))
                },
                now = now,
            ))
            // Example of a custom protocol: the server issues a per-device token.
            .register(SimulatedDriver(
                id = "jaga-custom",
                buildParams = { s ->
                    buildJsonObject {
                        put("endpoint", s["endpoint"] ?: JsonPrimitive(""))
                        put("token", Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(random::nextBytes)))
                        put("obfuscation", "tls")
                    }
                },
                now = now,
            ))
    }
}
