package dev.jaganet.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Typed shapes of the protocol-specific JSON objects. Adding a protocol =
 * an object here + a server driver + a device engine backend. Nothing else changes.
 */
object Protocols {
    const val WIREGUARD = "wireguard"

    /** Ids are lower-case slugs so custom protocols ("jaga-stealth", …) fit in. */
    val ID = Regex("^[a-z][a-z0-9-]{1,31}$")

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    inline fun <reified T> encode(value: T): JsonObject = json.encodeToJsonElement(value).jsonObject
    inline fun <reified T> decode(obj: JsonObject): T = json.decodeFromJsonElement(obj)
}

object WireGuard {
    private val KEY = Regex("^[A-Za-z0-9+/]{43}=$")
    fun isKey(s: String) = KEY.matches(s)

    /** Sent by the device. The private key is generated and kept on the device. */
    @Serializable
    data class ClientParams(val publicKey: String) {
        init { require(isKey(publicKey)) { "invalid WireGuard public key" } }
    }

    /** Returned in TunnelConfig.params. */
    @Serializable
    data class ServerParams(
        val serverPublicKey: String,
        val endpoint: String,
        val allowedIps: List<String>,
        val persistentKeepalive: Int,
    )
}
