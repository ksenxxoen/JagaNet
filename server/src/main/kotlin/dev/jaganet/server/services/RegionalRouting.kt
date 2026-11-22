package dev.jaganet.server.services

/**
 * Regional direct routing for the clients' split routing (JAG-7, flag `VLESS_SUB_REGIONAL_DIRECT`, off
 * by default): traffic to the home region's own sites and services (national domain zones and
 * `geoip:ru`, the big portals, banks and payment services, IP-checker pages) goes direct, so those
 * sites see the user's own address and keep working; a few sites that must come through the tunnel
 * are forced back into it and win over every direct rule.
 *
 * Names are domain suffixes (Xray's `domain:` rules: the name and all its subdomains). The same lists
 * feed every format: Xray configs, the Happ and v2RayTun `routing` headers, sing-box and Clash.
 * The owner extends them without a release: `VLESS_SUB_REGIONAL_DIRECT_EXTRA` and `VLESS_SUB_REGIONAL_PROXY_EXTRA`.
 */
object RegionalRouting {
    /** IP ranges that are the home region's by the geo database every client ships (Xray, Happ, v2RayTun; Clash as `GEOIP,RU`). */
    const val GEOIP = "geoip:ru"

    /** National zones, as Xray's `domain:` takes a bare zone (all names in it). */
    val ZONES = listOf(
        "ru", "su", "xn--p1ai", "moscow", "tatar", "xn--80adxhks", "xn--80asehdb", "xn--80aswg", "xn--d1acj3b",
    )

    /** Big portals, social networks, maps and shops that live outside the national zones. */
    val SERVICES = listOf(
        "yandex.com", "yandex.net", "yandex.kz", "yandex.by", "yandex.uz", "yastatic.net", "yandexcloud.net",
        "vk.com", "vk.me", "vk.cc", "vk-cdn.net", "vkuser.net", "vkuseraudio.net", "vkuservideo.net", "userapi.com", "mycdn.me", "tamtam.chat",
        "ozon.com", "ozon.kz", "ozonusercontent.com", "wbstatic.net", "avito.st", "2gis.com", "okko.tv", "premier.one", "dodopizza.com", "dodostatic.net",
    )

    /** Banks and payment services whose own domains are outside the national zones; they refuse or flag a foreign address. */
    val BANKS = listOf(
        "sberbank.com", "vtb.com", "alfabank.com", "alfa-bank.com", "tochka.com", "tbank-online.com", "bank131.com",
    )

    /** Pages and APIs that show the address a request comes from: they should show the user's own. */
    val IP_CHECKERS = listOf(
        "ipify.org", "checkip.amazonaws.com", "ifconfig.me", "ifconfig.co", "icanhazip.com", "ipinfo.io", "ip-api.com",
        "ipapi.co", "ident.me", "myip.com", "wtfismyip.com", "showip.net", "whoer.net", "2ip.io", "2ip.me", "ipecho.net",
    )

    /** Sites forced through the tunnel: they win over every direct rule (the national zone would send them direct). */
    val PROXIED = listOf("4pda.ru", "4pda.to", "4pda.ws", "habr.com", "habrastorage.org")

    private val NAME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$")

    /** A domain suffix from the owner's list, trimmed and lowercase; fails on anything that is not a plain name. */
    fun parseName(raw: String, key: String): String {
        val n = raw.trim().lowercase().removePrefix("domain:").trimEnd('.')
        require(NAME.matches(n) && n.length <= 253) { "$key: \"$raw\" is not a domain name" }
        return n
    }

    /** Domains that go direct: zones, services, banks, IP checkers, then the owner's own; none of the [proxied] ones. */
    fun direct(o: SubscriptionOptions): List<String> =
        (ZONES + SERVICES + BANKS + IP_CHECKERS + o.regionalDirectExtra).distinct()

    /** Domains forced through the tunnel. */
    fun proxied(o: SubscriptionOptions): List<String> = (PROXIED + o.regionalProxyExtra).distinct()
}
