package dev.jaganet.server.telegram

import dev.jaganet.server.http.Services
import dev.jaganet.server.services.Crypto
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("telegram")

/** Updates the bot asks Telegram for. */
private val ALLOWED_UPDATES = listOf("message", "callback_query")

/** The webhook's secret: TELEGRAM_WEBHOOK_SECRET, else derived from AUTH_SECRET (Telegram allows A-Z a-z 0-9 _ -). */
fun webhookSecret(s: Services): String =
    s.ctx.cfg.telegram.webhookSecret ?: Crypto.hmac(s.ctx.cfg.authSecret, "telegram-webhook").take(48)

/**
 * Starts the bot when TELEGRAM_BOT_TOKEN is set (called from Main.kt). Long polling by default,
 * webhook with TELEGRAM_MODE=webhook. Everything runs in its own supervisor scope: when Telegram
 * is unreachable the bot keeps retrying with growing pauses, and nothing it does can stop the panel.
 */
fun Application.startTelegramBot(s: Services) {
    val token = s.ctx.cfg.telegramBotToken ?: return
    val cfg = s.ctx.cfg.telegram
    val scope = CoroutineScope(SupervisorJob(coroutineContext[kotlinx.coroutines.Job]) + Dispatchers.IO)
    val api = HttpTelegramApi(token, cfg)
    val bot = TelegramBot(s, api, scope)
    s.bot = bot
    if (cfg.webhook) {
        val secret = webhookSecret(s)
        routing {
            post(TelegramConfig.WEBHOOK_PATH) {
                if (!Crypto.safeEqual(call.request.headers["X-Telegram-Bot-Api-Secret-Token"].orEmpty(), secret)) {
                    return@post call.respondText("forbidden", ContentType.Text.Plain, HttpStatusCode.Forbidden)
                }
                val update = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
                // Answer at once: Telegram resends updates that take long, and our work may call Telegram back.
                if (update != null) scope.launch { runCatching { bot.handle(update) }.onFailure { log.error("update failed", it) } }
                call.respondText("ok")
            }
        }
    }
    scope.launch { TelegramRunner(bot, api, cfg, s).run(webhookSecret(s)) }
    scope.launch {
        delay(60_000)
        while (true) {
            runCatching { bot.remind() }.onSuccess { if (it > 0) log.info("Telegram reminders sent: {}", it) }.onFailure { log.warn("Telegram reminders: ${it.message}") }
            delay(10 * 60_000L)
        }
    }
}

/** Connects to Telegram and keeps receiving updates. Split out so tests can run it against a fake Bot API. */
class TelegramRunner(private val bot: TelegramBot, private val api: TelegramApi, private val cfg: TelegramConfig, private val s: Services) {
    /** Pause after the n-th failure in a row: 5 s, 10 s, 20 s… up to 2 minutes. */
    private fun backoff(failures: Int) = (5_000L shl (failures - 1).coerceIn(0, 5)).coerceAtMost(120_000L)

    suspend fun run(secret: String, maxPolls: Int = Int.MAX_VALUE) {
        var failures = 0
        while (true) {
            try {
                bot.prepare()
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                log.warn("Telegram unreachable (getMe): ${e.message}; retrying")
                delay(backoff(failures))
            }
        }
        log.info("Telegram bot @{} is running ({})", bot.username, cfg.mode)
        if (cfg.webhook) keepWebhook(secret) else poll(maxPolls)
    }

    private suspend fun poll(maxPolls: Int) {
        // A webhook left from an earlier setup would make getUpdates fail with 409.
        runCatching { api.call("deleteWebhook", buildJsonObject { put("drop_pending_updates", false) }) }
        var offset = 0L
        var failures = 0
        repeat(maxPolls) {
            val updates = try {
                api.call("getUpdates", buildJsonObject {
                    put("offset", offset); put("timeout", 50)
                    putJsonArray("allowed_updates") { ALLOWED_UPDATES.forEach { add(it) } }
                }).jsonArray.also { bot.lastPollOk = s.ctx.now(); failures = 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                log.warn("getUpdates: ${e.message}")
                delay(backoff(failures))
                return@repeat
            }
            for (u in updates) {
                offset = u.jsonObject["update_id"]!!.jsonPrimitive.long + 1
                try {
                    bot.handle(u.jsonObject)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("update failed", e)
                }
            }
        }
    }

    /** Registers the webhook (again after failures) and checks it every minute for the monitoring page. */
    private suspend fun keepWebhook(secret: String) {
        val url = cfg.webhookUrl ?: (s.ctx.cfg.publicUrl.trimEnd('/') + TelegramConfig.WEBHOOK_PATH)
        var set = false
        var failures = 0
        while (true) {
            try {
                if (!set) {
                    api.call("setWebhook", buildJsonObject {
                        put("url", url); put("secret_token", secret)
                        putJsonArray("allowed_updates") { ALLOWED_UPDATES.forEach { add(it) } }
                    })
                    set = true
                    log.info("Telegram webhook set to {}", url)
                }
                val info = api.call("getWebhookInfo", JsonObject(emptyMap())).jsonObject
                if (info["url"]?.jsonPrimitive?.content != url) set = false else bot.lastPollOk = s.ctx.now()
                failures = 0
                delay(60_000)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                log.warn("Telegram webhook: ${e.message}")
                delay(backoff(failures))
            }
        }
    }
}
