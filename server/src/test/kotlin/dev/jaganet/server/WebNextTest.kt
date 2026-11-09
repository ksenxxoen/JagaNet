package dev.jaganet.server

import dev.jaganet.api.CreateOrderReq
import dev.jaganet.api.DeviceInfo
import dev.jaganet.api.Platform
import dev.jaganet.server.telegram.TelegramBot
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The new website (issues #118, #126, #127): WEB_UI=new (default) serves it on /, WEB_UI=legacy the old
 * one with the new as the preview on /next/; files in /ui/assets; the old files stay served in both modes.
 */
class WebNextTest {
    private val legacy = mapOf("WEB_UI" to "legacy")

    /** The old website's files: still served in both modes, so tabs opened before a switch keep working. */
    private val oldFiles = listOf("/app.js", "/control.js", "/telegram.js", "/invite.js", "/support.js", "/style.css", "/favicon.svg")

    private fun isNewSite(html: String) = """<div id="root">""" in html && Regex("""/ui/assets/site-[\w-]+\.js""").containsMatchIn(html)
    private fun isOldSite(html: String) = """<script src="/app.js"></script>""" in html && "/ui/assets/" !in html

    @Test fun `WEB_UI is new by default, legacy on request, and a typo stops the server`() {
        assertEquals(WebUi.NEW, Config.load(mapOf("JAGANET_MODE" to "test")).webUi)
        assertEquals(WebUi.NEW, Config.load(mapOf("JAGANET_MODE" to "test", "WEB_UI" to " New ")).webUi)
        assertEquals(WebUi.LEGACY, Config.load(mapOf("JAGANET_MODE" to "test", "WEB_UI" to "legacy")).webUi)
        assertEquals(WebUi.NEW, Config.load(mapOf("JAGANET_MODE" to "test", "WEB_UI" to "")).webUi)
        assertFailsWith<IllegalArgumentException> { Config.load(mapOf("JAGANET_MODE" to "test", "WEB_UI" to "legasy")) }
    }

    @Test fun `WEB_UI=new serves the new website on the root, the old files stay`() = harness {
        val c = b.createClient {}
        val r = c.get("/")
        assertEquals(HttpStatusCode.OK, r.status)
        val html = r.bodyAsText()
        assertTrue(isNewSite(html), html)
        // The main page is indexed: no noindex in the page or the headers; never cached, so a release shows up at once.
        assertFalse("noindex" in html, html)
        assertNull(r.headers["X-Robots-Tag"])
        assertTrue("no-cache" in r.headers["Cache-Control"]!!, r.headers["Cache-Control"])
        assertEquals(html, c.get("/index.html").bodyAsText())
        assertEquals(html, c.get("/?lang=de").bodyAsText())

        // Hashed assets under /ui/assets (the same for every page) with a long cache.
        val js = Regex("""/ui/assets/[\w.-]+\.js""").find(html)!!.value
        val a = c.get(js)
        assertEquals(HttpStatusCode.OK, a.status)
        assertTrue("immutable" in a.headers["Cache-Control"]!!)
        assertTrue("javascript" in a.headers["Content-Type"]!!, a.headers["Content-Type"])
        val css = Regex("""/ui/assets/[\w.-]+\.css""").find(html)!!.value
        assertTrue("text/css" in c.get(css).headers["Content-Type"]!!)

        for (f in oldFiles) assertEquals(HttpStatusCode.OK, c.get(f).status, f)
        // The server's own pages (/privacy, the key and checkout pages) keep their stylesheet.
        assertTrue("/style.css" in c.get("/privacy").bodyAsText())
        assertNoRawFiles(c)
    }

    @Test fun `WEB_UI=new sends the preview addresses to the main ones, keeping the fragment`() = harness {
        val c = b.createClient { followRedirects = false }
        for (path in listOf("/next", "/next/", "/next/index.html")) {
            val r = c.get(path)
            assertEquals(HttpStatusCode.OK, r.status, path)
            val html = r.bodyAsText()
            // The fragment never reaches the server: a script of our own replaces the address on the client.
            assertTrue("""<script src="/ui/to-main.js"></script>""" in html, html)
            assertTrue("""<a href="/">""" in html, html)
            assertEquals("noindex", r.headers["X-Robots-Tag"])
            assertEquals("no-store", r.headers["Cache-Control"])
        }
        val js = c.get("/ui/to-main.js")
        assertEquals(HttpStatusCode.OK, js.status)
        assertTrue("javascript" in js.headers["Content-Type"]!!)
        val code = js.bodyAsText()
        for (pair in listOf(""""/next/": "/"""", """"/tg/app-next": "/tg/app"""", """"/tg/open-next": "/tg/open"""")) assertTrue(pair in code, code)
        assertTrue("location.replace(" in code && "location.search + location.hash" in code, code)
    }

    @Test fun `WEB_UI=legacy keeps the old website on the root and the new one as the preview`() = harness(legacy) {
        val c = b.createClient {}
        val old = c.get("/").bodyAsText()
        assertTrue(isOldSite(old), old)
        assertEquals(old, c.get("/index.html").bodyAsText())
        for (f in oldFiles) assertEquals(HttpStatusCode.OK, c.get(f).status, f)

        val r = c.get("/next/")
        assertEquals(HttpStatusCode.OK, r.status)
        val html = r.bodyAsText()
        assertTrue(isNewSite(html), html)
        assertTrue("no-cache" in r.headers["Cache-Control"]!!, r.headers["Cache-Control"])
        assertEquals("noindex", r.headers["X-Robots-Tag"])
        // /next without the slash and /next/index.html lead to the same page.
        assertEquals("/next/", b.createClient { followRedirects = false }.get("/next").headers[HttpHeaders.Location])
        assertEquals(html, c.get("/next/index.html").bodyAsText())
        assertNoRawFiles(c)
    }

    /** Nothing outside the build's assets, no folders, and the mini app's templates are never served raw. */
    private suspend fun assertNoRawFiles(c: HttpClient) {
        // An encoded slash is refused before routing (400).
        assertTrue(c.get("/next/..%2Fweb%2Findex.html").status.value in setOf(400, 404))
        for (bad in listOf("/next/assets", "/next/missing.js", "/ui/assets", "/ui/assets/missing.js",
            "/ui/index.html", "/ui/miniapp.html", "/ui/tg-open.html", "/next/miniapp.html", "/ui/assets/.x.js", "/miniapp.html", "/tg-open.html")) {
            assertEquals(HttpStatusCode.NotFound, c.get(bad).status, bad)
        }
        assertTrue(c.get("/ui/assets/..%2F..%2Fminiapp.html").status.value in setOf(400, 404))
    }

    /* ---------------- every link the server and the bot make (W-10, item 3) ---------------- */

    /** Every JS file of the new build, as text: the routes and screens the links need must be in it. */
    private val builtJs: String by lazy {
        val loader = WebNextTest::class.java.classLoader
        val index = loader.getResourceAsStream("web-next/index.html")!!.use { String(it.readBytes()) }
        val seen = linkedSetOf<String>()
        val queue = ArrayDeque(Regex("""/ui/assets/([\w.-]+\.js)""").findAll(index).map { it.groupValues[1] }.toList())
        val out = StringBuilder()
        while (queue.isNotEmpty()) {
            val f = queue.removeFirst()
            if (!seen.add(f)) continue
            val js = loader.getResourceAsStream("web-next/assets/$f")?.use { String(it.readBytes()) } ?: continue
            out.append(js)
            queue.addAll(Regex("""(?:/ui/assets/|\./)([\w.-]+\.js)""").findAll(js).map { it.groupValues[1] })
        }
        out.toString()
    }

    @Test fun `every link the server and the bot make opens the new interface`() = harness(
        mapOf("TELEGRAM_BOT_TOKEN" to "123456:TEST-token", "TELEGRAM_MINIAPP" to "1", "PUBLIC_URL" to "https://boop.example"),
    ) {
        val base = "https://boop.example"
        val c = b.createClient {}
        val noFollow = b.createClient { followRedirects = false }
        val me = signIn("links@test.dev")
        val bot = TelegramBot(services, NumberedTelegram()).also { services.bot = it; it.username = "boop_test_bot" }

        // Links the server builds itself.
        val login = services.auth.createLoginLink(me.session.user.id, Duration.ofMinutes(10))
        val appLogin = services.auth.startAppLogin(DeviceInfo("Phone", Platform.ANDROID)).url
        val order = me.api.createOrder(CreateOrderReq(tariff(MONTH)))
        val paid = noFollow.post("/pay/test/${order.id}").headers[HttpHeaders.Location]!!
        val supportPage = noFollow.get("/support").headers[HttpHeaders.Location]!!
        val link = services.referrals.createLink(me.session.user.id, "Stories", "LINKS-TEST")
        val referral = noFollow.get("/r/${link.code}?lang=de").headers[HttpHeaders.Location]!!
        val websiteLinks = mapOf(
            login to "/login",
            login.replace("/#/", "/?lang=de#/") to "/login", // the bot's /login and POST /v1/telegram/login-link
            appLogin to "/app-login",
            "$base/#/account" to "/account",
            "$base$paid" to "/account",
            "$base/#/status" to "/status", // the monitor's alerts
            "$base$supportPage" to "/support",
            "$base/?lang=ru#/invite" to "/invite", // the bot's statistics button
            "$base/?lang=ru#/referrals" to "/referrals",
            "$base$referral" to "/",
        )
        assertEquals("/#/account?order=${order.id}", paid)
        assertEquals("/#/support", supportPage)
        assertEquals("/?ref=LINKS-TEST&lang=de", referral)
        assertTrue(login.startsWith("$base/#/login?token="), login)
        assertTrue(appLogin.startsWith("$base/#/app-login?id="), appLogin)
        for ((url, route) in websiteLinks) {
            assertTrue(url.startsWith(base), url)
            val path = url.removePrefix(base).substringBefore('#').ifEmpty { "/" }
            val r = c.get(path)
            assertEquals(HttpStatusCode.OK, r.status, url)
            assertTrue(isNewSite(r.bodyAsText()), "$url: not the new website")
            // The fragment's route is one of the new website's routes.
            assertTrue(Regex("""path:\s*["'`]${Regex.escape(route)}["'`]""").containsMatchIn(builtJs), "$url: the new website has no route $route")
        }

        // The bot's mini app buttons (#/pay, #/devices) open the new mini app on /tg/app.
        for (screen in listOf("pay", "devices", null)) {
            val url = bot.miniAppUrl(screen)!!
            assertEquals("$base/tg/app" + (screen?.let { "#/$it" } ?: ""), url)
            val r = c.get(url.removePrefix(base).substringBefore('#'))
            assertEquals(HttpStatusCode.OK, r.status, url)
            assertTrue(Regex("""/ui/assets/miniapp-[\w-]+\.js""").containsMatchIn(r.bodyAsText()), url)
        }
    }
}
