package dev.jaganet.server

import dev.jaganet.api.Protocols
import dev.jaganet.api.Vless
import dev.jaganet.api.i18n.Lang
import dev.jaganet.server.services.RegionalRouting
import dev.jaganet.server.services.SubscriptionOptions
import dev.jaganet.server.services.VlessLocation
import dev.jaganet.server.services.VlessSubscription
import dev.jaganet.server.services.XrayConfigs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** JAG-7: regional direct routing in the subscription formats (flag off by default) and per-node XHTTP tuning (default unchanged). */
class RegionalRoutingTest {
    private val uuid = "11111111-2222-4333-8444-555555555555"
    private val pbk = "_No2ExH-ajHLTIY4xDz1EjOQ6izyfCBAueJM73gZbh0"

    private fun settings(host: String, xhttp: Vless.XhttpTuning? = null) = Vless.NodeSettings(
        host = host, publicKey = pbk, shortIds = listOf("a1b2c3d4"), sni = host,
        transports = listOf(Vless.Transport("tcp", 443), Vless.Transport("xhttp", 8443, path = "/p", xhttp = xhttp)),
    )

    private fun locations(xhttp: Vless.XhttpTuning? = null) = listOf(
        VlessLocation("n1", "FI", "FI", settings("fi.example.com", xhttp).params(uuid)),
        VlessLocation("n2", "NL", "NL", settings("nl.example.com").params(uuid)),
    )

    private val on = SubscriptionOptions(regionalDirect = true)
    private fun JsonObject.arr(k: String) = this[k]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.strs(k: String) = this[k]!!.jsonArray.map { it.jsonPrimitive.content }
    private fun configs(o: SubscriptionOptions, l: List<VlessLocation> = locations()) = XrayConfigs.configs(l, Lang.DEFAULT, o).map { it.jsonObject }

    // ---- the flag ----

    @Test fun `regional routing is off by default and the configs are as before`() {
        assertFalse(SubscriptionOptions().regionalDirect)
        assertFalse(SubscriptionOptions.load(emptyMap()).regionalDirect)
        assertFalse(SubscriptionOptions.load(mapOf("VLESS_SUB_REGIONAL_DIRECT" to "0")).regionalDirect)
        for (c in configs(SubscriptionOptions())) {
            val routing = c["routing"]!!.jsonObject
            assertEquals("AsIs", routing["domainStrategy"]!!.jsonPrimitive.content)
            assertTrue(routing.arr("rules").none { "domain" in it }, "no domain rules without the flag")
            assertFalse(c.toString().contains("geoip"))
        }
        val clash = VlessSubscription.clash(locations(), Lang.DEFAULT, SubscriptionOptions())
        assertFalse("DOMAIN-SUFFIX" in clash || "GEOIP" in clash)
        val box = VlessSubscription.singBox(locations(), Lang.DEFAULT, SubscriptionOptions())
        assertFalse("domain_suffix" in box)
        assertFalse("regional" in (VlessSubscription.happRouting(SubscriptionOptions())?.let { String(Base64.getDecoder().decode(it.substringAfterLast('/'))) } ?: ""))
    }

    @Test fun `the flag and the owner's own lists come from the environment`() {
        val o = SubscriptionOptions.load(mapOf(
            "VLESS_SUB_REGIONAL_DIRECT" to "on",
            "VLESS_SUB_REGIONAL_DIRECT_EXTRA" to " Shop.Example.com , domain:bank.example.net.",
            "VLESS_SUB_REGIONAL_PROXY_EXTRA" to "wiki.example.org",
        ))
        assertTrue(o.regionalDirect)
        assertEquals(listOf("shop.example.com", "bank.example.net"), o.regionalDirectExtra)
        assertEquals(listOf("wiki.example.org"), o.regionalProxyExtra)
        assertTrue("shop.example.com" in RegionalRouting.direct(o))
        assertEquals(RegionalRouting.PROXIED + "wiki.example.org", RegionalRouting.proxied(o))
        assertFailsWith<IllegalArgumentException> { SubscriptionOptions.load(mapOf("VLESS_SUB_REGIONAL_DIRECT_EXTRA" to "not a domain")) }
        assertFailsWith<IllegalArgumentException> { SubscriptionOptions.load(mapOf("VLESS_SUB_REGIONAL_PROXY_EXTRA" to "a/b.example.com")) }
    }

    @Test fun `the lists cover zones, banks and IP checkers, and the forced sites are not hidden by a direct rule`() {
        val direct = RegionalRouting.direct(on)
        for (d in listOf("ru", "su", "xn--p1ai", "sberbank.com", "ipify.org", "ifconfig.me", "checkip.amazonaws.com")) assertTrue(d in direct, d)
        assertEquals(setOf("4pda.ru", "habr.com"), setOf("4pda.ru", "habr.com").filter { it in RegionalRouting.proxied(on) }.toSet())
        assertEquals(direct.size, direct.distinct().size)
        assertTrue(direct.none { it.startsWith("domain:") || it.isBlank() })
    }

    // ---- Xray configs ----

    @Test fun `Xray config - forced sites first, then direct domains and the geo rule, then the tunnel`() {
        for (c in configs(on)) {
            val routing = c["routing"]!!.jsonObject
            assertEquals("IPIfNonMatch", routing["domainStrategy"]!!.jsonPrimitive.content)
            val rules = routing.arr("rules")
            val forced = rules.indexOfFirst { r -> "domain:4pda.ru" in (r["domain"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()) }
            val direct = rules.indexOfFirst { r -> "domain:ru" in (r["domain"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()) }
            val geo = rules.indexOfFirst { r -> r["ip"]?.jsonArray?.any { it.jsonPrimitive.content == "geoip:ru" } == true }
            val private = rules.indexOfFirst { r -> r["ip"]?.jsonArray?.any { it.jsonPrimitive.content == "10.0.0.0/8" } == true }
            assertTrue(private in 0 until forced, "private networks first")
            assertTrue(forced in 0 until direct && direct < geo, "forced, direct, geo")
            assertEquals("direct", rules[direct]["outboundTag"]!!.jsonPrimitive.content)
            assertEquals("direct", rules[geo]["outboundTag"]!!.jsonPrimitive.content)
            assertTrue("domain:habr.com" in rules[forced].strs("domain"))
            assertTrue("domain:ipify.org" in rules[direct].strs("domain"))
            // The forced rule goes to the tunnel: the balancer in "Auto", the one outbound elsewhere.
            val balanced = c["balancers"] != null || routing["balancers"] != null
            if (balanced) assertEquals("auto", rules[forced]["balancerTag"]!!.jsonPrimitive.content)
            else assertEquals("proxy", rules[forced]["outboundTag"]!!.jsonPrimitive.content)
            // Everything else is still the tunnel (the catch-all, or the first outbound).
            if (balanced) assertEquals("auto", rules.last()["balancerTag"]!!.jsonPrimitive.content)
            assertEquals("proxy", c.arr("outbounds").first()["tag"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `Xray config - owner's extra names are in the right rules`() {
        val o = on.copy(regionalDirectExtra = listOf("shop.example.com"), regionalProxyExtra = listOf("wiki.example.org"))
        val rules = configs(o).first()["routing"]!!.jsonObject.arr("rules")
        assertTrue(rules.any { "domain:wiki.example.org" in (it["domain"]?.jsonArray?.map { d -> d.jsonPrimitive.content } ?: emptyList()) && it["outboundTag"] == null })
        assertTrue(rules.any { "domain:shop.example.com" in (it["domain"]?.jsonArray?.map { d -> d.jsonPrimitive.content } ?: emptyList()) && it["outboundTag"]?.jsonPrimitive?.content == "direct" })
    }

    // ---- the other formats ----

    @Test fun `sing-box and Clash profiles get the domain rules, Clash the geo rule too`() {
        val box = Json.parseToJsonElement(VlessSubscription.singBox(locations(), Lang.DEFAULT, on)).jsonObject
        val rules = box["route"]!!.jsonObject.arr("rules")
        val forced = rules.indexOfFirst { it["domain_suffix"]?.jsonArray?.any { d -> d.jsonPrimitive.content == "4pda.ru" } == true }
        val direct = rules.indexOfFirst { it["domain_suffix"]?.jsonArray?.any { d -> d.jsonPrimitive.content == "ru" } == true }
        assertTrue(forced in 0 until direct)
        assertEquals("Boop VPN", rules[forced]["outbound"]!!.jsonPrimitive.content)
        assertEquals("direct", rules[direct]["outbound"]!!.jsonPrimitive.content)
        assertEquals("Boop VPN", box["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)

        val clash = VlessSubscription.clash(locations(), Lang.DEFAULT, on).lines()
        val iForced = clash.indexOf("  - \"DOMAIN-SUFFIX,4pda.ru,Boop VPN\"")
        val iDirect = clash.indexOf("  - \"DOMAIN-SUFFIX,ru,DIRECT\"")
        val iGeo = clash.indexOf("  - \"GEOIP,RU,DIRECT,no-resolve\"")
        val iMatch = clash.indexOf("  - \"MATCH,Boop VPN\"")
        assertTrue(iForced > 0 && iForced < iDirect && iDirect < iGeo && iGeo < iMatch, "$iForced $iDirect $iGeo $iMatch")
    }

    @Test fun `Happ and v2RayTun headers carry the regional rules`() {
        val happ = Json.parseToJsonElement(String(Base64.getDecoder().decode(VlessSubscription.happRouting(on)!!.removePrefix("happ://routing/onadd/")))).jsonObject
        assertEquals("IPIfNonMatch", happ["DomainStrategy"]!!.jsonPrimitive.content)
        assertTrue("domain:ru" in happ.strs("DirectSites"))
        assertTrue("geoip:ru" in happ.strs("DirectIp") && "10.0.0.0/8" in happ.strs("DirectIp"))
        assertTrue("domain:4pda.ru" in happ.strs("ProxySites"))
        val off = Json.parseToJsonElement(String(Base64.getDecoder().decode(VlessSubscription.happRouting(SubscriptionOptions())!!.removePrefix("happ://routing/onadd/")))).jsonObject
        assertEquals(emptyList(), off.strs("DirectSites"))
        assertEquals("AsIs", off["DomainStrategy"]!!.jsonPrimitive.content)

        val tun = Json.parseToJsonElement(String(Base64.getDecoder().decode(VlessSubscription.v2rayTunRouting(on)!!))).jsonObject
        assertEquals("IPIfNonMatch", tun["domainStrategy"]!!.jsonPrimitive.content)
        val rules = tun.arr("rules")
        assertTrue(rules.any { "domain:4pda.ru" in (it["domain"]?.jsonArray?.map { d -> d.jsonPrimitive.content } ?: emptyList()) && it["outboundTag"]!!.jsonPrimitive.content == "proxy" })
        assertTrue(rules.indexOfFirst { it["__name__"]?.jsonPrimitive?.content == "Tunnel" } < rules.indexOfFirst { it["__name__"]?.jsonPrimitive?.content == "Regional" })
        // The regional rules alone justify the header even without the QUIC block; without both there is none, as before.
        assertNotNull(VlessSubscription.v2rayTunRouting(on.copy(blockQuic = false)))
        assertNull(VlessSubscription.v2rayTunRouting(SubscriptionOptions(blockQuic = false)))
        // The header helper used by the subscription route: Happ and v2RayTun only, same payload.
        assertEquals(VlessSubscription.happRouting(on), VlessSubscription.routingHeader("Happ/4.8.0/Android/1", on))
        assertEquals(VlessSubscription.v2rayTunRouting(on), VlessSubscription.routingHeader("v2RayTun/5.23.74", on))
        assertNull(VlessSubscription.routingHeader("v2rayNG/1.10.5", on))
        assertNull(VlessSubscription.routingHeader(null, on))
    }

    // ---- XHTTP tuning ----

    /** The per-link config of a node's XHTTP variant. */
    private fun xhttpConfig(cs: List<JsonObject>, host: String) = cs.first { c ->
        c.arr("outbounds").any { o ->
            o["streamSettings"]?.jsonObject?.get("network")?.jsonPrimitive?.content == "xhttp" &&
                o["settings"]!!.jsonObject.arr("vnext").first()["address"]!!.jsonPrimitive.content == host
        }
    }

    private fun xhttpOutbound(c: JsonObject) = c.arr("outbounds").first { it["streamSettings"]?.jsonObject?.get("network")?.jsonPrimitive?.content == "xhttp" }["streamSettings"]!!.jsonObject["xhttpSettings"]!!.jsonObject

    @Test fun `XHTTP without tuning is exactly as before`() {
        val xs = xhttpOutbound(xhttpConfig(configs(SubscriptionOptions(), locations()), "fi.example.com"))
        assertEquals(setOf("path", "mode"), xs.keys)
        val link = Vless.links(locations().first().params, "FI").last()
        assertFalse("extra=" in link)
        assertFalse("xhttp" in Protocols.encode(locations().first().params).toString().replace("\"transport\":\"xhttp\"", ""))
    }

    @Test fun `browser preset gives padding, 150 KB chunks and a ten-minute xmux, fields set win`() {
        val t = Vless.XhttpTuning(preset = "browser").resolved()
        assertEquals("50-355", t.xPaddingBytes)
        assertEquals("150000", t.scMaxEachPostBytes)
        assertEquals("580-666", t.xmux!!.hMaxReusableSecs)
        assertEquals("1", t.xmux!!.maxConnections)
        val mixed = Vless.XhttpTuning(preset = "browser", xPaddingBytes = "100-200", xmux = Vless.Xmux(maxConcurrency = "16-32")).resolved()
        assertEquals("100-200", mixed.xPaddingBytes)
        assertEquals("16-32", mixed.xmux!!.maxConcurrency)
        assertNull(mixed.xmux!!.maxConnections, "maxConcurrency and maxConnections never go together")
        assertEquals("580-666", mixed.xmux!!.hMaxReusableSecs)
        assertEquals(emptyMap(), Vless.XhttpTuning().extraJson())
    }

    @Test fun `tuned XHTTP reaches the Xray config and the share link, Vision links stay`() {
        val tuned = Vless.XhttpTuning(preset = "browser")
        val c = xhttpConfig(configs(SubscriptionOptions(), locations(tuned)), "fi.example.com")
        val extra = xhttpOutbound(c)["extra"]!!.jsonObject
        assertEquals("50-355", extra["xPaddingBytes"]!!.jsonPrimitive.content)
        assertEquals("150000", extra["scMaxEachPostBytes"]!!.jsonPrimitive.content)
        assertEquals("580-666", extra["xmux"]!!.jsonObject["hMaxReusableSecs"]!!.jsonPrimitive.content)
        // The other node has no tuning: untouched.
        val nl = xhttpConfig(configs(SubscriptionOptions(), locations(tuned)), "nl.example.com")
        assertFalse("extra" in xhttpOutbound(nl))
        val links = Vless.links(locations(tuned).first().params, "FI")
        assertFalse("extra=" in links.first(), "Vision link")
        val q = links.last().substringAfter("extra=").substringBefore('&').substringBefore('#')
        val decoded = Json.parseToJsonElement(java.net.URLDecoder.decode(q, "UTF-8")).jsonObject
        assertEquals(extra, decoded)
        // And the stored params survive the round trip (the apps that don't know the field ignore it).
        val back = Protocols.decode<Vless.ServerParams>(Protocols.encode(locations(tuned).first().params))
        assertEquals(tuned.resolved(), back.variants.last().xhttp)
        assertNull(back.variants.first().xhttp)
    }

    @Test fun `tuning is checked with the node settings`() {
        fun problem(t: Vless.XhttpTuning, type: String = "xhttp") =
            settings("a.example.com").copy(transports = listOf(Vless.Transport(type, 8443, path = "/p".takeIf { type == "xhttp" }, xhttp = t))).problem()
        assertNull(problem(Vless.XhttpTuning(preset = "browser", scMaxEachPostBytes = "1000000", xmux = Vless.Xmux(hMaxReusableSecs = "580-666"))))
        assertEquals("transports.xhttp.preset", problem(Vless.XhttpTuning(preset = "other")))
        assertEquals("transports.xhttp.xPaddingBytes", problem(Vless.XhttpTuning(xPaddingBytes = "355-50")))
        assertEquals("transports.xhttp.xPaddingBytes", problem(Vless.XhttpTuning(xPaddingBytes = "abc")))
        assertEquals("transports.xhttp.scMaxEachPostBytes", problem(Vless.XhttpTuning(scMaxEachPostBytes = "0")))
        assertEquals("transports.xhttp.xmux.hMaxReusableSecs", problem(Vless.XhttpTuning(xmux = Vless.Xmux(hMaxReusableSecs = "999999"))))
        assertEquals("transports.xhttp.xmux", problem(Vless.XhttpTuning(xmux = Vless.Xmux(maxConnections = "1", maxConcurrency = "16"))))
        assertEquals("transports.xhttp", problem(Vless.XhttpTuning(preset = "browser"), type = "tcp"))
    }
}
