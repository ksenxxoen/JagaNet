package dev.jaganet.server

import dev.jaganet.api.CreateOrderReq
import dev.jaganet.api.DeviceInfo
import dev.jaganet.api.LinkLoginReq
import dev.jaganet.api.Platform
import dev.jaganet.api.Protocols
import dev.jaganet.api.TunnelProvisionReq
import dev.jaganet.api.WireGuard
import dev.jaganet.api.i18n.I18n
import dev.jaganet.server.http.MINIAPP_CSP
import dev.jaganet.server.telegram.TelegramAccounts
import dev.jaganet.server.telegram.TelegramBot
import dev.jaganet.server.telegram.TelegramConfig
import dev.jaganet.server.telegram.TgUser
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Telegram mini app (issue #87): initData check, sign-in, sessions, data access, the page and the bot. */
class MiniAppTest {
    private val token = "123456:TEST-token"
    private val on = mapOf("TELEGRAM_BOT_TOKEN" to token, "TELEGRAM_MINIAPP" to "1")
    private val onHttps = on + ("PUBLIC_URL" to "https://boop.example")

    /* ---------------- signing initData the way Telegram does (independent of the server's code) ---------------- */

    private fun hmac(key: ByteArray, msg: String): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(msg.toByteArray())
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun enc(v: String) = URLEncoder.encode(v, Charsets.UTF_8).replace("+", "%20")

    /** initData as Telegram builds it: the fields URL-encoded in [order], plus hash over the sorted, decoded fields. */
    private fun sign(fields: Map<String, String>, botToken: String = token, order: List<String> = fields.keys.toList()): String {
        val check = fields.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
        val hash = hex(hmac(hmac("WebAppData".toByteArray(), botToken), check))
        return (order.map { "$it=${enc(fields.getValue(it))}" } + "hash=$hash").joinToString("&")
    }

    private fun userJson(id: Long, first: String = "Иван", username: String? = "ivan_tg") =
        buildJsonObject { put("id", id); put("first_name", first); username?.let { put("username", it) }; put("language_code", "ru") }.toString()

    private fun fields(id: Long, at: Instant, extra: Map<String, String> = emptyMap()) =
        linkedMapOf("query_id" to "AAHdF6IQAAAAAN0XohDhrOrc", "user" to userJson(id), "auth_date" to at.epochSecond.toString()) + extra

    /* ---------------- verifyWebAppInitData ---------------- */

    private val at = Instant.ofEpochSecond(1792065600)

    @Test fun `initData signature matches Telegram's algorithm`() {
        // Signed independently (Python hmac/hashlib) for the same data, values URL-encoded as Telegram sends them.
        val raw = "query_id=AAHdF6IQAAAAAN0XohDhrOrc&user=%7B%22id%22%3A424242%2C%22first_name%22%3A%22%D0%98%D0%B2%D0%B0%D0%BD%22%2C%22last_name%22%3A%22%D0%9F%D0%B5%D1%82%D1%80%D0%BE%D0%B2%22%2C%22username%22%3A%22ivan_tg%22%2C%22language_code%22%3A%22ru%22%7D&auth_date=1792065600&start_param=ref_ABC&hash=92deb23d972d1f4ad0145bbdd89f90647a5d3806122f703222da2956c5af28ce"
        assertEquals(TgUser(424242, "ivan_tg", "Иван"), TelegramAccounts.verifyWebAppInitData(raw, token, at))
        assertEquals("ref_ABC", TelegramAccounts.webAppField(raw, "start_param"))
        // The same with another bot's token: refused.
        assertNull(TelegramAccounts.verifyWebAppInitData(raw, "654321:OTHER", at))
    }

    @Test fun `valid initData in any field order, with URL-encoded values`() {
        val f = fields(77, at, mapOf("start_param" to "a b&c=d", "chat_type" to "sender"))
        val u = TelegramAccounts.verifyWebAppInitData(sign(f), token, at)
        assertEquals(TgUser(77, "ivan_tg", "Иван"), u)
        // The order of the fields in the query string doesn't matter: the check string is sorted.
        assertEquals(u, TelegramAccounts.verifyWebAppInitData(sign(f, order = f.keys.reversed()), token, at))
        // "+" for spaces (form encoding) decodes the same.
        assertEquals(u, TelegramAccounts.verifyWebAppInitData(sign(f).replace("%20", "+"), token, at))
        assertEquals("a b&c=d", TelegramAccounts.webAppField(sign(f), "start_param"))
    }

    @Test fun `wrong hash, changed field, no hash, no user and repeated fields are refused`() {
        val raw = sign(fields(77, at))
        assertNull(TelegramAccounts.verifyWebAppInitData(raw.replace(Regex("hash=[0-9a-f]+"), "hash=" + "0".repeat(64)), token, at))
        assertNull(TelegramAccounts.verifyWebAppInitData(raw.replace(Regex("hash=[0-9a-f]+"), "hash=xyz"), token, at))
        // Another user id under the same signature.
        assertNull(TelegramAccounts.verifyWebAppInitData(raw.replace("%22id%22%3A77", "%22id%22%3A78"), token, at))
        // A field added after signing.
        assertNull(TelegramAccounts.verifyWebAppInitData("$raw&start_param=ref_X", token, at))
        assertNull(TelegramAccounts.verifyWebAppInitData(raw.substringBefore("&hash="), token, at))
        assertNull(TelegramAccounts.verifyWebAppInitData(sign(fields(77, at) - "user"), token, at))
        // A user without a numeric id.
        assertNull(TelegramAccounts.verifyWebAppInitData(sign(fields(77, at) + ("user" to """{"id":"77","first_name":"x"}""")), token, at))
        // The same key twice: refused rather than guessing which one counts.
        assertNull(TelegramAccounts.verifyWebAppInitData("auth_date=1&" + raw, token, at))
        assertNull(TelegramAccounts.verifyWebAppInitData("", token, at))
        assertNull(TelegramAccounts.verifyWebAppInitData("not a query", token, at))
    }

    @Test fun `auth_date at most 24 hours old and not from the future`() {
        val raw = sign(fields(77, at))
        assertNotNull(TelegramAccounts.verifyWebAppInitData(raw, token, at.plus(Duration.ofHours(23))))
        assertNull(TelegramAccounts.verifyWebAppInitData(raw, token, at.plus(Duration.ofHours(24)).plusSeconds(1)))
        assertNotNull(TelegramAccounts.verifyWebAppInitData(raw, token, at.minusSeconds(30)))
        assertNull(TelegramAccounts.verifyWebAppInitData(raw, token, at.minus(Duration.ofMinutes(5))))
        assertNull(TelegramAccounts.verifyWebAppInitData(sign(fields(77, at) - "auth_date"), token, at))
        assertEquals(Duration.ofHours(24), TelegramAccounts.WEBAPP_MAX_AGE)
        assertEquals(Duration.ofHours(12), TelegramAccounts.WEBAPP_SESSION_TTL)
    }

    /* ---------------- sign-in ---------------- */

    private suspend fun Harness.webapp(initData: String?): HttpResponse = b.createClient {}.post("/v1/auth/telegram/webapp") {
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject { initData?.let { put("initData", it) } }.toString())
    }

    private suspend fun Harness.signInTg(id: Long, extra: Map<String, String> = emptyMap()): String {
        val r = webapp(sign(fields(id, clock, extra)))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    private suspend fun Harness.n(q: String, vararg a: Any?) = ctx.db.run { it.one(q, *a)!!.int("n") }

    private suspend fun Harness.call(method: String, path: String, bearer: String, body: String? = null): HttpResponse {
        val c = b.createClient {}
        return when (method) {
            "GET" -> c.get(path) { header("Authorization", "Bearer $bearer") }
            "DELETE" -> c.delete(path) { header("Authorization", "Bearer $bearer") }
            "PATCH" -> c.patch(path) { header("Authorization", "Bearer $bearer"); contentType(ContentType.Application.Json); setBody(body ?: "{}") }
            "PUT" -> c.put(path) { header("Authorization", "Bearer $bearer"); contentType(ContentType.Application.Json); setBody(body ?: "{}") }
            else -> c.post(path) { header("Authorization", "Bearer $bearer"); contentType(ContentType.Application.Json); setBody(body ?: "{}") }
        }
    }

    @Test fun `sign-in makes or finds the account and adds no devices`() = harness(on) {
        val t1 = signInTg(501)
        val me = client(t1).me()
        assertEquals("tg501@telegram.invalid", me.user.email)
        assertEquals(0, me.usage.devicesUsed)
        // Opening the mini app again: the same account, the same one service device.
        val t2 = signInTg(501)
        assertTrue(t1 != t2)
        assertEquals(me.user.id, client(t2).me().user.id)
        assertEquals(1, n("SELECT count(*)::int AS n FROM users WHERE telegram_id=501"))
        assertEquals(1, n("SELECT count(*)::int AS n FROM devices WHERE user_id=?::uuid", me.user.id))
        assertEquals(1, n("SELECT count(*)::int AS n FROM devices WHERE user_id=?::uuid AND service='tg_miniapp' AND kind='device'", me.user.id))
        // Never listed as a device, not even for an app that runs VLESS, and not counted.
        assertTrue(client(t2).devices().devices.isEmpty())
        assertTrue(client(t2).devices(listOf("amneziawg", "vless")).devices.isEmpty())
        // Its id can't be renamed or deleted as a device.
        val dev = ctx.db.run { it.one("SELECT id::text AS id FROM devices WHERE user_id=?::uuid", me.user.id) }!!.str("id")
        assertEquals("NOT_FOUND", code { client(t2).renameDevice(dev, "x") })
        assertEquals("NOT_FOUND", code { client(t2).removeDevice(dev) })
        // A mini app session is not a device: it never gets a tunnel.
        assertEquals("FORBIDDEN", code { client(t2).provisionTunnel(TunnelProvisionReq(Protocols.AMNEZIAWG, clientParams = Protocols.encode(WireGuard.ClientParams(key(5))))) })
        assertEquals(0, n("SELECT count(*)::int AS n FROM tunnels"))
        // The same Telegram through the bot gets the same account.
        assertEquals(me.user.id, TelegramAccounts(ctx, services.auth, services.tunnels).findOrCreate(TgUser(501)).userId)
    }

    @Test fun `bad, forged or stale initData gets 401 without details`() = harness(on) {
        for (bad in listOf(
            sign(fields(502, clock), "999:attacker-bot"),
            sign(fields(502, clock)).replace("%22id%22%3A502", "%22id%22%3A503"),
            sign(fields(502, clock.minus(Duration.ofHours(25)))),
            sign(fields(502, clock) - "user"),
            "",
            null,
        )) {
            val r = webapp(bad)
            assertEquals(HttpStatusCode.Unauthorized, r.status, bad)
            assertTrue("UNAUTHORIZED" in r.bodyAsText(), r.bodyAsText())
        }
        assertEquals(0, n("SELECT count(*)::int AS n FROM users WHERE telegram_id IN (502, 503)"))
    }

    @Test fun `503 while the bot or the mini app is off`() {
        harness(mapOf("TELEGRAM_BOT_TOKEN" to token)) {
            assertEquals(HttpStatusCode.ServiceUnavailable, webapp(sign(fields(504, clock))).status)
            assertEquals(HttpStatusCode.NotFound, b.createClient {}.get("/tg/app").status)
            assertEquals(HttpStatusCode.NotFound, b.createClient {}.get("/tg/app-next").status)
            assertEquals(HttpStatusCode.NotFound, b.createClient {}.get("/tg/open-next").status)
        }
        harness(mapOf("TELEGRAM_MINIAPP" to "1")) {
            assertEquals(HttpStatusCode.ServiceUnavailable, webapp(sign(fields(504, clock))).status)
        }
    }

    @Test fun `mini app sessions end after 12 hours, other sessions don't`() = harness(on) {
        val mail = signIn("old@example.com")
        val tok = signInTg(505)
        assertEquals("OK", code { client(tok).me() })
        clock = clock.plus(Duration.ofHours(11))
        assertEquals("OK", code { client(tok).me() })
        clock = clock.plus(Duration.ofHours(1))
        assertEquals("UNAUTHORIZED", code { client(tok).me() })
        // Sessions without an end (the website's, the apps', all made before V25) work as before.
        assertEquals("OK", code { mail.api.me() })
        assertEquals(1, n("SELECT count(*)::int AS n FROM sessions WHERE expires_at IS NULL"))
        // Signing in again works, and the ended session is cleaned up.
        val again = signInTg(505)
        assertEquals("OK", code { client(again).me() })
        assertEquals(1, n("SELECT count(*)::int AS n FROM sessions s JOIN devices d ON d.id = s.device_id WHERE d.service='tg_miniapp'"))
    }

    @Test fun `a user blocked by the admin gets 403`() = harness(on) {
        val tok = signInTg(506)
        ctx.db.run { it.exec("UPDATE users SET blocked_at=now(), blocked_reason='test' WHERE telegram_id=506") }
        val r = webapp(sign(fields(506, clock)))
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals("FORBIDDEN", code { client(tok).me() })
    }

    @Test fun `start_param with a referral code remembers who invited`() = harness(on) {
        val inviter = signIn("inviter@example.com")
        val code = services.referrals.stats(inviter.session.user.id, dev.jaganet.api.ReferralPeriod.D30).links.first { it.main }.code
        signInTg(507, mapOf("start_param" to "ref_$code"))
        assertEquals(inviter.session.user.id, ctx.db.run { it.one("SELECT referred_by::text AS r FROM users WHERE telegram_id=507") }!!.str("r"))
        // A screen is not a referral code.
        signInTg(508, mapOf("start_param" to "devices"))
        assertNull(ctx.db.run { it.one("SELECT tg_start_ref FROM users WHERE telegram_id=508") }!!.strOrNull("tg_start_ref"))
    }

    @Test fun `someone else's devices, keys and orders are out of reach`() = harness(on) {
        val a = signInTg(509)
        val other = signIn("victim@example.com", "Phone")
        other.api.provisionTunnel(TunnelProvisionReq(Protocols.AMNEZIAWG, clientParams = Protocols.encode(WireGuard.ClientParams(key(6)))))
        val order = buy(other.api)
        val k = other.api.keys().keys.first()
        val phone = other.session.deviceId

        assertEquals(HttpStatusCode.NotFound, call("GET", "/v1/orders/${order.id}", a).status)
        assertEquals(HttpStatusCode.NotFound, call("PATCH", "/v1/devices/$phone", a, """{"name":"mine now"}""").status)
        assertEquals(HttpStatusCode.NotFound, call("DELETE", "/v1/devices/$phone", a).status)
        assertEquals(HttpStatusCode.NotFound, call("PATCH", "/v1/devices/${k.id}", a, """{"name":"mine now"}""").status)
        assertEquals(HttpStatusCode.NotFound, call("DELETE", "/v1/keys/${k.id}", a).status)
        assertEquals(HttpStatusCode.NotFound, call("GET", "/v1/keys/${k.id}/tunnel", a).status)
        val keys = Json.parseToJsonElement(call("GET", "/v1/keys?supportedProtocols=amneziawg,vless", a).bodyAsText()).jsonObject
        assertTrue(keys["keys"]!!.jsonArray.isEmpty())
        val devs = Json.parseToJsonElement(call("GET", "/v1/devices", a).bodyAsText()).jsonObject
        assertTrue(devs["devices"]!!.jsonArray.isEmpty())
        // Nothing of the other account changed.
        assertEquals(listOf("Phone", "VPN-ключ"), other.api.devices().devices.map { it.name }.sorted())
        assertEquals(1, other.api.keys().keys.size)
        assertEquals("OK", code { other.api.order(order.id) })
    }

    @Test fun `orders from the mini app are Telegram orders, settings and the website link work`() = harness(on) {
        val tok = signInTg(510)
        val r = call("POST", "/v1/orders", tok, """{"tariffId":"${tariff(MONTH)}","channel":"telegram"}""")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val id = Json.parseToJsonElement(r.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        assertEquals("telegram", ctx.db.run { it.one("SELECT channel FROM orders WHERE id=?::uuid", id) }!!.str("channel"))
        services.payments.markPaid(id)
        assertEquals("paid", Json.parseToJsonElement(call("GET", "/v1/orders/$id", tok).bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)

        val p = Json.parseToJsonElement(call("GET", "/v1/telegram", tok).bodyAsText()).jsonObject
        assertEquals("ru", p["lang"]!!.jsonPrimitive.content)
        assertEquals("true", p["notify"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, call("PUT", "/v1/me/lang", tok, """{"lang":"de"}""").status)
        assertEquals(HttpStatusCode.BadRequest, call("PUT", "/v1/me/lang", tok, """{"lang":"xx"}""").status)
        assertEquals(HttpStatusCode.OK, call("PUT", "/v1/telegram/notify", tok, """{"notify":false}""").status)
        val p2 = Json.parseToJsonElement(call("GET", "/v1/telegram", tok).bodyAsText()).jsonObject
        assertEquals("de", p2["lang"]!!.jsonPrimitive.content)
        assertEquals("false", p2["notify"]!!.jsonPrimitive.content)

        val link = Json.parseToJsonElement(call("POST", "/v1/telegram/login-link", tok).bodyAsText()).jsonObject["url"]!!.jsonPrimitive.content
        assertTrue("#/login?token=" in link, link)
        assertEquals("tg510@telegram.invalid", client().redeemLoginLink(LinkLoginReq(link.substringAfter("token="), DeviceInfo("Website", Platform.OTHER))).user.email)
    }

    /* ---------------- the page ---------------- */

    @Test fun `GET tg app serves the page with its CSP`() = harness(on) {
        val c = b.createClient {}
        val r = c.get("/tg/app")
        assertEquals(HttpStatusCode.OK, r.status)
        val html = r.bodyAsText()
        assertTrue("https://telegram.org/js/telegram-web-app.js" in html)
        assertTrue(Regex("""/tg/app\.js\?v=[0-9a-f]{12}""").containsMatchIn(html), html)
        assertTrue("Откройте Boop в Telegram" in html, html)
        assertFalse("{{" in html, html)
        val csp = r.headers["Content-Security-Policy"]!!
        assertTrue("script-src 'self' https://telegram.org;" in csp, csp)
        assertTrue("default-src 'self'" in csp && "style-src 'self';" in csp && "frame-ancestors https://web.telegram.org" in csp, csp)
        assertEquals("nosniff", r.headers["X-Content-Type-Options"])
        val v = Regex("""app\.js\?v=([0-9a-f]+)""").find(html)!!.groupValues[1]
        val js = c.get("/tg/app.js?v=$v")
        assertEquals(HttpStatusCode.OK, js.status)
        assertTrue("immutable" in js.headers["Cache-Control"]!!)
        assertTrue("initData" in js.bodyAsText())
        assertEquals("no-cache", c.get("/tg/app.js").headers["Cache-Control"])
        assertEquals(HttpStatusCode.OK, c.get("/tg/app.css?v=$v").status)
        // The raw template is never served.
        assertEquals("/tg/app", b.createClient { followRedirects = false }.get("/tg/app.html").headers["Location"])
        val open = c.get("/tg/open")
        assertEquals(HttpStatusCode.OK, open.status)
        assertEquals("no-store", open.headers["Cache-Control"])
        assertTrue("/tg/open.js?v=$v" in open.bodyAsText())
        // German when asked.
        assertTrue("Öffne Boop in Telegram" in c.get("/tg/app?lang=de").bodyAsText())
    }

    @Test fun `the mini app's texts are translated and keep the house style`() {
        val known = I18n.all.map { it.en }.toSet()
        val dir = File("src/main/resources/web/tg")
        // The old pages and the new templates (web/miniapp.html, web/tg-open.html).
        val pages = dir.listFiles()!!.filter { it.extension == "html" } + listOf(File("../web/miniapp.html"), File("../web/tg-open.html"))
        val texts = pages.flatMap { f -> Regex("""\{\{t:([^}]+)}}""").findAll(f.readText()).map { it.groupValues[1] }.toList() }
        assertTrue(texts.count { it == "Open Boop in Telegram" } == 2, texts.toString())
        assertTrue(texts.isNotEmpty())
        assertTrue(texts.all { it in known }, (texts - known).toString())
        // The token stays in memory: never in localStorage, sessionStorage or cookies.
        val js = File(dir, "app.js").readText()
        for (bad in listOf("localStorage", "sessionStorage", "document.cookie")) assertFalse(bad in js, bad)
    }

    /* ---------------- the new mini app (web/, preview on /tg/app-next, #126) ---------------- */

    @Test fun `GET tg app-next serves the new mini app with the same template and CSP`() = harness(on) {
        val c = b.createClient {}
        val old = c.get("/tg/app")
        val r = c.get("/tg/app-next")
        assertEquals(HttpStatusCode.OK, r.status)
        val html = r.bodyAsText()
        // The same CSP, headers and caching as /tg/app; Telegram's script first, then ours from /ui/assets.
        assertEquals(old.headers["Content-Security-Policy"], r.headers["Content-Security-Policy"])
        assertEquals(MINIAPP_CSP, r.headers["Content-Security-Policy"])
        assertEquals("nosniff", r.headers["X-Content-Type-Options"])
        assertEquals("no-referrer", r.headers["Referrer-Policy"])
        assertEquals("noindex", r.headers["X-Robots-Tag"])
        assertEquals("no-cache", r.headers["Cache-Control"])
        assertTrue(html.indexOf("https://telegram.org/js/telegram-web-app.js") in 0 until html.indexOf("/ui/assets/miniapp-"), html)
        assertTrue("""<html lang="ru" data-bot="">""" in html, html)
        assertTrue("Откройте Boop в Telegram" in html, html)
        assertFalse("{{" in html, html)
        // No inline scripts or styles: every <script> has a src, no style attributes or <style>.
        assertFalse(Regex("""<script(?![^>]*\bsrc=)[^>]*>""").containsMatchIn(html), html)
        assertFalse(Regex("""<style[\s>]|\sstyle=""").containsMatchIn(html), html)
        assertFalse("data:" in html, html)
        for (asset in Regex("""/ui/assets/[\w.-]+\.(js|css)""").findAll(html).map { it.value }.toSet()) {
            val a = c.get(asset)
            assertEquals(HttpStatusCode.OK, a.status, asset)
            assertTrue("immutable" in a.headers["Cache-Control"]!!, asset)
        }
        assertTrue("Öffne Boop in Telegram" in c.get("/tg/app-next?lang=de").bodyAsText())

        val open = c.get("/tg/open-next")
        assertEquals(HttpStatusCode.OK, open.status)
        assertEquals(MINIAPP_CSP, open.headers["Content-Security-Policy"])
        assertEquals("no-store", open.headers["Cache-Control"])
        val openHtml = open.bodyAsText()
        assertTrue(Regex("""/ui/assets/tg-open-[\w-]+\.js""").containsMatchIn(openHtml), openHtml)
        assertTrue("Открываем приложение" in openHtml, openHtml)
        assertFalse("{{" in openHtml, openHtml)
        assertFalse(Regex("""<script(?![^>]*\bsrc=)[^>]*>""").containsMatchIn(openHtml), openHtml)

        // The old mini app is unchanged.
        assertTrue(Regex("""/tg/app\.js\?v=[0-9a-f]{12}""").containsMatchIn(old.bodyAsText()))
    }

    @Test fun `the bot's name goes into the new mini app's page`() = harness(on) {
        TelegramBot(services, NumberedTelegram()).also { services.bot = it; it.username = "boop_test_bot" }
        assertTrue("""data-bot="boop_test_bot"""" in b.createClient {}.get("/tg/app-next").bodyAsText())
    }

    /** Every JS file the page loads, following static and dynamic imports between the built files. */
    private fun builtJs(page: String): Map<String, String> {
        fun res(path: String) = MiniAppTest::class.java.classLoader.getResourceAsStream("web-next/$path")?.use { String(it.readBytes()) }
        val html = res(page) ?: error("web-next/$page is not built")
        val ref = Regex("""(?:/ui/assets/|\./)([\w.-]+\.js)""")
        val out = linkedMapOf<String, String>()
        val queue = ArrayDeque(ref.findAll(html).map { it.groupValues[1] }.toList())
        while (queue.isNotEmpty()) {
            val f = queue.removeFirst()
            if (f in out) continue
            val js = res("assets/$f") ?: continue
            out[f] = js
            queue.addAll(ref.findAll(js).map { it.groupValues[1] })
        }
        return out
    }

    @Test fun `the new mini app's built files have no web storage, no cookies and none of the website's screens`() {
        for (page in listOf("miniapp.html", "tg-open.html")) {
            val files = builtJs(page)
            assertTrue(files.keys.any { it.startsWith(page.substringBefore(".html") + "-") }, files.keys.toString())
            for ((f, js) in files) for (bad in listOf("localStorage", "sessionStorage", "document.cookie")) assertFalse(bad in js, "$page: $f has $bad")
            // The website's libraries and lazy screens stay out (admin, status, analytics, Swagger UI, the router).
            assertTrue(files.keys.none { Regex("""^(vendor|ApiDocs|Nodes|status|analytics|Dashboard|Landing|site)-""").containsMatchIn(it) }, files.keys.toString())
        }
        // The QR code is drawn on canvas in the mini app: no data: images in its scripts.
        assertTrue(builtJs("miniapp.html").values.none { "data:image" in it })
    }

    /* ---------------- the bot ---------------- */

    private fun Harness.bot(tg: NumberedTelegram) = TelegramBot(services, tg).also { services.bot = it; it.username = "boop_test_bot" }

    private fun JsonObject.webApps(): List<String> =
        (this["reply_markup"]?.jsonObject?.get("inline_keyboard")?.jsonArray ?: emptyList()).flatMap { it.jsonArray }
            .mapNotNull { it.jsonObject["web_app"]?.jsonObject?.get("url")?.jsonPrimitive?.content }

    @Test fun `the menu and the menu button open the mini app when it is on`() = harness(onHttps) {
        val tg = NumberedTelegram()
        val bot = bot(tg)
        bot.handle(tgMessage(601, "/start"))
        val menu = tg.sent("sendMessage").last()
        assertEquals(listOf("https://boop.example/tg/app"), menu.webApps())
        val first = menu["reply_markup"]!!.jsonObject["inline_keyboard"]!!.jsonArray.first().jsonArray.single().jsonObject
        assertEquals("📱 Открыть Boop", first["text"]!!.jsonPrimitive.content)
        bot.prepare()
        val mb = tg.sent("setChatMenuButton").single()["menu_button"]!!.jsonObject
        assertEquals("web_app", mb["type"]!!.jsonPrimitive.content)
        assertEquals("Открыть", mb["text"]!!.jsonPrimitive.content)
        assertEquals("https://boop.example/tg/app", mb["web_app"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        // Status and the keys message carry it once, the reminder opens the payment screen.
        bot.handle(tgMessage(601, "/status"))
        assertEquals(1, tg.sent("sendMessage").last().webApps().size)
        val uid = ctx.db.run { it.one("SELECT id::text AS id FROM users WHERE telegram_id=601") }!!.str("id")
        ctx.db.run {
            it.exec(
                "INSERT INTO subscriptions (user_id, product_id, source, external_id, started_at, expires_at, auto_renew, tariff_name, device_limit) VALUES (?::uuid,'t','web','x',?,?,false,'Pro',5)",
                uid, clock.minus(Duration.ofDays(20)), clock.plus(Duration.ofHours(30)),
            )
        }
        assertEquals(1, bot.remind())
        assertEquals(listOf("https://boop.example/tg/app#/pay"), tg.sent("sendMessage").last().webApps())
        // /start devices opens the devices screen of the bot.
        bot.handle(tgMessage(601, "/start devices"))
        assertTrue(tg.texts(601).last().startsWith("📱 Устройства"), tg.texts(601).last())
    }

    @Test fun `no mini app buttons while it is off, and the menu button goes back to default`() = harness(mapOf("PUBLIC_URL" to "https://boop.example", "TELEGRAM_BOT_TOKEN" to token)) {
        val tg = NumberedTelegram()
        val bot = bot(tg)
        bot.handle(tgMessage(602, "/start"))
        assertTrue(tg.sent("sendMessage").last().webApps().isEmpty())
        assertTrue("Открыть Boop" !in tg.sent("sendMessage").last().toString())
        bot.prepare()
        assertEquals("default", tg.sent("setChatMenuButton").single()["menu_button"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        // A plain-http address can't be a web_app button: the bot keeps its usual buttons.
        assertNull(TelegramConfig(miniApp = true).miniAppUrl("http://localhost:4000"))
        assertEquals("https://x.example/app", TelegramConfig(miniApp = true, miniAppUrl = "https://x.example/app").miniAppUrl("http://localhost:4000"))
        assertNull(TelegramConfig(miniApp = false).miniAppUrl("https://boop.example"))
    }

    @Test fun `a new device is announced once, never with notifications off`() = harness(onHttps) {
        val tg = NumberedTelegram()
        val bot = bot(tg)
        bot.handle(tgMessage(603, "/start"))
        services.payments.markPaid(services.payments.create(ctx.db.run { it.one("SELECT id::text AS id FROM users WHERE telegram_id=603") }!!.str("id"), tariff(MONTH), dev.jaganet.server.services.Channel.WEB).id)
        // The mini app's own sign-in is not a new device.
        signInTg(603)
        tg.calls.clear()
        // A phone with the Boop app, signed in through the bot's login link, connects.
        bot.handle(tgMessage(603, "/login"))
        val s = client().redeemLoginLink(LinkLoginReq(tg.sent("sendMessage").last()["reply_markup"].toString().substringAfter("token=").substringBefore("\""), DeviceInfo("Pixel", Platform.ANDROID)))
        tg.calls.clear()
        val app = client(s.token)
        app.provisionTunnel(TunnelProvisionReq(Protocols.AMNEZIAWG, clientParams = Protocols.encode(WireGuard.ClientParams(key(7)))))
        val notes = tg.texts(603).filter { "новое устройство" in it }
        assertEquals(1, notes.size, tg.texts(603).toString())
        assertTrue("Pixel" in notes.single(), notes.single())
        assertEquals(listOf("https://boop.example/tg/app#/devices"), tg.sent("sendMessage").last().webApps())
        // Reconnecting (a new key on the same device) says nothing more.
        app.provisionTunnel(TunnelProvisionReq(Protocols.AMNEZIAWG, clientParams = Protocols.encode(WireGuard.ClientParams(key(8)))))
        assertEquals(1, tg.texts(603).count { "новое устройство" in it })
        assertEquals(1, n("SELECT count(*)::int AS n FROM telegram_device_notices"))

        // Notifications off: a second phone connects silently.
        ctx.db.run { it.exec("UPDATE users SET tg_notify=false WHERE telegram_id=603") }
        bot.handle(tgMessage(603, "/login"))
        val s2 = client().redeemLoginLink(LinkLoginReq(tg.sent("sendMessage").last()["reply_markup"].toString().substringAfter("token=").substringBefore("\""), DeviceInfo("iPad", Platform.IOS)))
        tg.calls.clear()
        client(s2.token).provisionTunnel(TunnelProvisionReq(Protocols.AMNEZIAWG, clientParams = Protocols.encode(WireGuard.ClientParams(key(9)))))
        assertTrue(tg.texts(603).none { "новое устройство" in it }, tg.texts(603).toString())
        // Not for the mini app's service device (no tunnel, not the user's device), nor for unknown ids.
        val service = ctx.db.run { it.one("SELECT id::text AS id FROM devices WHERE service='tg_miniapp'") }!!.str("id")
        ctx.db.run { it.exec("UPDATE users SET tg_notify=true WHERE telegram_id=603") }
        assertFalse(bot.notifyNewDevice(s.user.id, service))
        assertFalse(bot.notifyNewDevice(s.user.id, "00000000-0000-0000-0000-000000000000"))
    }
}
