package dev.jaganet.server.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.time.Instant

/**
 * The BitTorrent counters traffic.py writes next to a VPN interface (resources/node/traffic.py).
 * peers: public key -> (bytes from the device, bytes to the device), cumulative.
 * status: "ok", "off" (no file: the service isn't installed), "stale" or the service's error.
 */
data class TrafficClasses(val status: String, val peers: Map<String, Pair<Long, Long>>) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun read(path: String, now: Instant = Instant.now()): TrafficClasses {
            val f = File(path)
            if (!f.canRead()) return TrafficClasses("off", emptyMap())
            return try {
                val o = json.parseToJsonElement(f.readText()).jsonObject
                val at = o["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0
                if (now.epochSecond - at > 300) return TrafficClasses("stale", emptyMap())
                if (o["ok"]?.jsonPrimitive?.booleanOrNull != true) return TrafficClasses(o["error"]?.jsonPrimitive?.contentOrNull ?: "error", emptyMap())
                val peers = (o["peers"] as? JsonObject).orEmpty().mapValues { (_, v) ->
                    val p = v.jsonObject
                    (p["torrentRx"]?.jsonPrimitive?.longOrNull ?: 0L) to (p["torrentTx"]?.jsonPrimitive?.longOrNull ?: 0L)
                }
                TrafficClasses("ok", peers)
            } catch (e: Exception) {
                TrafficClasses("error", emptyMap())
            }
        }
    }
}
