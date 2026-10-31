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
    private var next = 100L
    fun sent(method: String) = calls.filter { it.first == method }.map { it.second }
    fun texts(chat: Long) = calls.filter { it.first == "sendMessage" && it.second["chat_id"]!!.jsonPrimitive.content == chat.toString() }
        .map { it.second["text"]!!.jsonPrimitive.content }

    override suspend fun call(method: String, params: JsonObject, upload: Upload?): JsonElement {
        if (down) throw TelegramException("$method: ConnectException", -1)
        calls += method to params
        upload?.let { uploads += method to it }
        return when (method) {
            "getMe" -> buildJsonObject { put("username", "boop_test_bot") }
            "sendMessage", "sendPhoto", "sendDocument", "forwardMessage", "copyMessage" -> buildJsonObject { put("message_id", next++) }
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

/** Every inline button of the last message: text to callback data or URL. */
fun JsonObject.buttons(): List<Pair<String, String>> =
    (this["reply_markup"]?.jsonObject?.get("inline_keyboard")?.jsonArray ?: emptyList()).flatMap { it.jsonArray }.map { b ->
        val o = b.jsonObject
        o["text"]!!.jsonPrimitive.content to (o["callback_data"] ?: o["url"])!!.jsonPrimitive.content
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
}
