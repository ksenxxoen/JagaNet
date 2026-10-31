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
import java.util.Base64

/**
 * The VLESS subscription (/k/<token>/vless) in the format the client reads (issue #41):
 *  - [Format.BASE64]: the vless:// links, one per line, base64. Happ, v2RayTun, v2rayNG, Hiddify
 *    and everything else; Vision and XHTTP links of every location.
 *  - [Format.SINGBOX]: a sing-box (1.12+) profile: TUN, a selector with "Авто (лучший)" (urltest
 *    over every location) first and each location after it.
 *  - [Format.CLASH]: a Clash Meta (mihomo) profile with the same two groups.
 * sing-box has no XHTTP and older mihomo builds refuse it, so both profiles carry the Vision
 * (TCP) links only; XHTTP stays in the base64 list.
 */
object VlessSubscription {
    enum class Format { BASE64, SINGBOX, CLASH }

    const val PROBE_URL = "https://www.gstatic.com/generate_204"
    /** The selector's name: the profile itself, not translated (a brand). */
    const val SELECT = "Boop VPN"

    /**
     * ?format= wins (singbox, sing-box, clash, mihomo, base64, v2ray); else the User-Agent: the
     * official sing-box apps (SFA, SFI, SFM, SFT, sing-box) and Clash Meta clients (clash, mihomo,
     * FlClash, Clash Verge, ClashMetaForAndroid). Hiddify names sing-box and Clash in its
     * User-Agent but builds its own "auto" group from plain links and also reads XHTTP, so it
     * gets the base64 list, as do Happ, v2RayTun, v2rayNG and anything unknown.
     */
    fun format(query: String?, userAgent: String?): Format {
        when (query?.trim()?.lowercase()) {
            "singbox", "sing-box" -> return Format.SINGBOX
            "clash", "clashmeta", "clash-meta", "mihomo" -> return Format.CLASH
            "base64", "v2ray", "links" -> return Format.BASE64
        }
        val ua = userAgent?.lowercase().orEmpty()
        if ("hiddify" in ua) return Format.BASE64
        if (Regex("^(sfa|sfi|sfm|sft)/").containsMatchIn(ua) || "sing-box" in ua || "singbox" in ua) return Format.SINGBOX
        if ("clash" in ua || "mihomo" in ua) return Format.CLASH
        return Format.BASE64
    }

    fun base64(links: List<String>): String = Base64.getEncoder().encodeToString(links.joinToString("\n").toByteArray())

    /** The Vision (TCP) variants of every location with their names, as the profiles use them. */
    private fun tcp(locations: List<VlessLocation>, lang: Lang): List<Triple<String, String, Vless.Variant>> {
        val list = locations.flatMap { l -> l.named(lang).filter { it.first.transport == Vless.TCP }.map { Triple(l.params.uuid, it.second, it.first) } }
        return list.zip(VlessLocations.unique(list.map { it.second })) { t, n -> t.copy(second = n) }
    }

    /** False when no location has a Vision (TCP) link: then the profiles would be empty, and the base64 list is sent. */
    fun hasGroups(locations: List<VlessLocation>): Boolean = locations.any { l -> l.params.variants.any { it.transport == Vless.TCP } }

    fun autoName(lang: Lang) = I18n.tr(lang, "Auto (best)")

    private val pretty = Json { prettyPrint = true }

    /** A sing-box profile (sing-box 1.12 and newer). */
    fun singBox(locations: List<VlessLocation>, lang: Lang = Lang.DEFAULT): String {
        val proxies = tcp(locations, lang)
        val auto = autoName(lang)
        val names = proxies.map { it.second }
        val doc = buildJsonObject {
            putJsonObject("log") { put("level", "warn") }
            putJsonObject("dns") {
                putJsonArray("servers") {
                    addJsonObject { put("type", "https"); put("tag", "dns-remote"); put("server", "1.1.1.1"); put("detour", SELECT) }
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
    fun clash(locations: List<VlessLocation>, lang: Lang = Lang.DEFAULT): String {
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
            appendLine("  - ${q("IP-CIDR,10.0.0.0/8,DIRECT,no-resolve")}")
            appendLine("  - ${q("IP-CIDR,172.16.0.0/12,DIRECT,no-resolve")}")
            appendLine("  - ${q("IP-CIDR,192.168.0.0/16,DIRECT,no-resolve")}")
            appendLine("  - ${q("MATCH,$SELECT")}")
        }
    }

    /** `subscription-userinfo`: bytes this month (upload, download), the monthly limit (0 = none) and the end of access (0 = none). */
    fun userInfo(upload: Long, download: Long, total: Long, expire: Long) = "upload=$upload; download=$download; total=$total; expire=$expire"
}
