package dev.jaganet.server

import dev.jaganet.api.DeviceInfo
import dev.jaganet.api.LinkLoginReq
import dev.jaganet.api.Platform
import dev.jaganet.api.Protocols
import dev.jaganet.api.TunnelProvisionReq
import dev.jaganet.api.Vless
import dev.jaganet.api.WireGuard
import dev.jaganet.server.db.Jsonb
import dev.jaganet.server.telegram.StartPayload
import dev.jaganet.server.telegram.TelegramAccounts
import dev.jaganet.server.telegram.TelegramApi
import dev.jaganet.server.telegram.TelegramBot
import dev.jaganet.server.telegram.TelegramException
import dev.jaganet.server.telegram.Upload
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import dev.jaganet.server.telegram.TelegramConfig
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A fake Bot API that numbers sent messages (support needs message ids) and can play "Telegram is down". */
class NumberedTelegram : TelegramApi {
    val calls = mutableListOf<Pair<String, JsonObject>>()
    val uploads = mutableListOf<Pair<String, Upload>>()
    var down = false
    /** Errors to throw for one method (sendWidget, editMessageWidgetData…), as Telegram would answer. */
    val fail = mutableMapOf<String, TelegramException>()
    private var next = 100L
    fun sent(method: String) = calls.filter { it.first == method }.map { it.second }
    fun texts(chat: Long) = calls.filter { it.first == "sendMessage" && it.second["chat_id"]!!.jsonPrimitive.content == chat.toString() }
        .map { it.second["text"]!!.jsonPrimitive.content }

    override suspend fun call(method: String, params: JsonObject, upload: Upload?): JsonElement {
        if (down) throw TelegramException("$method: ConnectException", -1)
        calls += method to params
        fail[method]?.let { throw it }
        upload?.let { uploads += method to it }
        return when (method) {
            "getMe" -> buildJsonObject { put("username", "boop_test_bot") }
            "sendMessage", "sendPhoto", "sendDocument", "sendWidget", "forwardMessage", "copyMessage" -> buildJsonObject { put("message_id", next++) }
            else -> JsonObject(emptyMap())
        }
    }
}

private fun from(id: Long, username: String? = null) = buildJsonObject {
    put("id", id); put("is_bot", false); put("first_name", "T"); username?.let { put("username", it) }
}

private var updateId = 1L

fun tgMessage(chat: Long, text: String, fromId: Long = chat, messageId: Long = 1, replyTo: Long? = null, type: String = "private") = buildJsonObject {
    put("update_id", updateId++)
    putJsonObject("message") {
        put("message_id", messageId); put("from", from(fromId)); putJsonObject("chat") { put("id", chat); put("type", type) }; put("text", text)
        replyTo?.let { putJsonObject("reply_to_message") { put("message_id", it) } }
    }
}

fun tgCallback(chat: Long, data: String) = buildJsonObject {
    put("update_id", updateId++)
    putJsonObject("callback_query") {
        put("id", "q$updateId"); put("from", from(chat)); put("data", data)
        putJsonObject("message") { put("message_id", 2); putJsonObject("chat") { put("id", chat); put("type", "private") } }
    }
}

/** Every inline button of the last message: text to callback data, URL or mini app URL. */
fun JsonObject.buttons(): List<Pair<String, String>> =
    (this["reply_markup"]?.jsonObject?.get("inline_keyboard")?.jsonArray ?: emptyList()).flatMap { it.jsonArray }.map { b ->
        val o = b.jsonObject
        o["text"]!!.jsonPrimitive.content to (o["callback_data"] ?: o["url"] ?: o["web_app"]?.jsonObject?.get("url"))!!.jsonPrimitive.content
    }

class TelegramBotTest {
    private val token = "123456:TEST-token"
    private val web = DeviceInfo("Website", Platform.OTHER)

    private fun Harness.bot(tg: TelegramApi) = TelegramBot(services, tg).also { services.bot = it; it.username = "boop_test_bot" }

    private suspend fun Harness.userIdOfTelegram(tgId: Long) = ctx.db.run { it.one("SELECT id FROM users WHERE telegram_id=?", tgId)?.str("id") }
    private suspend fun Harness.count(q: String, vararg a: Any?) = ctx.db.run { it.one(q, *a)!!.int("n") }

    private fun widget(fields: Map<String, String>, botToken: String = token): Map<String, String> {
        val check = fields.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
        return fields + ("hash" to TelegramAccounts.widgetHash(check, botToken))
    }

    private suspend fun Harness.postWidget(fields: Map<String, String>, ref: String? = null) = b.createClient {}.post("/v1/auth/telegram") {
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject {
            putJsonObject("auth") { for ((k, v) in fields) if (k == "id" || k == "auth_date") put(k, v.toLong()) else put(k, v) }
            ref?.let { put("referralCode", it) }
        }.toString())
    }

    /* ---------------- Login Widget ---------------- */

    @Test fun `Login Widget signature matches Telegram's algorithm`() {
        // Vector computed independently (Python hmac/hashlib) for the same data.
        val f = mapOf("id" to "424242", "first_name" to "Ivan", "username" to "ivan_tg", "auth_date" to "1792065600")
        assertEquals("998a53d347a64291ce64160b31ad8c8eba1c62411acbc58163ccce039a815783", TelegramAccounts.widgetHash(f.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }, token))
        val now = Instant.ofEpochSecond(1792065600)
        val signed = f + ("hash" to "998a53d347a64291ce64160b31ad8c8eba1c62411acbc58163ccce039a815783")
        assertEquals(424242L, TelegramAccounts.verifyWidget(signed, token, now))
        // Wrong signature, changed field, other bot, no hash: refused.
        assertNull(TelegramAccounts.verifyWidget(signed + ("hash" to "0".repeat(64)), token, now))
        assertNull(TelegramAccounts.verifyWidget(signed + ("username" to "someone_else"), token, now))
        assertNull(TelegramAccounts.verifyWidget(signed, "654321:OTHER", now))
        assertNull(TelegramAccounts.verifyWidget(f, token, now))
        // auth_date: too old or from the future.
        assertNull(TelegramAccounts.verifyWidget(signed, token, now.plus(Duration.ofHours(2))))
        assertNull(TelegramAccounts.verifyWidget(signed, token, now.minus(Duration.ofMinutes(5))))
        assertEquals(424242L, TelegramAccounts.verifyWidget(signed, token, now.plus(Duration.ofMinutes(59))))
    }

    @Test fun `website sign-in with the Login Widget, and refusal with a bad signature`() = harness(mapOf("TELEGRAM_BOT_TOKEN" to token)) {
        val auth = mapOf("id" to "555", "first_name" to "Ann", "username" to "ann", "auth_date" to clock.epochSecond.toString())
        val ok = postWidget(widget(auth))
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val tokenA = Json.parseToJsonElement(ok.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
        assertEquals("tg555@telegram.invalid", client(tokenA).me().user.email)
        // Again: same account, no duplicate.
        assertEquals(HttpStatusCode.OK, postWidget(widget(auth)).status)
        assertEquals(1, count("SELECT count(*)::int AS n FROM users WHERE telegram_id=555"))

        val forged = postWidget(widget(auth, "999:attacker-bot"))
        assertEquals(HttpStatusCode.BadRequest, forged.status)
        assertTrue("INVALID_CODE" in forged.bodyAsText())
        val tampered = postWidget(widget(auth) + ("id" to "556"))
        assertEquals(HttpStatusCode.BadRequest, tampered.status)
        assertNull(userIdOfTelegram(556))
        val stale = postWidget(widget(auth + ("auth_date" to (clock.epochSecond - 7200).toString())))
        assertEquals(HttpStatusCode.BadRequest, stale.status)
    }

    @Test fun `widget sign-in is off without a bot token`() = harness {
        val r = postWidget(widget(mapOf("id" to "1", "auth_date" to clock.epochSecond.toString())))
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    /* ---------------- one-time login links ---------------- */

    @Test fun `the bot's website sign-in link works once and expires in 10 minutes`() = harness {
        val tg = NumberedTelegram()
        val bot = bot(tg)
        bot.handle(tgMessage(31, "/start login"))
        val url = tg.sent("sendMessage").last().buttons().first().second
        assertTrue("#/login?token=" in url, url)
        val tokenA = url.substringAfter("token=")
        assertEquals("tg31@telegram.invalid", client().redeemLoginLink(LinkLoginReq(tokenA, web)).user.email)
        assertEquals("INVALID_CODE", code { client().redeemLoginLink(LinkLoginReq(tokenA, web)) })

        bot.handle(tgCallback(31, "login"))
        val tokenB = tg.sent("sendMessage").last().buttons().first().second.substringAfter("token=")
        clock = clock.plus(Duration.ofMinutes(11))
        assertEquals("INVALID_CODE", code { client().redeemLoginLink(LinkLoginReq(tokenB, web)) })
    }

    /* ---------------- linking an e-mail account ---------------- */

    private suspend fun Harness.linkUrl(sessionToken: String): String {
        val r = b.createClient {}.post("/v1/telegram/link") { header("Authorization", "Bearer $sessionToken") }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject["url"]!!.jsonPrimitive.content
    }

    @Test fun `Telegram links to an existing e-mail account without a duplicate`() = harness(mapOf("TELEGRAM_BOT_TOKEN" to token)) {
        val tg = NumberedTelegram()
        val bot = bot(tg)
        val mail = signIn("ivan@example.com")
        // The person used the bot before: it made an empty Telegram account.
        bot.handle(tgMessage(77, "/start"))
        val placeholder = userIdOfTelegram(77)!!

        val url = linkUrl(mail.session.token)
        assertTrue(url.startsWith("https://t.me/boop_test_bot?start=link_"), url)
        bot.handle(tgMessage(77, "/start " + url.substringAfter("start=")))
        assertTrue("ivan@example.com" in tg.texts(77).last(), tg.texts(77).last())
        assertEquals(mail.session.user.id, userIdOfTelegram(77))
        assertEquals(1, count("SELECT count(*)::int AS n FROM users WHERE telegram_id=77"))
        assertEquals(0, count("SELECT count(*)::int AS n FROM users WHERE id=?::uuid", placeholder))

        // The bot and the Login Widget now open the e-mail account.
        bot.handle(tgMessage(77, "/login"))
        val t = tg.sent("sendMessage").last().buttons().first().second.substringAfter("token=")
        assertEquals("ivan@example.com", client().redeemLoginLink(LinkLoginReq(t, web)).user.email)
        val w = postWidget(widget(mapOf("id" to "77", "auth_date" to clock.epochSecond.toString())))
        assertTrue("ivan@example.com" in w.bodyAsText())

        // The code works once.
        bot.handle(tgMessage(78, "/start " + url.substringAfter("start=")))
        assertTrue("использована или устарела" in tg.texts(78).last(), tg.texts(78).last())
        assertEquals(mail.session.user.id, userIdOfTelegram(77))

        // Status for the website, then unlink.
        val raw = b.createClient {}
        val st = Json.parseToJsonElement(raw.get("/v1/telegram") { header("Authorization", "Bearer ${mail.session.token}") }.bodyAsText()).jsonObject
        assertEquals("true", st["linked"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, raw.delete("/v1/telegram/link") { header("Authorization", "Bearer ${mail.session.token}") }.status)
        assertNull(userIdOfTelegram(77))
    }

    @Test fun `linking refuses to swallow a Telegram account with purchases`() = harness {
        val tg = NumberedTelegram()
        val bot = bot(tg)
        bot.handle(tgMessage(88, "/start"))
        val tgUser = userIdOfTelegram(88)!!
        // Bought in the bot.
        services.payments.markPaid(services.payments.create(tgUser, tariff(MONTH), dev.jaganet.server.services.Channel.TELEGRAM).id)
        val mail = signIn("petr@example.com")
        bot.handle(tgMessage(88, "/start " + linkUrl(mail.session.token).substringAfter("start=")))
        assertTrue("свой аккаунт с подпиской" in tg.texts(88).last(), tg.texts(88).last())
        assertEquals(tgUser, userIdOfTelegram(88))
        assertEquals(2, count("SELECT count(*)::int AS n FROM users WHERE email IN ('petr@example.com','tg88@telegram.invalid')"))

        // A Telegram-only account can't unlink: it has nothing else to sign in with.
        bot.handle(tgMessage(88, "/login"))
        val s = client().redeemLoginLink(LinkLoginReq(tg.sent("sendMessage").last().buttons().first().second.substringAfter("token="), web))
        assertEquals(HttpStatusCode.BadRequest, b.createClient {}.delete("/v1/telegram/link") { header("Authorization", "Bearer ${s.token}") }.status)
    }

    @Test fun `an account with another Telegram is not relinked`() = harness {
        val tg = NumberedTelegram()
        val bot = bot(tg)
        val mail = signIn("olga@example.com")
        bot.handle(tgMessage(91, "/start " + linkUrl(mail.session.token).substringAfter("start=")))
        assertEquals(mail.session.user.id, userIdOfTelegram(91))
        bot.handle(tgMessage(92, "/start " + linkUrl(mail.session.token).substringAfter("start=")))
        assertEquals(mail.session.user.id, userIdOfTelegram(91))
        assertNotNull(userIdOfTelegram(92)).also { assertTrue(it != mail.session.user.id) }
    }

    /* ---------------- referral groundwork ---------------- */

    @Test fun `start ref_ remembers who invited`() = harness {
        val tg = NumberedTelegram()
        val bot = bot(tg)
        val inviter = signIn("inviter@example.com")
        val code = services.referrals.stats(inviter.session.user.id, dev.jaganet.api.ReferralPeriod.D30).links.first { it.main }.code
        bot.handle(tgMessage(61, "/start ref_$code"))
        val row = ctx.db.run { it.one("SELECT referred_by::text AS r, tg_start_ref FROM users WHERE telegram_id=61") }!!
        assertEquals(inviter.session.user.id, row.str("r"))
        assertEquals(code, row.str("tg_start_ref"))
        // An unknown code is kept raw for later; a second /start changes nothing.
        bot.handle(tgMessage(62, "/start ref_nosuch-code"))
        assertEquals("NOSUCH-CODE", ctx.db.run { it.one("SELECT tg_start_ref FROM users WHERE telegram_id=62") }!!.str("tg_start_ref"))
        bot.handle(tgMessage(61, "/start ref_OTHER"))
        assertEquals(code, ctx.db.run { it.one("SELECT tg_start_ref FROM users WHERE telegram_id=61") }!!.str("tg_start_ref"))

        assertEquals("ABC", StartPayload.referral("ref_ABC"))
        assertEquals("ABC", StartPayload.referral("ABC"))
        assertNull(StartPayload.referral("login"))
        assertNull(StartPayload.referral("link_xyz"))
        assertNull(StartPayload.referral("ref_a b"))
    }

    @Test fun `a user blocked in the admin gets only support in the bot`() = harness {
        val tg = NumberedTelegram()
        val bot = bot(tg)
        bot.handle(tgMessage(71, "/start"))
        val id = userIdOfTelegram(71)!!
        ctx.db.run { it.exec("UPDATE users SET blocked_at=now(), blocked_reason='test' WHERE id=?::uuid", id) }
        tg.calls.clear()
        bot.handle(tgMessage(71, "/keys"))
        bot.handle(tgCallback(71, "pay"))
        val texts = tg.texts(71)
        assertEquals(2, texts.size)
        assertTrue(texts.all { it.startsWith("Аккаунт заблокирован") || it.startsWith("This account is blocked") }, texts.toString())
        assertTrue(tg.sent("sendPhoto").isEmpty())
        // Support still works: the button, then a plain message goes to the support chat.
        tg.calls.clear()
        bot.handle(tgCallback(71, "support"))
        assertTrue(tg.texts(71).none { it.startsWith("Аккаунт заблокирован") })
    }

    /* ---------------- keys and devices ---------------- */

    private val pbk = "_No2ExH-ajHLTIY4xDz1EjOQ6izyfCBAueJM73gZbh0"

    private suspend fun Harness.vlessNode() = ctx.db.run {
        val settings = Vless.NodeSettings(host = "fi1.vpn.example.com", publicKey = pbk, shortIds = listOf("a1b2c3d4"), sni = "fi1.vpn.example.com", transports = listOf(Vless.Transport("tcp", 443)))
        it.exec(
            "INSERT INTO servers (id, name, city, country_code, subnet, protocols, max_peers) VALUES ('v1','VLESS v1','Helsinki','FI','10.9.0.0/16',?,5)",
            Jsonb("""{"vless":${Protocols.encode(settings)}}"""),
        )
    }

    @Test fun `keys come as a subscription link with a QR code, app tips and no server names`() = harness {
        vlessNode()
        val tg = NumberedTelegram()
        val bot = bot(tg)
        bot.handle(tgMessage(41, "/keys"))
        val token = ctx.db.run { it.one("SELECT k.link_token FROM vless_keys k JOIN users u ON u.id = k.user_id WHERE u.telegram_id=41") }!!.str("link_token")
        val photo = tg.uploads.single { it.first == "sendPhoto" }.second
        assertEquals("image/png", photo.contentType)
        val text = tg.texts(41).last()
        assertTrue("/k/$token/vless" in text, text)
        assertTrue("/k/$token?lang=ru" in text, text)
        assertTrue(text.indexOf("Happ") < text.indexOf("v2RayTun") && text.indexOf("v2RayTun") < text.indexOf("Hiddify"), text)
        for (hidden in listOf("vless://", "VLESS", "Финляндия", "Helsinki", "fi1.vpn", "Reality", "AmneziaWG")) assertFalse(hidden in text, "$hidden in $text")
        val buttons = tg.sent("sendMessage").last().buttons().map { it.first }
        assertEquals(listOf("Happ", "v2RayTun", "Hiddify"), buttons.take(3))
        // The link the bot gave is the real subscription.
        assertEquals(HttpStatusCode.OK, b.createClient {}.get("/k/$token/vless").status)
    }

    @Test fun `devices list and delete, within the plan's limit, the subscription key outside it`() = harness {
        vlessNode()
        val tg = NumberedTelegram()
        val bot = bot(tg)
        bot.handle(tgMessage(51, "/start"))
        val uid = userIdOfTelegram(51)!!
        services.payments.markPaid(services.payments.create(uid, tariff(MONTH), dev.jaganet.server.services.Channel.WEB).id)
        services.vlessKeys.ensureFor(uid)
        // A phone with the Boop app, signed in through the bot's login link.
        bot.handle(tgMessage(51, "/login"))
        val s = client().redeemLoginLink(LinkLoginReq(tg.sent("sendMessage").last().buttons().first().second.substringAfter("token="), DeviceInfo("Pixel", Platform.ANDROID)))
        client(s.token).provisionTunnel(TunnelProvisionReq(Protocols.AMNEZIAWG, clientParams = Protocols.encode(WireGuard.ClientParams(key(3)))))

        bot.handle(tgMessage(51, "/devices"))
        val msg = tg.sent("sendMessage").last()
        val text = msg["text"]!!.jsonPrimitive.content
        // The paid key from markPaid and the phone; the VLESS key is not in the list.
        assertTrue("Устройства 2 из 5" in text, text)
        assertTrue("Pixel" in text, text)
        assertFalse("VLESS" in text, text)
        val del = msg.buttons().first { "Pixel" in it.first }.second
        bot.handle(tgCallback(51, del))
        assertEquals("delok:" + del.removePrefix("del:"), tg.sent("sendMessage").last().buttons().first().second)
        bot.handle(tgCallback(51, "delok:" + del.removePrefix("del:")))
        assertTrue("Устройство удалено" in tg.texts(51).last(), tg.texts(51).last())
        assertTrue("Устройства 1 из 5" in tg.texts(51).last())
        // The subscription key can't be deleted through the bot either.
        val bonus = ctx.db.run { it.one("SELECT device_id::text AS d FROM vless_keys WHERE user_id=?::uuid", uid) }!!.str("d")
        bot.handle(tgCallback(51, "delok:$bonus"))
        assertEquals(1, count("SELECT count(*)::int AS n FROM tunnels WHERE device_id=?::uuid", bonus))
    }

    /* ---------------- notifications ---------------- */

    private suspend fun Harness.subscriptionUntil(userId: String, end: Instant) = ctx.db.run {
        it.exec(
            "INSERT INTO subscriptions (user_id, product_id, source, external_id, started_at, expires_at, auto_renew, tariff_name, device_limit) VALUES (?::uuid,'t','web',?,?,?,false,'Pro',5)",
            userId, "test:${end.epochSecond}:$userId", clock.minus(Duration.ofDays(30)), end,
        )
    }

    @Test fun `reminders 3 days and 1 day before the end and at the end, once each, and unsubscribe`() = harness {
        val tg = NumberedTelegram()
        val bot = bot(tg)
        bot.handle(tgMessage(71, "/start"))
        bot.handle(tgMessage(72, "/start"))
        val a = userIdOfTelegram(71)!!
        val b2 = userIdOfTelegram(72)!!
        subscriptionUntil(a, clock.plus(Duration.ofHours(60)))
        subscriptionUntil(b2, clock.plus(Duration.ofHours(60)))
        // B turns them off in the bot.
        bot.handle(tgCallback(72, "notify:off"))
        assertTrue("выключены" in tg.texts(72).last())

        assertEquals(1, bot.remind())
        assertTrue("Подписка закончится" in tg.texts(71).last() && "Осталось 3 дня" in tg.texts(71).last(), tg.texts(71).last())
        assertTrue(tg.sent("sendMessage").last().buttons().any { it.second == "notify:off" })
        assertEquals(0, bot.remind())

        clock = clock.plus(Duration.ofHours(40))
        assertEquals(1, bot.remind())
        assertTrue("Остался 1 день" in tg.texts(71).last(), tg.texts(71).last())
        assertEquals(0, bot.remind())

        clock = clock.plus(Duration.ofHours(21))
        assertEquals(1, bot.remind())
        assertTrue("Подписка закончилась" in tg.texts(71).last())
        assertEquals(0, bot.remind())
        assertTrue(tg.texts(72).none { "Подписка" in it })

        // A renewal (new end date) starts the reminders over.
        subscriptionUntil(a, clock.plus(Duration.ofDays(2)))
        assertEquals(1, bot.remind())
    }

    @Test fun `a reminder that Telegram didn't take is tried again, and the panel keeps working`() = harness {
        val tg = NumberedTelegram()
        val bot = bot(tg)
        bot.handle(tgMessage(73, "/start"))
        subscriptionUntil(userIdOfTelegram(73)!!, clock.plus(Duration.ofHours(20)))
        tg.down = true
        assertEquals(0, bot.remind())
        assertEquals(0, count("SELECT count(*)::int AS n FROM telegram_notices"))
        tg.down = false
        assertEquals(1, bot.remind())
        // A payment while Telegram is down: paid and activated all the same.
        tg.down = true
        val order = services.payments.create(userIdOfTelegram(73)!!, tariff(MONTH), dev.jaganet.server.services.Channel.TELEGRAM)
        assertNotNull(services.payments.markPaid(order.id))
        assertEquals(dev.jaganet.api.OrderStatus.PAID, services.payments.get(order.id)!!.status)
        // Commands that reach the bot while Telegram fails throw TelegramException to the runtime, nothing else.
        assertTrue(runCatching { bot.handle(tgMessage(73, "/keys")) }.exceptionOrNull() is TelegramException)
    }

    @Test fun `payments on the website are announced to a linked Telegram`() = harness {
        val tg = NumberedTelegram()
        val bot = bot(tg)
        val mail = signIn("web@example.com")
        bot.handle(tgMessage(81, "/start " + linkUrl(mail.session.token).substringAfter("start=")))
        buy(mail.api)
        assertTrue("Оплата получена" in tg.texts(81).last(), tg.texts(81).last())
    }

    /* ---------------- support ---------------- */

    @Test fun `support messages go to the admin chat and the admin's reply comes back`() = harness(mapOf("TELEGRAM_SUPPORT_CHAT_ID" to "-1001")) {
        val tg = NumberedTelegram()
        val bot = bot(tg)
        bot.handle(tgMessage(21, "/start"))
        // Without pressing Support a plain message is not forwarded.
        bot.handle(tgMessage(21, "hello?", messageId = 5))
        assertTrue(tg.sent("forwardMessage").isEmpty())

        bot.handle(tgCallback(21, "support"))
        bot.handle(tgMessage(21, "VPN does not connect", messageId = 6))
        val fwd = tg.sent("forwardMessage").single()
        assertEquals("-1001", fwd["chat_id"]!!.jsonPrimitive.content)
        assertEquals("6", fwd["message_id"]!!.jsonPrimitive.content)
        assertTrue("Обращение в поддержку" in tg.texts(-1001).last())
        assertTrue("Отправлено в поддержку" in tg.texts(21).last())
        val forwardedId = ctx.db.run { it.query("SELECT admin_message_id FROM telegram_support_messages ORDER BY admin_message_id") }.last().long("admin_message_id")

        // The admin replies to the forwarded message in the support chat.
        bot.handle(tgMessage(-1001, "Reinstall the app please", fromId = 999, messageId = 500, replyTo = forwardedId, type = "supergroup"))
        assertTrue("Ответ поддержки" in tg.texts(21).last() && "Reinstall the app please" in tg.texts(21).last(), tg.texts(21).last())
        assertTrue("Отправлено пользователю" in tg.texts(-1001).last())
        // Other messages in the group are ignored: no account for the admin's group chatter.
        bot.handle(tgMessage(-1001, "just talking", fromId = 999, messageId = 501, type = "supergroup"))
        assertNull(userIdOfTelegram(999))
    }

    @Test fun `the main menu has the actions in Russian`() = harness {
        val tg = NumberedTelegram()
        val bot = bot(tg)
        bot.handle(tgMessage(11, "/start"))
        val b = tg.sent("sendMessage").last().buttons().map { it.second }
        for (d in listOf("keys", "pay", "devices", "notify", "support", "login", "help")) assertTrue(d in b, "$d in $b")
        assertTrue("Мои ключи" in tg.sent("sendMessage").last()["reply_markup"].toString())
        bot.handle(tgMessage(11, "/help"))
        for (c in listOf("/start", "/keys", "/pay", "/support")) assertTrue(c in tg.texts(11).last())
        bot.prepare()
        val commands = tg.sent("setMyCommands").first()["commands"]!!.jsonArray.map { it.jsonObject["command"]!!.jsonPrimitive.content }
        assertTrue(commands.containsAll(listOf("start", "help", "keys", "pay", "support")), commands.toString())
    }

    /* ---- the community link (issue #91) ---- */

    private val community = "https://t.me/example"

    @Test fun `with TELEGRAM_COMMUNITY_URL the bot shows the group`() = harness(mapOf("TELEGRAM_COMMUNITY_URL" to community, "TELEGRAM_SUPPORT_CHAT_ID" to "-1001")) {
        val tg = NumberedTelegram()
        val bot = bot(tg)
        val group = "👥 Наша группа в Telegram" to community
        bot.handle(tgMessage(31, "/start"))
        assertTrue(group in tg.sent("sendMessage").last().buttons(), tg.sent("sendMessage").last().buttons().toString())
        bot.handle(tgCallback(31, "menu"))
        assertTrue(group in tg.sent("sendMessage").last().buttons())
        bot.handle(tgMessage(31, "/help"))
        assertTrue("/community" in tg.texts(31).last() && community in tg.texts(31).last(), tg.texts(31).last())
        assertTrue(group in tg.sent("sendMessage").last().buttons())
        bot.handle(tgMessage(31, "/community"))
        assertTrue(group in tg.sent("sendMessage").last().buttons())
        bot.handle(tgCallback(31, "support"))
        val b = tg.sent("sendMessage").last().buttons()
        assertTrue(group in b && "menu" in b.map { it.second }, b.toString())
        bot.prepare()
        for (call in tg.sent("setMyCommands")) {
            assertTrue("community" in call["commands"]!!.jsonArray.map { it.jsonObject["command"]!!.jsonPrimitive.content })
        }
    }

    @Test fun `without the community link there is no button and no command`() = harness {
        val tg = NumberedTelegram()
        val bot = bot(tg)
        bot.handle(tgMessage(32, "/start"))
        assertTrue(tg.sent("sendMessage").last().buttons().none { "t.me/example" in it.second || "группа" in it.first })
        bot.handle(tgMessage(32, "/help"))
        assertFalse("/community" in tg.texts(32).last())
        bot.handle(tgMessage(32, "/community"))
        assertTrue(tg.sent("sendMessage").last().buttons().none { "группа" in it.first })
        bot.prepare()
        for (call in tg.sent("setMyCommands")) {
            assertFalse("community" in call["commands"]!!.jsonArray.map { it.jsonObject["command"]!!.jsonPrimitive.content })
        }
    }

    @Test fun `only telegram links are accepted as the community`() {
        assertEquals(community, TelegramConfig.load(mapOf("TELEGRAM_COMMUNITY_URL" to "  $community ")).communityUrl)
        assertEquals("https://telegram.me/example", TelegramConfig.load(mapOf("TELEGRAM_COMMUNITY_URL" to "https://telegram.me/example")).communityUrl)
        for (bad in listOf("", "  ", "http://t.me/example", "https://example.com/x", "t.me/example", "javascript:alert(1)")) {
            assertNull(TelegramConfig.load(mapOf("TELEGRAM_COMMUNITY_URL" to bad)).communityUrl, bad)
        }
        assertNull(TelegramConfig.load(emptyMap()).communityUrl)
    }

    @Test fun `GET site carries communityUrl or null`() {
        harness(mapOf("TELEGRAM_COMMUNITY_URL" to community)) {
            assertEquals(community, client().site().communityUrl)
        }
        harness { assertNull(client().site().communityUrl) }
    }

    /* ---------------- the status card (issue #140) ---------------- */

    private val cardOn = mapOf("TELEGRAM_WIDGET" to "all", "TELEGRAM_MINIAPP" to "1", "PUBLIC_URL" to "https://boop.example")

    private fun JsonObject.data(): JsonObject = Json.parseToJsonElement(this["chat_data"]!!.jsonPrimitive.content).jsonObject

    @Test fun `with TELEGRAM_WIDGET off the bot sends the text menu as before`() = harness(mapOf("TELEGRAM_MINIAPP" to "1", "PUBLIC_URL" to "https://boop.example")) {
        assertEquals("off", TelegramConfig.load(emptyMap()).widget)
        assertEquals("off", TelegramConfig.load(mapOf("TELEGRAM_WIDGET" to "yes")).widget)
        assertEquals(listOf("message", "callback_query"), dev.jaganet.server.telegram.allowedUpdates(TelegramConfig.load(emptyMap())))
        assertTrue("widget_query" in dev.jaganet.server.telegram.allowedUpdates(TelegramConfig.load(mapOf("TELEGRAM_WIDGET" to "all"))))
        val tg = NumberedTelegram()
        val bot = bot(tg)
        bot.handle(tgMessage(601, "/start"))
        bot.handle(tgMessage(601, "/status"))
        bot.handle(tgCallback(601, "menu"))
        assertTrue(tg.sent("sendWidget").isEmpty())
        assertEquals(3, tg.sent("sendMessage").size)
        val uid = userIdOfTelegram(601)!!
        services.payments.markPaid(services.payments.create(uid, tariff(MONTH), dev.jaganet.server.services.Channel.WEB).id)
        assertTrue(tg.sent("editMessageWidgetData").isEmpty())
        assertEquals(0, count("SELECT count(*)::int AS n FROM telegram_widgets"))
    }

    @Test fun `start, menu and status send the card with a caption and the two buttons`() = harness(cardOn) {
        val tg = NumberedTelegram()
        val bot = bot(tg)
        bot.handle(tgMessage(602, "/start"))
        val card = tg.sent("sendWidget").single()
        assertTrue(tg.sent("sendMessage").isEmpty())
        assertTrue("Нет активной подписки" in card["caption"]!!.jsonPrimitive.content, card.toString())
        assertEquals(listOf("📱 Открыть Boop" to "https://boop.example/tg/app", "💳 Купить подписку" to "pay"), card.buttons())
        assertEquals("true", card["allow_queries"]!!.jsonPrimitive.content)
        assertEquals("none", card.data()["st"]!!.jsonPrimitive.content)
        val html = card["html"]!!.jsonPrimitive.content
        assertTrue("Подписка активна" in html && "lang=\"ru\"" in html && "{{" !in html)

        val uid = userIdOfTelegram(602)!!
        services.payments.markPaid(services.payments.create(uid, tariff(MONTH), dev.jaganet.server.services.Channel.WEB).id)
        bot.handle(tgMessage(602, "/status"))
        val status = tg.sent("sendWidget").last()
        assertTrue("Подписка активна до" in status["caption"]!!.jsonPrimitive.content && "Устройства" in status["caption"]!!.jsonPrimitive.content)
        assertEquals("💳 Продлить" to "pay", status.buttons().last())
        val d = status.data()
        assertEquals("active", d["st"]!!.jsonPrimitive.content)
        assertNotNull(d["exp"]); assertNotNull(d["from"])
        assertEquals(2, d["dev"]!!.jsonArray.size)
        // No subscription link and no keys in the card.
        for (c in tg.sent("sendWidget")) assertFalse("/k/" in c.toString() || "vless" in c.toString().lowercase(), c.toString())
        bot.handle(tgCallback(602, "menu"))
        assertEquals(3, tg.sent("sendWidget").size)
        // A new language: a new card in that language.
        bot.handle(tgCallback(602, "lang:en"))
        assertTrue("Subscription active" in tg.sent("sendWidget").last()["html"]!!.jsonPrimitive.content)
        assertEquals(1, count("SELECT count(*)::int AS n FROM telegram_widgets"))
    }

    @Test fun `without the mini app the card keeps the whole menu under it`() = harness(mapOf("TELEGRAM_WIDGET" to "all")) {
        val tg = NumberedTelegram()
        bot(tg).handle(tgMessage(603, "/start"))
        val b = tg.sent("sendWidget").single().buttons().map { it.second }
        for (d in listOf("keys", "pay", "devices", "support")) assertTrue(d in b, "$d in $b")
    }

    @Test fun `TELEGRAM_WIDGET=owner shows the card to the owner only`() = harness(cardOn + ("TELEGRAM_WIDGET" to "owner")) {
        val tg = NumberedTelegram()
        val bot = bot(tg)
        bot.handle(tgMessage(604, "/start"))
        assertTrue(tg.sent("sendWidget").isEmpty())
        ctx.db.run { it.exec("UPDATE users SET role='owner' WHERE telegram_id=604") }
        bot.handle(tgMessage(604, "/start"))
        assertEquals(1, tg.sent("sendWidget").size)
    }

    @Test fun `if sendWidget fails the text menu goes out`() = harness(cardOn) {
        val tg = NumberedTelegram()
        tg.fail["sendWidget"] = TelegramException("sendWidget: Bad Request: method not found", 400)
        bot(tg).handle(tgMessage(605, "/start"))
        assertEquals(1, tg.sent("sendWidget").size)
        val menu = tg.sent("sendMessage").single()
        assertTrue("keys" in menu.buttons().map { it.second })
        assertEquals(0, count("SELECT count(*)::int AS n FROM telegram_widgets"))
    }

    @Test fun `payment, devices, admin changes and rewards update the last card in place`() = harness(cardOn) {
        val tg = NumberedTelegram()
        val bot = bot(tg)
        bot.handle(tgMessage(606, "/start"))
        bot.handle(tgMessage(606, "/start"))
        val lastCard = 101L // the fake numbers messages from 100: the second card is the last one
        val uid = userIdOfTelegram(606)!!
        services.payments.markPaid(services.payments.create(uid, tariff(MONTH), dev.jaganet.server.services.Channel.WEB).id)
        val edit = tg.sent("editMessageWidgetData").single()
        assertEquals("606", edit["chat_id"]!!.jsonPrimitive.content)
        assertEquals(lastCard.toString(), edit["message_id"]!!.jsonPrimitive.content)
        assertEquals("active", edit.data()["st"]!!.jsonPrimitive.content)
        // The edit is silent; the payment message still comes as a message.
        assertTrue("Оплата получена" in tg.texts(606).last())

        // A new key (device) and its removal.
        val web = signIn("card@example.com")
        ctx.db.run { it.exec("UPDATE users SET telegram_id=NULL WHERE id=?::uuid", uid) }
        ctx.db.run { it.exec("UPDATE users SET telegram_id=606 WHERE email='card@example.com'") }
        val webUid = userIdOfTelegram(606)!!
        ctx.db.run { it.exec("UPDATE telegram_widgets SET user_id=?::uuid", webUid) }
        buy(web.api)
        val before = tg.sent("editMessageWidgetData").size
        val key = web.api.createKey()
        assertTrue(tg.sent("editMessageWidgetData").size > before)
        val used = tg.sent("editMessageWidgetData").last().data()["dev"]!!.jsonArray[0].jsonPrimitive.content.toInt()
        b.createClient {}.delete("/v1/keys/${key.id}") { header("Authorization", "Bearer ${web.session.token}") }
        assertEquals(used - 1, tg.sent("editMessageWidgetData").last().data()["dev"]!!.jsonArray[0].jsonPrimitive.content.toInt())

        // The admin extends the subscription.
        val owner = signIn("owner@test.dev")
        val n = tg.sent("editMessageWidgetData").size
        val exp0 = tg.sent("editMessageWidgetData").last().data()["exp"]!!.jsonPrimitive.content
        val res = b.createClient {}.post("/v1/admin/users/$webUid/extend") {
            header("Authorization", "Bearer ${owner.session.token}"); contentType(ContentType.Application.Json); setBody("""{"days":5}""")
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertEquals(n + 1, tg.sent("editMessageWidgetData").size)
        assertTrue(Instant.parse(tg.sent("editMessageWidgetData").last().data()["exp"]!!.jsonPrimitive.content) > Instant.parse(exp0))
        // Every update has a newer revision.
        val revs = tg.sent("editMessageWidgetData").map { it.data()["r"]!!.jsonPrimitive.content.toLong() }
        assertEquals(revs.sorted(), revs)
        assertEquals(revs.toSet().size, revs.size)
    }

    @Test fun `an unchanged card is not an error, a gone chat is marked, a deleted card is forgotten`() = harness(cardOn) {
        val tg = NumberedTelegram()
        val bot = bot(tg)
        bot.handle(tgMessage(607, "/start"))
        val uid = userIdOfTelegram(607)!!
        tg.fail["editMessageWidgetData"] = TelegramException("editMessageWidgetData: Bad Request: message is not modified", 400)
        assertTrue(bot.refreshWidget(uid))
        tg.fail["editMessageWidgetData"] = TelegramException("editMessageWidgetData: Bad Request: message to edit not found", 400)
        assertFalse(bot.refreshWidget(uid))
        assertEquals(0, count("SELECT count(*)::int AS n FROM telegram_widgets"))

        bot.handle(tgMessage(607, "/start"))
        tg.fail["editMessageWidgetData"] = TelegramException("editMessageWidgetData: Forbidden: bot was blocked by the user", 403)
        assertFalse(bot.refreshWidget(uid))
        assertNotNull(ctx.db.run { it.one("SELECT tg_blocked_at FROM users WHERE id=?::uuid", uid) }!!["tg_blocked_at"])
        // Telegram unreachable: the error goes to the caller (background work logs it), nothing else breaks.
        ctx.db.run { it.exec("UPDATE users SET tg_blocked_at=NULL WHERE id=?::uuid", uid) }
        tg.fail.remove("editMessageWidgetData")
        tg.down = true
        assertTrue(runCatching { bot.refreshWidget(uid) }.exceptionOrNull() is TelegramException)
        val order = services.payments.create(uid, tariff(MONTH), dev.jaganet.server.services.Channel.WEB)
        assertNotNull(services.payments.markPaid(order.id))
    }

    @Test fun `the card shows the end when the subscription ends, reminders stay separate messages`() = harness(cardOn) {
        val tg = NumberedTelegram()
        val bot = bot(tg)
        bot.handle(tgMessage(608, "/start"))
        val uid = userIdOfTelegram(608)!!
        subscriptionUntil(uid, clock.plus(Duration.ofHours(20)))
        bot.handle(tgMessage(608, "/start"))
        assertEquals("active", tg.sent("sendWidget").last().data()["st"]!!.jsonPrimitive.content)
        assertEquals(1, bot.remind())
        assertTrue("Остался 1 день" in tg.texts(608).last(), tg.texts(608).last())
        assertEquals(0, bot.expireWidgets())

        clock = clock.plus(Duration.ofHours(21))
        assertEquals(1, bot.expireWidgets())
        val d = tg.sent("editMessageWidgetData").last().data()
        assertEquals("ended", d["st"]!!.jsonPrimitive.content)
        assertEquals(0, bot.expireWidgets())
        assertEquals(1, bot.remind())
        assertTrue("Подписка закончилась" in tg.texts(608).last())
    }

    @Test fun `the card asks for fresh data and gets it`() = harness(cardOn) {
        val tg = NumberedTelegram()
        val bot = bot(tg)
        bot.handle(tgMessage(609, "/start"))
        bot.handle(buildJsonObject {
            put("update_id", 9001)
            putJsonObject("widget_query") {
                put("id", "wq1"); put("from", buildJsonObject { put("id", 609); put("is_bot", false); put("first_name", "T") })
                putJsonObject("chat") { put("id", 609); put("type", "private") }
                put("message_id", 100); put("chat_instance", "1"); put("data", "s")
                putJsonObject("type") { put("type", "data") }
            }
        })
        val answer = tg.sent("answerWidgetQuery").single()
        assertEquals("wq1", answer["widget_query_id"]!!.jsonPrimitive.content)
        assertEquals("none", Json.parseToJsonElement(answer["data"]!!.jsonPrimitive.content).jsonObject["st"]!!.jsonPrimitive.content)
    }

    @Test fun `the card's HTML is under 3800 bytes after zlib level 9 in every language`() {
        for (lang in dev.jaganet.api.i18n.Lang.entries) {
            val html = dev.jaganet.server.telegram.StatusWidget.html(lang).toByteArray()
            val deflater = java.util.zip.Deflater(9)
            deflater.setInput(html); deflater.finish()
            val buf = ByteArray(65536)
            val size = deflater.deflate(buf) // with the zlib header and checksum, as Python's zlib.compress counts
            assertTrue(html.size <= 32768 && size < 3800, "$lang: ${html.size} bytes, $size compressed")
            assertFalse("{{" in String(html))
        }
        val data = dev.jaganet.server.telegram.StatusWidget.chatData(dev.jaganet.server.telegram.StatusWidget.State("active", Instant.parse("2026-12-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"), 2, 5), 17)
        assertEquals("""{"v":1,"st":"active","exp":"2026-12-01T00:00:00Z","from":"2026-11-01T00:00:00Z","dev":[2,5],"r":17}""", data)
    }
}
