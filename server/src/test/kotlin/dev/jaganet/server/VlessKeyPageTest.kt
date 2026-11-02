package dev.jaganet.server

import dev.jaganet.api.i18n.Lang
import dev.jaganet.server.http.Pages
import dev.jaganet.server.http.VlessApps
import dev.jaganet.server.services.VlessLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Issue #88: the /k/{token} page of the VLESS key shows the subscription only. */
class VlessKeyPageTest {
    private val link = "vless://11111111-1111-1111-1111-111111111111@fi.example.com:443?type=tcp#FI"
    private fun key(links: List<String>) = VlessLinks("u", "d", "tok123", "Auto", "FI", links, null)
    private val pub = "https://panel.example.com"
    private val sub = "https://panel.example.com/k/tok123/vless"

    @Test fun `shows the subscription and no single keys`() {
        val html = Pages(Lang.EN).vlessKey(key(listOf(link, "$link-2")), pub, VlessApps(windows = true, windowsVersion = "1.5.1"))
        assertFalse("vless://" in html)
        assertFalse("data-copy=\"vk" in html)
        assertFalse("vless/0.png" in html)
        assertEquals(1, Regex("data-copy=").findAll(html).count())
        assertTrue("/k/tok123/vless/sub.png" in html)
        assertTrue("value=\"$sub\"" in html)
        assertTrue("Copy subscription link" in html)
        assertTrue("happ://add/$sub" in html)
        assertTrue("v2raytun://import/$sub" in html)
        assertTrue("play.google.com/store/apps/details?id=com.happproxy" in html)
        assertTrue("apps.apple.com/us/app/happ-proxy-utility/id6504287215" in html)
        assertTrue("apps.apple.com/ru/app/happ-proxy-utility-plus/id6746188973" in html)
        assertTrue("href=\"/download/windows\"" in html)
        assertTrue("version 1.5.1" in html)
    }

    @Test fun `Windows item is hidden without the installer`() {
        val html = Pages(Lang.RU).vlessKey(key(listOf(link)), pub, VlessApps(windows = false))
        assertFalse("/download/windows" in html)
        assertTrue("Скопировать ссылку на подписку" in html)
    }

    @Test fun `a disabled key has only the notice`() {
        val html = Pages(Lang.EN).vlessKey(key(emptyList()), pub, VlessApps())
        assertTrue("off until access is renewed" in html)
        assertFalse("sub.png" in html)
        assertFalse("data-copy" in html.substringBefore("<script>"))
        assertFalse("happ://" in html)
        assertTrue("play.google.com" in html)
    }
}
