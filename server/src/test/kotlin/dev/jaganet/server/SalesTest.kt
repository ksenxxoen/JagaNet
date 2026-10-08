package dev.jaganet.server

import dev.jaganet.api.BillingSource
import dev.jaganet.api.CreateOrderReq
import dev.jaganet.api.DeviceInfo
import dev.jaganet.api.OrderStatus
import dev.jaganet.api.PairRedeemReq
import dev.jaganet.api.PlanId
import dev.jaganet.api.Platform
import dev.jaganet.api.ProductId
import dev.jaganet.server.services.Channel
import dev.jaganet.server.services.Keys
import dev.jaganet.server.telegram.TelegramApi
import dev.jaganet.server.telegram.TelegramBot
import dev.jaganet.server.telegram.Upload
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeysAndSalesTest {
    @Test fun `makes WireGuard key pairs like wg genkey and wg pubkey`() {
        // RFC 7748 section 6.1: Alice's private key and the public key it gives.
        val alice = HexFormat.of().parseHex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val fixed = object : SecureRandom() { override fun nextBytes(b: ByteArray) { alice.copyInto(b) } }
        val (priv, pub) = Keys.newKeyPair(fixed)
        val hex = { b64: String -> HexFormat.of().formatHex(java.util.Base64.getDecoder().decode(b64)) }
        assertEquals("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a", hex(priv))
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", hex(pub))
    }

    @Test fun `a website purchase activates Pro and delivers a VPN key that any app can import`() = harness {
        val a = signIn("web@example.com")
        val order = a.api.createOrder(CreateOrderReq(ProductId.PRO_MONTHLY))
        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals("$5.00", order.amount)
        assertTrue(order.checkoutUrl!!.endsWith("/pay/test/${order.id}"))
        assertEquals(PlanId.FREE, a.api.me().entitlement.plan)

        // The test checkout's "Pay" button.
        val pay = b.createClient { followRedirects = false }.post("/pay/test/${order.id}")
        assertEquals(HttpStatusCode.Found, pay.status)
        assertEquals(OrderStatus.PAID, a.api.order(order.id).status)
        val e = a.api.me().entitlement
        assertEquals(PlanId.PRO, e.plan)
        assertEquals(BillingSource.WEB, e.source)
        assertEquals(clock.plus(Duration.ofDays(30)), Instant.parse(e.expiresAt))

        val key = a.api.keys().keys.single()
        assertEquals("amneziawg", key.protocol)
        val conf = b.client.get(key.configUrl.removePrefix(cfg.publicUrl)).bodyAsText()
        assertTrue(conf.startsWith("[Interface]\nPrivateKey = "), conf)
        assertTrue("Jc = 5" in conf && "S1 = 86" in conf && "Endpoint = n1:51821" in conf, conf)
        val png = b.client.get(key.qrUrl.removePrefix(cfg.publicUrl)).bodyAsBytes()
        assertEquals(listOf(0x89, 'P'.code, 'N'.code, 'G'.code), png.take(4).map { it.toInt() and 0xFF })
        assertTrue("Как подключиться" in b.client.get(key.pageUrl.removePrefix(cfg.publicUrl)).bodyAsText())
        assertTrue("So verbindest du dich" in b.client.get(key.pageUrl.removePrefix(cfg.publicUrl) + "?lang=de").bodyAsText())

        // The key is a device of the user, with its own peer on the node.
        assertTrue(a.api.devices().devices.any { it.id == key.id && it.tunnelAddress != null })

        // Paying the same order twice does nothing; a second order stacks after the first.
        assertNull(services.payments.markPaid(order.id))
        val second = a.api.createOrder(CreateOrderReq(ProductId.PRO_MONTHLY))
        services.payments.markPaid(second.id)
        assertEquals(clock.plus(Duration.ofDays(60)), Instant.parse(a.api.me().entitlement.expiresAt))
        assertEquals(1, a.api.keys().keys.size)

        // Deleting the key kills its link.
        a.api.deleteKey(key.id)
        assertEquals(HttpStatusCode.NotFound, b.client.get(key.configUrl.removePrefix(cfg.publicUrl)).status)
    }

    @Test fun `orders are private to their buyer`() = harness {
        val a = signIn("a@example.com")
        val o = a.api.createOrder(CreateOrderReq(ProductId.PRO_YEARLY))
        assertEquals("NOT_FOUND", code { signIn("b@example.com").api.order(o.id) })
    }

    @Test fun `the Telegram bot sells Pro and sends the key to the chat`() = harness {
        val tg = FakeTelegram()
        val bot = TelegramBot(services, tg)
        services.bot = bot

        bot.handle(message(chat = 42, text = "/start"))
        val menu = tg.sent("sendMessage").last()
        assertEquals(42L, menu["chat_id"]!!.jsonPrimitive.content.toLong())
        assertTrue("buy:pro_yearly" in menu["reply_markup"].toString())

        bot.handle(callback(chat = 42, data = "buy:pro_yearly"))
        val payUrl = tg.sent("sendMessage").last()["reply_markup"]!!.jsonObject["inline_keyboard"]!!.jsonArray[0].jsonArray[0].jsonObject["url"]!!.jsonPrimitive.content
        val orderId = payUrl.substringAfterLast('/').substringBefore('?')

        val paid = services.payments.markPaid(orderId)!!
        assertEquals(Channel.TELEGRAM, paid.channel)
        assertEquals(listOf("photo", "document"), tg.uploads.map { it.field })
        assertTrue(String(tg.uploads[1].bytes).startsWith("[Interface]"))
        assertTrue("Pro действует до" in tg.sent("sendPhoto").last()["caption"]!!.jsonPrimitive.content)

        // "Get the app" hands out a device code that signs the app into the same account.
        bot.handle(callback(chat = 42, data = "app"))
        val code = Regex("\\b\\d{6}\\b").find(tg.sent("sendMessage").last()["text"]!!.jsonPrimitive.content)!!.value
        val s = client().redeemPairing(PairRedeemReq(code, DeviceInfo("Phone", Platform.ANDROID)))
        assertEquals("tg42@telegram.invalid", s.user.email)
        assertEquals(PlanId.PRO, client(s.token).me().entitlement.plan)

        // Same Telegram user → same account; /key resends the existing key.
        bot.handle(message(chat = 42, text = "/key"))
        assertEquals(4, tg.uploads.size)
        assertEquals(1, client(s.token).keys().keys.size)
    }

    @Test fun `the bot speaks Russian by default and switches language`() = harness {
        val tg = FakeTelegram()
        val bot = TelegramBot(services, tg)
        bot.handle(message(chat = 9, text = "/start"))
        val ruMenu = tg.sent("sendMessage").last()["reply_markup"].toString()
        assertTrue("Pro на 1 год за 48,00 $" in ruMenu, ruMenu)
        bot.handle(callback(chat = 9, data = "lang:de"))
        val deMenu = tg.sent("sendMessage").last()["reply_markup"].toString()
        assertTrue("Pro für 1 Jahr für 48,00 $" in deMenu, deMenu)
        bot.handle(message(chat = 9, text = "/status"))
        assertEquals("Kein aktives Abo.", tg.sent("sendMessage").last()["text"]!!.jsonPrimitive.content)
    }

    @Test fun `API errors come in the requested language`() = harness {
        val ru = dev.jaganet.api.ApiClient("", b.createClient {}, { null }, lang = { "ru" })
        val de = dev.jaganet.api.ApiClient("", b.createClient {}, { null }, lang = { "de" })
        suspend fun msg(c: dev.jaganet.api.ApiClient) =
            runCatching { c.verifyEmail(dev.jaganet.api.EmailVerifyReq("x@example.com", "123456", DeviceInfo("p", Platform.IOS))) }.exceptionOrNull()!!.message
        assertEquals("Срок действия кода истёк, запросите новый", msg(ru))
        assertEquals("Der Code ist abgelaufen, fordere einen neuen an", msg(de))
    }

    @Test fun `the bot asks free users to buy before giving a key`() = harness {
        val tg = FakeTelegram()
        val bot = TelegramBot(services, tg)
        bot.handle(message(chat = 7, text = "/key"))
        assertTrue("buy:" in tg.sent("sendMessage").last()["reply_markup"].toString())
        assertTrue(tg.uploads.isEmpty())
    }
}

private fun from(id: Long) = buildJsonObject { put("id", id); put("is_bot", false); put("first_name", "T") }

private fun message(chat: Long, text: String) = buildJsonObject {
    put("update_id", 1)
    putJsonObject("message") {
        put("message_id", 1); put("from", from(chat)); putJsonObject("chat") { put("id", chat) }; put("text", text)
    }
}

private fun callback(chat: Long, data: String) = buildJsonObject {
    put("update_id", 2)
    putJsonObject("callback_query") {
        put("id", "q1"); put("from", from(chat)); put("data", data)
        putJsonObject("message") { put("message_id", 2); putJsonObject("chat") { put("id", chat) } }
    }
}

class FakeTelegram : TelegramApi {
    val calls = mutableListOf<Pair<String, JsonObject>>()
    val uploads = mutableListOf<Upload>()
    fun sent(method: String) = calls.filter { it.first == method }.map { it.second }

    override suspend fun call(method: String, params: JsonObject, upload: Upload?): JsonElement {
        calls += method to params
        upload?.let(uploads::add)
        return if (method == "getMe") buildJsonObject { put("username", "jaganet_test_bot") } else JsonObject(emptyMap())
    }
}

