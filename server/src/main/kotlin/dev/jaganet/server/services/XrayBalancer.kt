package dev.jaganet.server.services

import dev.jaganet.api.Vless
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The server-side balancer of a cascade (issue #41, behind VLESS_BALANCER=1): the part of the
 * entry node's Xray config that sends its users on to the fastest exit.
 *
 *  - one VLESS+Reality outbound per exit (`boop-exit-<node id>`), with the entry's own user
 *    ([cascadeUuid], servers.cascade_uuid) that the exits' agents add like any other user;
 *  - `routing.balancers`: `boop-exits` over those outbounds, strategy `leastPing`;
 *  - `routing.rules`: everything from the entry's inbounds (its transports' tags) to the balancer;
 *  - `observatory`: probes each exit every minute, which leastPing needs.
 *
 * Only public parameters go in (address, port, Reality public key, short id, SNI): the exits'
 * private keys never reach the panel. How to put it on the node: docs/SERVER.md, "Cascade".
 */
object XrayBalancer {
    const val BALANCER = "boop-exits"
    const val PREFIX = "boop-exit-"
    const val PROBE_URL = "https://www.gstatic.com/generate_204"

    data class Exit(val id: String, val settings: Vless.NodeSettings)

    /** The exit's transport the entry uses: Vision (TCP) if it has one, else its first. */
    private fun transportOf(s: Vless.NodeSettings): Vless.Transport =
        s.transports.firstOrNull { it.type == Vless.TCP && it.flow == Vless.FLOW_VISION } ?: s.transports.first { it.type in Vless.TRANSPORTS }

    fun fragment(entryInbounds: List<String>, cascadeUuid: String, exits: List<Exit>): JsonObject = buildJsonObject {
        putJsonArray("outbounds") {
            for (e in exits) addJsonObject {
                val t = transportOf(e.settings)
                put("tag", PREFIX + e.id)
                put("protocol", "vless")
                putJsonObject("settings") {
                    putJsonArray("vnext") {
                        addJsonObject {
                            put("address", t.host ?: e.settings.host)
                            put("port", t.port)
                            putJsonArray("users") {
                                addJsonObject {
                                    put("id", cascadeUuid)
                                    put("encryption", "none")
                                    if (t.flow.isNotEmpty()) put("flow", t.flow)
                                }
                            }
                        }
                    }
                }
                putJsonObject("streamSettings") {
                    put("network", if (t.type == Vless.XHTTP) "xhttp" else "tcp")
                    put("security", "reality")
                    putJsonObject("realitySettings") {
                        put("serverName", e.settings.sni)
                        put("fingerprint", e.settings.fingerprint)
                        put("publicKey", e.settings.publicKey)
                        put("shortId", e.settings.shortId)
                    }
                    if (t.type == Vless.XHTTP) putJsonObject("xhttpSettings") {
                        put("path", t.path ?: "/")
                        put("mode", t.mode ?: "auto")
                    }
                }
            }
        }
        putJsonObject("routing") {
            putJsonArray("balancers") {
                addJsonObject {
                    put("tag", BALANCER)
                    putJsonArray("selector") { add(PREFIX) }
                    putJsonObject("strategy") { put("type", "leastPing") }
                }
            }
            putJsonArray("rules") {
                addJsonObject {
                    put("type", "field")
                    putJsonArray("inboundTag") { entryInbounds.forEach { add(it) } }
                    put("balancerTag", BALANCER)
                }
            }
        }
        putJsonObject("observatory") {
            putJsonArray("subjectSelector") { add(PREFIX) }
            put("probeUrl", PROBE_URL)
            put("probeInterval", "1m")
            put("enableConcurrency", true)
        }
    }
}
