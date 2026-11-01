package dev.jaganet.server.services

import dev.jaganet.api.Vless
import dev.jaganet.api.i18n.I18n
import dev.jaganet.api.i18n.Lang
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.util.Base64
import java.util.UUID

/** A DNS-over-HTTPS server for the clients' remote DNS, from VLESS_SUB_DNS: `https://<host>[:port][/path]`. */
data class DohServer(val url: String, val host: String, val port: Int, val path: String) {
    /** True when [host] is an IP address, so the client needs no other DNS to reach it. */
    val isIp: Boolean get() = IP.matches(host)

    companion object {
        private val IP = Regex("^(\\d{1,3}(\\.\\d{1,3}){3}|[0-9A-Fa-f:]*:[0-9A-Fa-f:.]*)$")

        fun parse(url: String): DohServer {
            val u = runCatching { URI(url.trim()) }.getOrNull()
            require(u != null && u.scheme == "https" && !u.host.isNullOrBlank() && u.rawQuery == null && u.rawFragment == null && u.userInfo == null) {
                "VLESS_SUB_DNS: \"$url\" is not a DNS-over-HTTPS address (https://host/dns-query)"
            }
            val host = u.host.removePrefix("[").removeSuffix("]")
            val path = u.rawPath?.takeIf { it.isNotEmpty() } ?: "/dns-query"
            return DohServer(url.trim(), host, if (u.port > 0) u.port else 443, path)
        }
    }
}

/**
 * What a VLESS subscription tells the clients besides the links (issue #59), from the environment;
 * every part is on unless switched off.
 *  - VLESS_SUB_DNS: remote DNS servers, DNS over HTTPS through the tunnel, comma-separated
 *    (default `https://1.1.1.1/dns-query,https://8.8.8.8/dns-query`); `0`, `off` or empty: none
 *    (the clients keep their own DNS settings; the sing-box profile keeps its old 1.1.1.1).
 *  - VLESS_SUB_BLOCK_QUIC=0: no UDP 443 (QUIC) block in the clients' routing.
 *  - VLESS_SUB_XRAY_JSON=0: Happ and v2RayTun get the base64 links (with their routing header)
 *    instead of full Xray configs.
 */
data class SubscriptionOptions(
    val dns: List<DohServer> = DEFAULT_DNS.map { DohServer.parse(it) },
    val blockQuic: Boolean = true,
    val xrayJson: Boolean = true,
) {
    companion object {
        val DEFAULT_DNS = listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query")

        fun load(env: Map<String, String>): SubscriptionOptions {
            fun on(k: String) = env[k]?.trim()?.lowercase() !in setOf("0", "false", "off", "no")
            val dns = env["VLESS_SUB_DNS"]?.trim()?.let { v ->
                if (v.lowercase() in setOf("", "0", "off", "false", "none")) emptyList()
                else v.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { DohServer.parse(it) }
            } ?: DEFAULT_DNS.map { DohServer.parse(it) }
            return SubscriptionOptions(dns, on("VLESS_SUB_BLOCK_QUIC"), on("VLESS_SUB_XRAY_JSON"))
        }
    }
}

/**
 * The VLESS subscription (/k/<token>/vless) in the format the client reads (issues #41, #59):
 *  - [Format.BASE64]: the vless:// links, one per line, base64. v2rayNG, Hiddify and everything
 *    else; Vision and XHTTP links of every location. Happ and v2RayTun get it too when
 *    VLESS_SUB_XRAY_JSON=0, with their own `routing` header ([happRouting], [v2rayTunRouting]).
 *  - [Format.XRAY_JSON]: an array of full Xray configs (XrayConfigs): Happ and v2RayTun, which
 *    take DNS and routing only this way.
 *  - [Format.SINGBOX]: a sing-box (1.12+) profile: TUN, a selector with "Авто (лучший)" (urltest
 *    over every location) first and each location after it.
 *  - [Format.CLASH]: a Clash Meta (mihomo) profile with the same two groups.
 * sing-box has no XHTTP and older mihomo builds refuse it, so both profiles carry the Vision
 * (TCP) links only; XHTTP stays in the base64 list and the Xray configs.
 * Every format but plain links gets the remote DNS and the QUIC block of [SubscriptionOptions].
 */
object VlessSubscription {
    enum class Format { BASE64, XRAY_JSON, SINGBOX, CLASH }

    /** The apps whose own subscription extensions we use. */
    enum class Client { HAPP, V2RAYTUN, OTHER }

    const val PROBE_URL = "https://www.gstatic.com/generate_204"
    /** The selector's name: the profile itself, not translated (a brand). */
    const val SELECT = "Boop VPN"

    /** Private and link-local networks: never through the tunnel. */
    val PRIVATE = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "127.0.0.0/8", "fc00::/7", "fe80::/10")

    /** Happ reads Xray JSON subscriptions from 1.63.1 on (the version Marzban serves them to). */
    private val HAPP_JSON_SINCE = listOf(1, 63, 1)
    private val HAPP = Regex("^happ/(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?")

    fun client(userAgent: String?): Client {
        val ua = userAgent?.trim()?.lowercase().orEmpty()
        return when {
            ua.startsWith("happ/") || ua == "happ" -> Client.HAPP
            "v2raytun" in ua -> Client.V2RAYTUN
            else -> Client.OTHER
        }
    }

    /** True for a Happ new enough for Xray JSON subscriptions; a Happ without a version is taken as new. */
    private fun happReadsJson(userAgent: String): Boolean {
        val m = HAPP.find(userAgent.trim().lowercase()) ?: return true
        val v = (1..3).map { m.groupValues[it].toIntOrNull() ?: 0 }
        for (i in 0..2) if (v[i] != HAPP_JSON_SINCE[i]) return v[i] > HAPP_JSON_SINCE[i]
        return true
    }

    /**
     * ?format= wins (json, xray, singbox, sing-box, clash, mihomo, base64, v2ray); else the
     * User-Agent: Happ (1.63.1 and newer) and v2RayTun get Xray configs while VLESS_SUB_XRAY_JSON
     * is on; the official sing-box apps (SFA, SFI, SFM, SFT, sing-box) the sing-box profile; Clash
     * Meta clients (clash, mihomo, FlClash, Clash Verge, ClashMetaForAndroid) the Clash profile.
     * Hiddify names sing-box and Clash in its User-Agent but builds its own "auto" group from plain
     * links and also reads XHTTP, so it gets the base64 list, as do v2rayNG and anything unknown.
     */
    fun format(query: String?, userAgent: String?, options: SubscriptionOptions = SubscriptionOptions()): Format {
        when (query?.trim()?.lowercase()) {
            "json", "xray", "xray-json", "v2ray-json" -> return Format.XRAY_JSON
            "singbox", "sing-box" -> return Format.SINGBOX
            "clash", "clashmeta", "clash-meta", "mihomo" -> return Format.CLASH
            "base64", "v2ray", "links" -> return Format.BASE64
        }
        val ua = userAgent?.lowercase().orEmpty()
        if ("hiddify" in ua) return Format.BASE64
        when (client(userAgent)) {
            Client.HAPP -> return if (options.xrayJson && happReadsJson(ua)) Format.XRAY_JSON else Format.BASE64
            Client.V2RAYTUN -> return if (options.xrayJson) Format.XRAY_JSON else Format.BASE64
            Client.OTHER -> {}
        }
        if (Regex("^(sfa|sfi|sfm|sft)/").containsMatchIn(ua) || "sing-box" in ua || "singbox" in ua) return Format.SINGBOX
        if ("clash" in ua || "mihomo" in ua) return Format.CLASH
        return Format.BASE64
    }

    fun base64(links: List<String>): String = Base64.getEncoder().encodeToString(links.joinToString("\n").toByteArray())

    /** The Vision (TCP) variants of every location with their names, as the profiles use them. */
    internal fun tcp(locations: List<VlessLocation>, lang: Lang): List<Triple<String, String, Vless.Variant>> {
        val list = locations.flatMap { l -> l.named(lang).filter { it.first.transport == Vless.TCP }.map { Triple(l.params.uuid, it.second, it.first) } }
        return list.zip(VlessLocations.unique(list.map { it.second })) { t, n -> t.copy(second = n) }
    }

    /** False when no location has a Vision (TCP) link: then the profiles would be empty, and the base64 list is sent. */
    fun hasGroups(locations: List<VlessLocation>): Boolean = locations.any { l -> l.params.variants.any { it.transport == Vless.TCP } }

    fun autoName(lang: Lang) = I18n.tr(lang, "Auto (best)")

    private val pretty = Json { prettyPrint = true }
    private val compact = Json

    /** The sing-box profile's remote DNS before issue #59, kept when VLESS_SUB_DNS is off. */
    private val SINGBOX_FALLBACK_DNS = DohServer.parse(SubscriptionOptions.DEFAULT_DNS.first())

    /** A sing-box profile (sing-box 1.12 and newer). */
    fun singBox(locations: List<VlessLocation>, lang: Lang = Lang.DEFAULT, options: SubscriptionOptions = SubscriptionOptions()): String {
        val proxies = tcp(locations, lang)
        val auto = autoName(lang)
        val names = proxies.map { it.second }
        // sing-box asks one server (the route's final); the first of VLESS_SUB_DNS.
        val doh = options.dns.firstOrNull() ?: SINGBOX_FALLBACK_DNS
        val doc = buildJsonObject {
            putJsonObject("log") { put("level", "warn") }
            putJsonObject("dns") {
                putJsonArray("servers") {
                    addJsonObject {
                        put("type", "https"); put("tag", "dns-remote"); put("server", doh.host)
                        if (doh.port != 443) put("server_port", doh.port)
                        if (doh.path != "/dns-query") put("path", doh.path)
                        if (!doh.isIp) put("domain_resolver", "dns-local")
                        put("detour", SELECT)
                    }
                    addJsonObject { put("type", "local"); put("tag", "dns-local") }
                }
                put("final", "dns-remote")
                put("strategy", "ipv4_only")
            }
            putJsonArray("inbounds") {
                addJsonObject {
                    put("type", "tun"); put("tag", "tun-in")
                    putJsonArray("address") { add("172.19.0.1/30") }
                    put("auto_route", true); put("strict_route", true); put("stack", "mixed")
                }
            }
            putJsonArray("outbounds") {
                addJsonObject {
                    put("type", "selector"); put("tag", SELECT)
                    putJsonArray("outbounds") { add(auto); names.forEach { add(it) } }
                    put("default", auto)
                }
                addJsonObject {
                    put("type", "urltest"); put("tag", auto)
                    putJsonArray("outbounds") { names.forEach { add(it) } }
                    put("url", PROBE_URL); put("interval", "3m"); put("tolerance", 50)
                }
                for ((uuid, name, v) in proxies) addJsonObject {
                    put("type", "vless"); put("tag", name)
                    put("server", v.host); put("server_port", v.port); put("uuid", uuid)
                    if (v.flow.isNotEmpty()) put("flow", v.flow)
                    putJsonObject("tls") {
                        put("enabled", true); put("server_name", v.sni)
                        putJsonObject("utls") { put("enabled", true); put("fingerprint", v.fp) }
                        putJsonObject("reality") { put("enabled", true); put("public_key", v.pbk); put("short_id", v.sid) }
                    }
                }
                addJsonObject { put("type", "direct"); put("tag", "direct") }
            }
            putJsonObject("route") {
                putJsonArray("rules") {
                    addJsonObject { put("action", "sniff") }
                    addJsonObject { put("protocol", "dns"); put("action", "hijack-dns") }
                    // QUIC: refused at once, so the apps fall back to TCP (issue #59).
                    if (options.blockQuic) addJsonObject { put("network", "udp"); put("port", 443); put("action", "reject") }
                    addJsonObject { put("ip_is_private", true); put("outbound", "direct") }
                }
                put("final", SELECT)
                put("auto_detect_interface", true)
                putJsonObject("default_domain_resolver") { put("server", "dns-local") }
            }
        }
        return pretty.encodeToString(JsonElement.serializer(), doc) + "\n"
    }

    /** A Clash Meta (mihomo) profile. Strings are written JSON-quoted, which YAML reads as they are. */
    fun clash(locations: List<VlessLocation>, lang: Lang = Lang.DEFAULT, options: SubscriptionOptions = SubscriptionOptions()): String {
        val proxies = tcp(locations, lang)
        val auto = autoName(lang)
        fun q(s: String) = JsonPrimitive(s).toString()
        fun list(items: List<String>) = items.joinToString(", ", "[", "]") { q(it) }
        return buildString {
            appendLine("mixed-port: 7890")
            appendLine("allow-lan: false")
            appendLine("mode: rule")
            appendLine("log-level: warning")
            appendLine("ipv6: false")
            if (options.dns.isNotEmpty()) {
                // Remote DNS through the tunnel (respect-rules sends it by the rules, so MATCH),
                // real addresses (redir-host), no fake-ip; the proxies' own names resolve directly.
                val servers = list(options.dns.map { it.url })
                appendLine("dns:")
                appendLine("  enable: true")
                appendLine("  ipv6: false")
                appendLine("  enhanced-mode: redir-host")
                appendLine("  respect-rules: true")
                appendLine("  nameserver: $servers")
                appendLine("  proxy-server-nameserver: $servers")
                if (options.dns.any { !it.isIp }) appendLine("  default-nameserver: ${list(listOf("1.1.1.1", "8.8.8.8"))}")
            }
            appendLine("proxies:")
            for ((uuid, name, v) in proxies) {
                appendLine("  - name: ${q(name)}")
                appendLine("    type: vless")
                appendLine("    server: ${q(v.host)}")
                appendLine("    port: ${v.port}")
                appendLine("    uuid: ${q(uuid)}")
                appendLine("    network: tcp")
                appendLine("    udp: true")
                appendLine("    tls: true")
                if (v.flow.isNotEmpty()) appendLine("    flow: ${q(v.flow)}")
                appendLine("    servername: ${q(v.sni)}")
                appendLine("    client-fingerprint: ${q(v.fp)}")
                appendLine("    reality-opts:")
                appendLine("      public-key: ${q(v.pbk)}")
                appendLine("      short-id: ${q(v.sid)}")
            }
            appendLine("proxy-groups:")
            appendLine("  - name: ${q(SELECT)}")
            appendLine("    type: select")
            appendLine("    proxies: ${list(listOf(auto) + proxies.map { it.second })}")
            appendLine("  - name: ${q(auto)}")
            appendLine("    type: url-test")
            appendLine("    proxies: ${list(proxies.map { it.second })}")
            appendLine("    url: ${q(PROBE_URL)}")
            appendLine("    interval: 180")
            appendLine("    tolerance: 50")
            appendLine("rules:")
            if (options.blockQuic) appendLine("  - ${q("AND,((NETWORK,UDP),(DST-PORT,443)),REJECT")}")
            appendLine("  - ${q("IP-CIDR,10.0.0.0/8,DIRECT,no-resolve")}")
            appendLine("  - ${q("IP-CIDR,172.16.0.0/12,DIRECT,no-resolve")}")
            appendLine("  - ${q("IP-CIDR,192.168.0.0/16,DIRECT,no-resolve")}")
            appendLine("  - ${q("MATCH,$SELECT")}")
        }
    }

    /**
     * Happ's routing profile for the base64 list (VLESS_SUB_XRAY_JSON=0 or an old Happ): the
     * `routing` header with `happ://routing/onadd/<base64 JSON>` (https://www.happ.su/main/dev-docs/routing).
     * It sets the remote DNS (DoH through the tunnel, the first of VLESS_SUB_DNS) and no fake DNS;
     * a Happ profile has domain and IP lists only, so the QUIC block needs the Xray configs.
     * Null without DNS servers.
     */
    fun happRouting(options: SubscriptionOptions): String? {
        val remote = options.dns.firstOrNull() ?: return null
        val domestic = options.dns.getOrElse(1) { remote }
        val profile = buildJsonObject {
            put("Name", SELECT)
            put("GlobalProxy", "true")
            put("RemoteDNSType", "DoH")
            put("RemoteDNSDomain", remote.url)
            put("RemoteDNSIP", if (remote.isIp) remote.host else "")
            put("DomesticDNSType", "DoH")
            put("DomesticDNSDomain", domestic.url)
            put("DomesticDNSIP", if (domestic.isIp) domestic.host else "")
            putJsonObject("DnsHosts") {}
            putJsonArray("DirectSites") {}
            putJsonArray("DirectIp") { PRIVATE.forEach { add(it) } }
            putJsonArray("ProxySites") {}
            putJsonArray("ProxyIp") {}
            putJsonArray("BlockSites") {}
            putJsonArray("BlockIp") {}
            put("DomainStrategy", "AsIs")
            put("FakeDNS", "false")
        }
        return "happ://routing/onadd/" + Base64.getEncoder().encodeToString(compact.encodeToString(JsonElement.serializer(), profile).toByteArray())
    }

    /**
     * v2RayTun's routing for the base64 list (VLESS_SUB_XRAY_JSON=0): the `routing` header, base64
     * of an Xray `routing` object as the app exports it (https://docs.v2raytun.com/overview/supported-headers):
     * UDP 443 to `block`, private networks direct. v2RayTun has no header for DNS. Null without the QUIC block.
     */
    fun v2rayTunRouting(options: SubscriptionOptions): String? {
        if (!options.blockQuic) return null
        fun id(s: String) = UUID.nameUUIDFromBytes("boop-routing-$s".toByteArray()).toString().uppercase()
        val routing = buildJsonObject {
            put("name", SELECT)
            put("id", id("profile"))
            put("domainStrategy", "AsIs")
            put("domainMatcher", "hybrid")
            putJsonArray("balancers") {}
            putJsonArray("rules") {
                addJsonObject {
                    put("id", id("quic")); put("__name__", "QUIC")
                    put("type", "field"); put("network", "udp"); put("port", "443"); put("outboundTag", "block")
                }
                addJsonObject {
                    put("id", id("private")); put("__name__", "LAN")
                    put("type", "field"); putJsonArray("ip") { PRIVATE.forEach { add(it) } }; put("outboundTag", "direct")
                }
            }
        }
        return Base64.getEncoder().encodeToString(compact.encodeToString(JsonElement.serializer(), routing).toByteArray())
    }

    /** `subscription-userinfo`: bytes this month (upload, download), the monthly limit (0 = none) and the end of access (0 = none). */
    fun userInfo(upload: Long, download: Long, total: Long, expire: Long) = "upload=$upload; download=$download; total=$total; expire=$expire"
}
