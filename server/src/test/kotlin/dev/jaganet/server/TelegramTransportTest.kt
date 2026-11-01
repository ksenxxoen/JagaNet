package dev.jaganet.server

import com.sun.net.httpserver.HttpServer
import dev.jaganet.server.telegram.HttpTelegramApi
import dev.jaganet.server.telegram.TelegramBot
import dev.jaganet.server.telegram.TelegramConfig
import dev.jaganet.server.telegram.TelegramException
import dev.jaganet.server.telegram.TelegramRunner
import dev.jaganet.server.telegram.startTelegramBot
import dev.jaganet.server.telegram.webhookSecret
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A stand-in for api.telegram.org on localhost: answers the Bot API methods the bot uses and records
 * every call, so the real HTTP client, polling and webhook code run end to end without a real token.
 */
class StubBotApi(private val token: String) : AutoCloseable {
    val calls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    /** Updates the next getUpdates returns (then empty). */
    val pending = CopyOnWriteArrayList<JsonObject>()
    /** sendMessage answers 429 this many times first. */
    val tooMany = AtomicInteger(0)
    @Volatile var webhookUrl = ""
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path
            val method = path.substringAfterLast('/')
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val params = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrDefault(JsonObject(emptyMap()))
            val (status, answer) = if (!path.startsWith("/bot$token/")) {
                401 to """{"ok":false,"error_code":401,"description":"Unauthorized"}"""
            } else {
                calls += method to params
                when (method) {
                    "getMe" -> 200 to """{"ok":true,"result":{"id":1,"is_bot":true,"username":"stub_bot"}}"""
                    "getUpdates" -> {
                        val list = pending.toList().also { pending.clear() }
                        200 to """{"ok":true,"result":[${list.joinToString(",")}]}"""
                    }
                    "sendMessage" -> if (tooMany.getAndDecrement() > 0) 429 to """{"ok":false,"error_code":429,"description":"Too Many Requests","parameters":{"retry_after":1}}"""
                    else 200 to """{"ok":true,"result":{"message_id":${calls.size}}}"""
                    "setWebhook" -> { webhookUrl = params["url"]!!.jsonPrimitive.content; 200 to """{"ok":true,"result":true}""" }
                    "getWebhookInfo" -> 200 to """{"ok":true,"result":{"url":"$webhookUrl"}}"""
                    else -> 200 to """{"ok":true,"result":true}"""
                }
            }
            val bytes = answer.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    val url = "http://127.0.0.1:${server.address.port}"
    fun sent(method: String) = calls.filter { it.first == method }.map { it.second }
    override fun close() = server.stop(0)
}

class TelegramTransportTest {
    private val token = "777:STUB-token"

    @Test fun `long polling against a stub Bot API answers a user`() = harness {
        StubBotApi(token).use { stub ->
            val cfg = TelegramConfig(apiUrl = stub.url)
            val api = HttpTelegramApi(token, cfg)
            val bot = TelegramBot(services, api)
            stub.pending += tgMessage(33, "/start")
            TelegramRunner(bot, api, cfg, services).run("unused", maxPolls = 2)
            assertEquals("stub_bot", bot.username)
            assertTrue(stub.sent("deleteWebhook").isNotEmpty(), "a webhook left over would block polling")
            assertTrue(stub.sent("setMyCommands").isNotEmpty())
            val reply = stub.sent("sendMessage").single()
            assertEquals("33", reply["chat_id"]!!.jsonPrimitive.content)
            assertTrue("Мои ключи" in reply["reply_markup"].toString())
            assertTrue(bot.lastPollOk != null)
        }
    }

    @Test fun `429 is retried after retry_after, other errors are reported`() = kotlinx.coroutines.runBlocking {
        StubBotApi(token).use { stub ->
            val api = HttpTelegramApi(token, TelegramConfig(apiUrl = stub.url))
            stub.tooMany.set(1)
            api.call("sendMessage", buildJsonObject { put("chat_id", 1); put("text", "x") })
            assertEquals(2, stub.sent("sendMessage").size)
            // A wrong token: an error with Telegram's code, and the token is not in the message.
            val e = runCatching { HttpTelegramApi("1:WRONG", TelegramConfig(apiUrl = stub.url)).call("getMe", JsonObject(emptyMap())) }.exceptionOrNull()
            assertTrue(e is TelegramException && e.code == 401, e.toString())
            assertTrue("WRONG" !in e.message.orEmpty())
        }
    }

    @Test fun `unreachable Telegram fails fast and the runner keeps retrying quietly`() = harness {
        val closed = ServerSocket(0).use { it.localPort }
        val cfg = TelegramConfig(apiUrl = "http://127.0.0.1:$closed")
        val api = HttpTelegramApi(token, cfg)
        val started = System.nanoTime()
        val e = runCatching { api.call("sendMessage", buildJsonObject { put("chat_id", 1); put("text", "x") }) }.exceptionOrNull()
        assertTrue(e is TelegramException && e.code == -1, e.toString())
        assertTrue((System.nanoTime() - started) / 1_000_000 < 15_000, "gave up in time")
        val bot = TelegramBot(services, api)
        try {
            withTimeout(3_000) { TelegramRunner(bot, api, cfg, services).run("unused") }
            fail("the runner returned")
        } catch (_: TimeoutCancellationException) {
            // Still retrying: nothing else escaped.
        }
        assertNull(bot.username)
        // The panel answers meanwhile.
        assertEquals(HttpStatusCode.OK, b.createClient {}.post("/v1/auth/email/start") { contentType(ContentType.Application.Json); setBody("""{"email":"x@example.com"}""") }.status)
    }

    @Test fun `webhook mode registers the webhook and takes updates only with the secret`() {
        StubBotApi(token).use { stub ->
            harness(mapOf("TELEGRAM_BOT_TOKEN" to token, "TELEGRAM_MODE" to "webhook", "TELEGRAM_API_URL" to stub.url, "PUBLIC_URL" to "https://vpn.example.com")) {
                b.application { startTelegramBot(services) }
                val raw = b.createClient {}
                val update = tgMessage(44, "/start").toString()
                val denied = raw.post("/telegram/webhook") { contentType(ContentType.Application.Json); setBody(update) }
                assertEquals(HttpStatusCode.Forbidden, denied.status)
                val wrong = raw.post("/telegram/webhook") { header("X-Telegram-Bot-Api-Secret-Token", "nope"); contentType(ContentType.Application.Json); setBody(update) }
                assertEquals(HttpStatusCode.Forbidden, wrong.status)
                val ok = raw.post("/telegram/webhook") { header("X-Telegram-Bot-Api-Secret-Token", webhookSecret(services)); contentType(ContentType.Application.Json); setBody(update) }
                assertEquals(HttpStatusCode.OK, ok.status)
                // Handled in the background: wait for the answer and the webhook registration.
                repeat(100) { if (stub.sent("sendMessage").isEmpty() || stub.sent("setWebhook").isEmpty()) delay(100) }
                assertEquals("44", stub.sent("sendMessage").first()["chat_id"]!!.jsonPrimitive.content)
                val set = stub.sent("setWebhook").single()
                assertEquals("https://vpn.example.com/telegram/webhook", set["url"]!!.jsonPrimitive.content)
                assertEquals(webhookSecret(services), set["secret_token"]!!.jsonPrimitive.content)
                assertTrue(stub.sent("getUpdates").isEmpty())
                assertTrue(stub.calls.none { it.first == "getUpdates" })
            }
        }
    }
}
