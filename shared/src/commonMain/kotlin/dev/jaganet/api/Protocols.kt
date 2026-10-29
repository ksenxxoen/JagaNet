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
    /** VLESS+Reality on Xray-core: see [Vless]. */
    const val VLESS = "vless"

    /**
     * Protocols an app gets in GET /servers only when it names them in `supportedProtocols`.
     * Apps from before VLESS don't send that parameter, so they never see a node they can't use.
     */
    val OPT_IN = setOf(VLESS)

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
    private val OPTIONAL_ZERO = setOf("S3", "S4")

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
            // Empty values and zero S3/S4 (the defaults) are left out: older AmneziaWG apps don't know those keys.
            KEYS.filter { k -> p.obfuscation[k].let { v -> !v.isNullOrBlank() && !(k in OPTIONAL_ZERO && v.trim() == "0") } }
                .forEach { appendLine("$it = ${p.obfuscation[it]}") }
            extraInterfaceLines.forEach { appendLine(it) }
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${p.serverPublicKey}")
            appendLine("Endpoint = ${p.endpoint}")
            appendLine("AllowedIPs = ${p.allowedIps.joinToString(", ")}")
            appendLine("PersistentKeepalive = ${p.persistentKeepalive}")
        }
}

/**
 * VLESS+Reality on [Xray-core](https://github.com/XTLS/Xray-core). The device has no key of its
 * own: the server makes a UUID v4 per device (the Xray user id, and its `email` on the node), so
 * `clientParams` is an empty object. A node offers one or more transports (Vision on TCP 443,
 * XHTTP on 8443, …); the app tries [ServerParams.variants] in order and keeps the first that
 * connects. The node's Reality private key never leaves the node: the panel keeps only the
 * public key and the short ids. Format and examples: docs/ARCHITECTURE.md, "VLESS+Reality".
 *
 * `TunnelConfig.params` for `protocol = "vless"`:
 * ```json
 * {
 *   "uuid": "6f1c…",
 *   "variants": [
 *     {"transport": "tcp", "host": "ru1.example.com", "port": 443, "flow": "xtls-rprx-vision",
 *      "security": "reality", "sni": "ru1.example.com", "pbk": "<public key>", "sid": "a1b2c3d4", "fp": "firefox"},
 *     {"transport": "xhttp", "host": "ru1.example.com", "port": 8443, "flow": "",
 *      "security": "reality", "sni": "ru1.example.com", "pbk": "<public key>", "sid": "a1b2c3d4", "fp": "firefox",
 *      "path": "/secret", "mode": "auto"}
 *   ]
 * }
 * ```
 * Field names are those of the standard `vless://` share link ([link] makes one per variant).
 * TunnelConfig.address, mtu and dns mean nothing to VLESS; the apps ignore them.
 */
object Vless {
    const val FLOW_VISION = "xtls-rprx-vision"
    /** Transport ids, as in a share link's `type`. */
    const val TCP = "tcp"
    const val XHTTP = "xhttp"
    val TRANSPORTS = setOf(TCP, XHTTP)
    val FLOWS = setOf("", FLOW_VISION)
    val XHTTP_MODES = setOf("auto", "packet-up", "stream-up", "stream-one")

    private val HOST = Regex("^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)*[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
    private val IPV6 = Regex("^[0-9A-Fa-f:.]{2,45}$")
    /** x25519 public key, base64url without padding: what `xray x25519` prints as the public key ("Password"). */
    private val PUBLIC_KEY = Regex("^[A-Za-z0-9_-]{43}$")
    /** Reality short id: 0 to 16 hex digits, an even number of them. */
    private val SHORT_ID = Regex("^([0-9a-f]{2}){0,8}$")
    private val TAG = Regex("^[A-Za-z0-9_.-]{1,32}$")
    private val FINGERPRINT = Regex("^[a-z0-9]{1,20}$")
    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    fun isHost(s: String) = HOST.matches(s) || (':' in s && IPV6.matches(s))
    fun isUuid(s: String) = UUID.matches(s)

    /**
     * One Xray inbound of the node, in the order the apps try them.
     * [tag] is the inbound's tag in the node's Xray config: the agent adds users there.
     * [flow] defaults to Vision on TCP and to none on XHTTP. [host] overrides the node's host.
     */
    @Serializable
    data class Transport(
        val type: String,
        val port: Int,
        val tag: String = if (type == TCP) "vision" else type,
        val flow: String = if (type == TCP) FLOW_VISION else "",
        val path: String? = null,
        val mode: String? = null,
        val host: String? = null,
    )

    /**
     * A VLESS node's settings in the panel (`servers.protocols.vless`). The owner enters them;
     * no secrets: [publicKey] is Reality's public key, the private one stays in the node's config.
     */
    @Serializable
    data class NodeSettings(
        /** The address the apps connect to (the cascade's entry), a host name or an IP. */
        val host: String,
        val publicKey: String,
        val shortIds: List<String>,
        /** Reality server name: one of the inbound's `serverNames`. */
        val sni: String,
        val fingerprint: String = "firefox",
        val transports: List<Transport>,
    ) {
        /** Null when valid, else the name of the first wrong field (for the owner's error message). */
        fun problem(): String? = when {
            !isHost(host) -> "host"
            !PUBLIC_KEY.matches(publicKey) -> "publicKey"
            shortIds.isEmpty() || shortIds.size > 16 || shortIds.any { !SHORT_ID.matches(it) } -> "shortIds"
            !HOST.matches(sni) -> "sni"
            !FINGERPRINT.matches(fingerprint) -> "fingerprint"
            transports.isEmpty() || transports.size > 8 -> "transports"
            transports.map { it.tag }.toSet().size != transports.size -> "transports.tag"
            else -> transports.firstNotNullOfOrNull { t -> t.problem() }
        }

        private fun Transport.problem(): String? = when {
            type !in TRANSPORTS -> "transports.type"
            port !in 1..65535 -> "transports.port"
            !TAG.matches(tag) -> "transports.tag"
            flow !in FLOWS -> "transports.flow"
            host != null && !isHost(host) -> "transports.host"
            type == XHTTP && (path == null || !path.startsWith("/") || path.length > 200 || path.any { it.isWhitespace() || it == '#' || it == '?' }) -> "transports.path"
            type == XHTTP && mode != null && mode !in XHTTP_MODES -> "transports.mode"
            type == TCP && (path != null || mode != null) -> "transports.path"
            else -> null
        }

        /** The short id the apps use: the first one (Reality also accepts an empty one if listed). */
        val shortId: String get() = shortIds.first()

        /** What the apps try, in order, as the user [uuid]. */
        fun params(uuid: String) = ServerParams(uuid, transports.map { t ->
            Variant(
                transport = t.type, host = t.host ?: host, port = t.port, flow = t.flow,
                sni = sni, pbk = publicKey, sid = shortId, fp = fingerprint,
                path = t.path.takeIf { t.type == XHTTP }, mode = if (t.type == XHTTP) t.mode ?: "auto" else null,
            )
        })
    }

    /** One way to reach the node. Names follow the `vless://` link's query keys. */
    @Serializable
    data class Variant(
        /** "tcp" (RAW, with Vision) or "xhttp". */
        val transport: String,
        val host: String,
        val port: Int,
        /** "xtls-rprx-vision" or "" (none). */
        val flow: String = "",
        val security: String = "reality",
        val sni: String,
        /** Reality public key. */
        val pbk: String,
        /** Reality short id. */
        val sid: String,
        /** uTLS fingerprint, e.g. "firefox". */
        val fp: String,
        /** XHTTP only. */
        val path: String? = null,
        /** XHTTP only: auto, packet-up, stream-up or stream-one. */
        val mode: String? = null,
    )

    /** Returned in TunnelConfig.params. */
    @Serializable
    data class ServerParams(val uuid: String, val variants: List<Variant>)

    /**
     * The standard share link for one variant, which v2rayN, v2rayNG, Hiddify, Streisand,
     * NekoBox and others import:
     * `vless://<uuid>@<host>:<port>?encryption=none&security=reality&pbk=…&sid=…&sni=…&fp=…&type=tcp&flow=xtls-rprx-vision#<name>`
     * Keys in this order; flow, path and mode only when set; values and the name percent-encoded
     * (UTF-8, everything but `A-Z a-z 0-9 - . _ ~`). An IPv6 host goes in brackets.
     */
    fun link(uuid: String, v: Variant, name: String): String = buildString {
        append("vless://").append(uuid).append('@')
        append(if (':' in v.host) "[${v.host}]" else v.host).append(':').append(v.port)
        val q = buildList {
            add("encryption" to "none")
            add("security" to v.security)
            add("pbk" to v.pbk)
            add("sid" to v.sid)
            add("sni" to v.sni)
            add("fp" to v.fp)
            add("type" to v.transport)
            if (v.flow.isNotEmpty()) add("flow" to v.flow)
            v.path?.let { add("path" to it) }
            v.mode?.let { add("mode" to it) }
        }
        append('?').append(q.joinToString("&") { (k, value) -> "$k=${encode(value)}" })
        append('#').append(encode(name))
    }

    /** One link per variant, in order; with several, the name gets the transport (" TCP", " XHTTP"). */
    fun links(p: ServerParams, name: String): List<String> =
        p.variants.map { link(p.uuid, it, if (p.variants.size > 1) "$name ${it.transport.uppercase()}" else name) }

    /** RFC 3986 percent-encoding of UTF-8 text; only unreserved characters stay as they are. */
    fun encode(s: String): String = buildString {
        val hex = "0123456789ABCDEF"
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') append(ch)
            else append('%').append(hex[c shr 4]).append(hex[c and 15])
        }
    }
}
