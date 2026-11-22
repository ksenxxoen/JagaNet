package dev.jaganet.server.services

import dev.jaganet.api.Vless
import dev.jaganet.api.i18n.Lang
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The VLESS subscription as an array of full Xray configs (issue #59), which Happ and v2RayTun
 * import one per server and run as they are: the only way to hand them DNS and routing together
 * (https://www.happ.su/main/dev-docs/examples-of-links-and-parameters, "JSON Arrays").
 *
 * First "Самый быстрый 🚀": every location's Vision (TCP) link behind an Xray `leastPing` balancer
 * with the `observatory`, as the sing-box and Clash profiles have it; then one config per link of
 * the base64 list, in its order and with its names (XHTTP backups included).
 *
 * Each config has:
 *  - `dns`: DNS over HTTPS from VLESS_SUB_DNS, `queryStrategy` UseIPv4, no fake DNS. `hosts`
 *    bootstraps DoH names (cloudflare-dns.com, dns.google) and self-maps the servers' IPs.
 *    With VLESS_SUB_REGIONAL_DIRECT and at least two servers, the second (domestic) is used
 *    only for the regional direct domains and goes direct; the first (remote) is the default
 *    and goes through the tunnel, so other names are not resolved on the direct path;
 *  - the local `socks` (10808) and `http` (10809) inbounds on 127.0.0.1 the apps connect their TUN to;
 *  - `routing`: UDP 443 (QUIC) to `block` (VLESS_SUB_BLOCK_QUIC), DoH of the remote server to
 *    the tunnel and (with regional direct) DoH of the domestic server direct, the apps' DNS
 *    queries (port 53) to Xray's DNS (`dns-out`), private networks direct,
 *    with VLESS_SUB_REGIONAL_DIRECT the regional rules ([RegionalRouting]: forced-through-the-tunnel
 *    sites first, then the direct domains and `geoip:ru`; `domainStrategy` IPIfNonMatch so the
 *    geo rule sees names too), everything else through the tunnel.
 *  - XHTTP outbounds of a node that tunes them (`Vless.XhttpTuning`) get `xhttpSettings.extra`.
 */
object XrayConfigs {
    const val PROXY = "proxy"
    const val DIRECT = "direct"
    const val BLOCK = "block"
    const val DNS_OUT = "dns-out"
    const val AUTO = "auto"
    const val SOCKS_PORT = 10808
    const val HTTP_PORT = 10809

    private val pretty = Json { prettyPrint = true }

    fun subscription(locations: List<VlessLocation>, lang: Lang = Lang.DEFAULT, options: SubscriptionOptions = SubscriptionOptions()): String =
        pretty.encodeToString(JsonElement.serializer(), configs(locations, lang, options)) + "\n"

    fun configs(locations: List<VlessLocation>, lang: Lang = Lang.DEFAULT, options: SubscriptionOptions = SubscriptionOptions()): JsonArray = buildJsonArray {
        val tcp = VlessSubscription.tcp(locations, lang)
        if (tcp.isNotEmpty()) {
            val outbounds = tcp.mapIndexed { i, (uuid, _, v) -> outbound(if (i == 0) PROXY else "$PROXY-${i + 1}", uuid, v) }
            add(config(VlessSubscription.autoName(lang), outbounds, balanced = true, options))
        }
        val named = locations.flatMap { l -> l.named(lang).map { (v, n) -> Triple(l.params.uuid, n, v) } }
        for ((uuid, name, v) in named) add(config(name, listOf(outbound(PROXY, uuid, v)), balanced = false, options))
    }

    /** One VLESS+Reality outbound for a variant of a location. */
    fun outbound(tag: String, uuid: String, v: Vless.Variant): JsonObject = buildJsonObject {
        put("tag", tag)
        put("protocol", "vless")
        putJsonObject("settings") {
            putJsonArray("vnext") {
                addJsonObject {
                    put("address", v.host)
                    put("port", v.port)
                    putJsonArray("users") {
                        addJsonObject {
                            put("id", uuid)
                            put("encryption", "none")
                            if (v.flow.isNotEmpty()) put("flow", v.flow)
                        }
                    }
                }
            }
        }
        putJsonObject("streamSettings") {
            put("network", if (v.transport == Vless.XHTTP) "xhttp" else "tcp")
            put("security", v.security)
            putJsonObject("realitySettings") {
                put("serverName", v.sni)
                put("fingerprint", v.fp)
                put("publicKey", v.pbk)
                put("shortId", v.sid)
            }
            if (v.transport == Vless.XHTTP) putJsonObject("xhttpSettings") {
                put("path", v.path ?: "/")
                put("mode", v.mode ?: "auto")
                // Only a node that tunes its XHTTP has it; the default config stays as it was.
                v.xhttp?.extraJson()?.takeIf { it.isNotEmpty() }?.let { put("extra", it) }
            }
        }
    }

    /** Bootstrap and self-maps for DoH, as Happ puts in `dns.hosts`. */
    fun dnsHosts(options: SubscriptionOptions): Map<String, String> {
        val hosts = linkedMapOf(
            "1.1.1.1" to "1.1.1.1",
            "8.8.8.8" to "8.8.8.8",
            "cloudflare-dns.com" to "1.1.1.1",
            "dns.google" to "8.8.8.8",
        )
        options.dns.filter { it.isIp }.forEach { hosts[it.host] = it.host }
        return hosts
    }

    /**
     * True when regional direct routing can split DNS: the first VLESS_SUB_DNS server is remote
     * (through the tunnel), the second is domestic (direct, only for the regional domains).
     */
    fun splitDns(options: SubscriptionOptions): Boolean =
        options.regionalDirect && options.dns.size >= 2 && dohRouteIp(options.dns[0]) != null &&
            dohRouteIp(options.dns[1]) != null && dohRouteIp(options.dns[0]) != dohRouteIp(options.dns[1])

    /** The IPv4/IPv6 a DoH server is reached at, so routing can send it direct or through the tunnel. */
    fun dohRouteIp(s: DohServer): String? = when {
        s.isIp -> s.host
        s.host.equals("cloudflare-dns.com", ignoreCase = true) -> "1.1.1.1"
        s.host.equals("dns.google", ignoreCase = true) -> "8.8.8.8"
        else -> null
    }

    private fun config(remarks: String, proxies: List<JsonObject>, balanced: Boolean, options: SubscriptionOptions): JsonObject = buildJsonObject {
        put("remarks", remarks)
        putJsonObject("log") { put("loglevel", "warning") }
        if (options.dns.isNotEmpty()) putJsonObject("dns") {
            putJsonObject("hosts") { dnsHosts(options).forEach { (name, ip) -> put(name, ip) } }
            putJsonArray("servers") {
                if (splitDns(options)) {
                    val domestic = options.dns[1]
                    addJsonObject {
                        put("address", domestic.url)
                        putJsonArray("domains") { RegionalRouting.direct(options).forEach { add("domain:$it") } }
                    }
                    add(options.dns.first().url)
                } else {
                    options.dns.forEach { add(it.url) }
                }
            }
            put("queryStrategy", "UseIPv4")
        }
        putJsonArray("inbounds") {
            for ((tag, port, protocol) in listOf(Triple("socks", SOCKS_PORT, "socks"), Triple("http", HTTP_PORT, "http"))) addJsonObject {
                put("tag", tag)
                put("listen", "127.0.0.1")
                put("port", port)
                put("protocol", protocol)
                putJsonObject("settings") { if (protocol == "socks") { put("auth", "noauth"); put("udp", true) } }
                putJsonObject("sniffing") {
                    put("enabled", true)
                    putJsonArray("destOverride") { add("http"); add("tls"); add("quic") }
                    put("routeOnly", true)
                }
            }
        }
        // The first outbound is Xray's default: the tunnel (with the balancer, the rule below picks).
        putJsonArray("outbounds") {
            proxies.forEach { add(it) }
            addJsonObject { put("tag", DIRECT); put("protocol", "freedom") }
            addJsonObject { put("tag", BLOCK); put("protocol", "blackhole") }
            if (options.dns.isNotEmpty()) addJsonObject { put("tag", DNS_OUT); put("protocol", "dns") }
        }
        putJsonObject("routing") {
            put("domainStrategy", if (options.regionalDirect) "IPIfNonMatch" else "AsIs")
            putJsonArray("rules") {
                if (options.blockQuic) addJsonObject { put("type", "field"); put("network", "udp"); put("port", "443"); put("outboundTag", BLOCK) }
                if (options.dns.isNotEmpty()) {
                    if (splitDns(options)) {
                        val remoteIp = dohRouteIp(options.dns.first())!!
                        val domesticIp = dohRouteIp(options.dns[1])!!
                        addJsonObject {
                            put("type", "field"); putJsonArray("ip") { add(remoteIp) }; put("port", "443")
                            if (balanced) put("balancerTag", AUTO) else put("outboundTag", PROXY)
                        }
                        addJsonObject {
                            put("type", "field"); putJsonArray("ip") { add(domesticIp) }; put("port", "443"); put("outboundTag", DIRECT)
                        }
                    }
                    addJsonObject { put("type", "field"); put("port", "53"); put("outboundTag", DNS_OUT) }
                }
                addJsonObject { put("type", "field"); putJsonArray("ip") { VlessSubscription.PRIVATE.forEach { add(it) } }; put("outboundTag", DIRECT) }
                if (options.regionalDirect) {
                    // The forced sites first: Xray takes the first rule that matches, and most of them sit in a direct zone.
                    addJsonObject {
                        put("type", "field")
                        putJsonArray("domain") { RegionalRouting.proxied(options).forEach { add("domain:$it") } }
                        if (balanced) put("balancerTag", AUTO) else put("outboundTag", PROXY)
                    }
                    addJsonObject {
                        put("type", "field")
                        putJsonArray("domain") { RegionalRouting.direct(options).forEach { add("domain:$it") } }
                        put("outboundTag", DIRECT)
                    }
                    addJsonObject { put("type", "field"); putJsonArray("ip") { add(RegionalRouting.GEOIP) }; put("outboundTag", DIRECT) }
                }
                if (balanced) addJsonObject { put("type", "field"); put("network", "tcp,udp"); put("balancerTag", AUTO) }
            }
            if (balanced) putJsonArray("balancers") {
                addJsonObject {
                    put("tag", AUTO)
                    putJsonArray("selector") { add(PROXY) }
                    putJsonObject("strategy") { put("type", "leastPing") }
                    put("fallbackTag", PROXY)
                }
            }
        }
        if (balanced) putJsonObject("observatory") {
            putJsonArray("subjectSelector") { add(PROXY) }
            put("probeUrl", VlessSubscription.PROBE_URL)
            put("probeInterval", "3m")
            put("enableConcurrency", true)
        }
    }
}
