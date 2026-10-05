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
    const val AMNEZIAWG = "amneziawg"
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

/**
 * AmneziaWG (https://github.com/amnezia-vpn/amneziawg-go): WireGuard with DPI
 * obfuscation — junk packets, padded messages, custom headers, signature packets
 * and (AWG 3+) header protection. Same keys and handshake as WireGuard, so the
 * device sends the same ClientParams.
 */
object AmneziaWG {
    /**
     * Obfuscation settings in awg-quick `[Interface]` key form, passed through
     * verbatim so new AWG versions need no app update.
     * Server-side keys (must equal the node's): S1–S4, H1–H4, HeaderProtectionKey.
     * Client-side keys (the node suggests values): Jc, Jmin, Jmax, I1–I5, timings, padding.
     */
    val SERVER_SIDE = setOf("S1", "S2", "S3", "S4", "H1", "H2", "H3", "H4", "HeaderProtectionKey")
    val CLIENT_SIDE = setOf(
        "Jc", "Jmin", "Jmax", "I1", "I2", "I3", "I4", "I5",
        "ContentPaddingAddition", "RekeyAfterTime", "RekeyTimeout", "RejectAfterTime",
        "KeepaliveTimeout", "MaxHandshakeAttempts", "RandomTrailers", "DisableCookies",
    )
    val KEYS = SERVER_SIDE + CLIENT_SIDE

    /** Returned in TunnelConfig.params. */
    @Serializable
    data class ServerParams(
        val serverPublicKey: String,
        val endpoint: String,
        val allowedIps: List<String>,
        val persistentKeepalive: Int,
        val obfuscation: Map<String, String>,
    ) {
        init {
            val unknown = obfuscation.keys - KEYS
            require(unknown.isEmpty()) { "unknown AmneziaWG keys: $unknown" }
            require(obfuscation.values.none { '\n' in it }) { "AmneziaWG values must be single-line" }
        }
    }

    /**
     * awg-quick config text for the device. Engines that take a config file
     * (amneziawg-android, amneziawg-apple) parse this directly.
     */
    fun quickConfig(privateKey: String, address: String, dns: List<String>, mtu: Int, p: ServerParams, extraInterfaceLines: List<String> = emptyList()): String =
        buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = $privateKey")
            appendLine("Address = $address")
            if (dns.isNotEmpty()) appendLine("DNS = ${dns.joinToString(", ")}")
            appendLine("MTU = $mtu")
            KEYS.filter { it in p.obfuscation }.forEach { appendLine("$it = ${p.obfuscation[it]}") }
            extraInterfaceLines.forEach { appendLine(it) }
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${p.serverPublicKey}")
            appendLine("Endpoint = ${p.endpoint}")
            appendLine("AllowedIPs = ${p.allowedIps.joinToString(", ")}")
            appendLine("PersistentKeepalive = ${p.persistentKeepalive}")
        }
}
