package dev.jaganet.server.services

import dev.jaganet.api.TunnelConfig
import dev.jaganet.server.db.Row
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val IPV4 = Regex("""^(?:25[0-5]|2[0-4]\d|[01]?\d\d?)(?:\.(?:25[0-5]|2[0-4]\d|[01]?\d\d?)){3}$""")

/** The address the node registered with, when it is an IPv4 literal. */
internal fun publicIp(server: Row, protocol: String): String? {
    val node = server.json("protocols")[protocol] as? JsonObject ?: return null
    return (node["publicIp"] as? JsonPrimitive)?.content
}

/**
 * Adds `endpointIps` next to the protocol params. Older apps ignore the field
 * (unknown JSON keys). A new address changes the tunnel version, so a client
 * that only syncs on version still notices a node that moved.
 * Obfuscation and the endpoint hostname are left as they are.
 */
internal fun TunnelConfig.withEndpointIp(ip: String?): TunnelConfig {
    val v4 = ip?.trim()?.takeIf { IPV4.matches(it) } ?: return this
    val hinted = JsonObject(params + ("endpointIps" to JsonArray(listOf(JsonPrimitive(v4)))))
    return copy(params = hinted)
}
