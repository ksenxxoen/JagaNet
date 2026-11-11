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
import java.io.File
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The web interface (epic #117): the React build on / with its files in /ui/assets (#118, #127), the
 * preview addresses of the switch leading to the main ones, the server's own pages with pages.css, and
 * no trace of the old website (#128).
 */
class WebUiTest {
    private fun isSite(html: String) = """<div id="root">""" in html && Regex("""/ui/assets/site-[\w-]+\.js""").containsMatchIn(html)

    @Test fun `the website is served on the root`() = harness {
        val c = b.createClient {}
        val r = c.get("/")
        assertEquals(HttpStatusCode.OK, r.status)
        val html = r.bodyAsText()
        assertTrue(isSite(html), html)
        // The main page is indexed: no noindex in the page or the headers; never cached, so a release shows up at once.
        assertFalse("noindex" in html, html)
        assertNull(r.headers["X-Robots-Tag"])
        assertTrue("no-cache" in r.headers["Cache-Control"]!!, r.headers["Cache-Control"])
        // The same page for every address the hash routes and query parameters live on (?lang=, ?ref=).
        assertEquals(html, c.get("/index.html").bodyAsText())
        assertEquals(html, c.get("/?lang=de").bodyAsText())
        assertEquals(html, c.get("/?ref=FRIEND").bodyAsText())

        // Hashed assets under /ui/assets (the same for every page) with a long cache.
        val js = Regex("""/ui/assets/[\w.-]+\.js""").find(html)!!.value
        val a = c.get(js)
        assertEquals(HttpStatusCode.OK, a.status)
        assertTrue("immutable" in a.headers["Cache-Control"]!!)
        assertTrue("javascript" in a.headers["Content-Type"]!!, a.headers["Content-Type"])
        val css = Regex("""/ui/assets/[\w.-]+\.css""").find(html)!!.value
        assertTrue("text/css" in c.get(css).headers["Content-Type"]!!)
        assertEquals(HttpStatusCode.OK, c.get("/favicon.svg").status)
        assertNoRawFiles(c)
    }

    @Test fun `the old website is gone`() = harness {
        val c = b.createClient {}
        for (f in listOf("/app.js", "/control.js", "/telegram.js", "/invite.js", "/support.js", "/style.css", "/tg/app.js", "/tg/app.css", "/tg/open.js")) {
            assertEquals(HttpStatusCode.NotFound, c.get(f).status, f)
        }
        // Only the server pages' files stay in resources/web (no scripts, no pages of the old website).
        val left = File("src/main/resources/web").walk().filter { it.isFile }.map { it.relativeTo(File("src/main/resources/web")).invariantSeparatorsPath }.toSet()
        assertEquals(setOf("favicon.svg", "pages.css"), left)
    }

    @Test fun `the preview addresses lead to the main ones, keeping the fragment`() = harness {
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
        assertNoRawFiles(c)
    }

    /* ---------------- the server's own pages (/k/…, payment, /privacy, /terms) ---------------- */

    @Test fun `the server's own pages keep their stylesheet and icon`() = harness(mapOf("PUBLIC_URL" to "https://boop.example")) {
        val c = b.createClient {}
        val css = c.get("/pages.css")
        assertEquals(HttpStatusCode.OK, css.status)
        assertTrue("text/css" in css.headers["Content-Type"]!!, css.headers["Content-Type"])
        val rules = css.bodyAsText()
        val u = signIn("pages@test.dev")
        buy(u.api)
        val key = u.api.createKey().pageUrl.substringAfter("https://boop.example")
        val order = u.api.createOrder(CreateOrderReq(tariff(MONTH)))
        for (page in listOf("/privacy", "/terms", key, "/pay/test/${order.id}")) {
            val r = c.get(page)
            assertEquals(HttpStatusCode.OK, r.status, page)
            val html = r.bodyAsText()
            assertTrue("""<link rel="stylesheet" href="/pages.css">""" in html, "$page: $html")
            assertTrue("""<link rel="icon" href="/favicon.svg"""" in html, page)
            // Every class the page uses has its rule in pages.css.
            val classes = Regex("""class="([^"]*)"""").findAll(html).flatMap { it.groupValues[1].split(' ') }.filter { it.isNotBlank() }.toSet()
            val missing = classes.filterNot { Regex("""\.${Regex.escape(it)}(?![\w-])""").containsMatchIn(rules) }
            assertTrue(missing.isEmpty(), "$page: no rules for $missing")
        }
    }

    @Test fun `pages css uses the colors of the Tailwind theme`() {
        // web/src/styles/tokens.ts is the one source of the design tokens (docs/DESIGN.md); pages.css repeats the ones it needs.
        val ts = File("../web/src/styles/tokens.ts").readText()
        fun tokens(block: String) = Regex(""""(--[\w-]+)":\s*"([^"]+)"""").findAll(block).associate { it.groupValues[1] to it.groupValues[2] }
        val light = tokens(ts.substringAfter("export const light").substringBefore("export const dark"))
        val dark = tokens(ts.substringAfter("export const dark"))
        val css = File("src/main/resources/web/pages.css").readText()
        fun cssTokens(block: String) = Regex("""(--[\w-]+):\s*([^;]+);""").findAll(block).associate { it.groupValues[1] to it.groupValues[2].trim() }
        val cssLight = cssTokens(css.substringAfter(":root {").substringBefore("}"))
        val cssDark = cssTokens(css.substringAfter("prefers-color-scheme: dark").substringAfter(":root {").substringBefore("}"))
        assertTrue(cssLight.size > 10 && cssDark.size > 10, "$cssLight $cssDark")
        for ((name, value) in cssLight) light[name]?.let { assertEquals(it, value, "light $name") }
        for ((name, value) in cssDark) dark[name]?.let { assertEquals(it, value, "dark $name") }
        // Colors: only tokens of the theme, nothing of their own.
        val own = (cssLight + cssDark).keys.filter { it !in light && it !in dark && !it.startsWith("--r-") && it !in setOf("--ctl", "--gap", "--page", "--font", "--mono") }
        assertTrue(own.isEmpty(), "Tokens that are not in tokens.ts: $own")
        assertFalse("gradient(" in css, "No gradients (docs/DESIGN.md)")
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

    /** Every JS file of the build, as text: the routes and screens the links need must be in it. */
    private val builtJs: String by lazy {
        val loader = WebUiTest::class.java.classLoader
        val index = loader.getResourceAsStream("web-ui/index.html")!!.use { String(it.readBytes()) }
        val seen = linkedSetOf<String>()
        val queue = ArrayDeque(Regex("""/ui/assets/([\w.-]+\.js)""").findAll(index).map { it.groupValues[1] }.toList())
        val out = StringBuilder()
        while (queue.isNotEmpty()) {
            val f = queue.removeFirst()
            if (!seen.add(f)) continue
            val js = loader.getResourceAsStream("web-ui/assets/$f")?.use { String(it.readBytes()) } ?: continue
            out.append(js)
            queue.addAll(Regex("""(?:/ui/assets/|\./)([\w.-]+\.js)""").findAll(js).map { it.groupValues[1] })
        }
        out.toString()
    }

    @Test fun `every link the server and the bot make opens its screen of the website`() = harness(
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
            assertTrue(isSite(r.bodyAsText()), "$url: not the website")
            // The fragment's route is one of the website's routes.
            assertTrue(Regex("""path:\s*["'`]${Regex.escape(route)}["'`]""").containsMatchIn(builtJs), "$url: the website has no route $route")
        }

        // The bot's mini app buttons (#/pay, #/devices) open the mini app on /tg/app.
        for (screen in listOf("pay", "devices", null)) {
            val url = bot.miniAppUrl(screen)!!
            assertEquals("$base/tg/app" + (screen?.let { "#/$it" } ?: ""), url)
            val r = c.get(url.removePrefix(base).substringBefore('#'))
            assertEquals(HttpStatusCode.OK, r.status, url)
            assertTrue(Regex("""/ui/assets/miniapp-[\w-]+\.js""").containsMatchIn(r.bodyAsText()), url)
        }
    }
}
