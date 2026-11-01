package dev.jaganet.server.telegram

import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/** A file sent with sendPhoto / sendDocument. */
class Upload(val field: String, val fileName: String, val contentType: String, val bytes: ByteArray)

/** The Telegram Bot API calls the bot needs. Tests use a fake. */
interface TelegramApi {
    /** Calls [method] and returns its "result". */
    suspend fun call(method: String, params: JsonObject, upload: Upload? = null): JsonElement
}

/** [code] is Telegram's error_code (403 = the user blocked the bot), 0 when there was no answer. */
open class TelegramException(message: String, val code: Int = 0) : Exception(message) {
    /** The chat is gone for good: blocked, deleted, never started. Retrying won't help. */
    val chatGone get() = code == 403 || (code == 400 && "chat not found" in (message ?: ""))
}

/**
 * Bot API over HTTPS. Never throws anything but [TelegramException] (or cancellation), so callers can
 * treat "Telegram is unreachable" as one ordinary case. Short timeouts: a slow or blocked
 * api.telegram.org must not hold up the panel's own requests.
 * Retries what is safe to retry: connection failures (nothing was sent), 429 (after retry_after) and 5xx.
 */
class HttpTelegramApi(token: String, cfg: TelegramConfig = TelegramConfig()) : TelegramApi {
    private val base = "${cfg.apiUrl.trimEnd('/')}/bot$token/"
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).apply {
        cfg.proxy?.let { p ->
            val u = URI(if ("://" in p) p else "http://$p")
            proxy(ProxySelector.of(InetSocketAddress(u.host, if (u.port > 0) u.port else 8080)))
        }
    }.build()

    override suspend fun call(method: String, params: JsonObject, upload: Upload?): JsonElement {
        // Long polling waits up to its own timeout on Telegram's side; everything else answers fast.
        val wait = params["timeout"]?.jsonPrimitive?.intOrNull?.takeIf { method == "getUpdates" } ?: 0
        var attempt = 0
        while (true) {
            attempt++
            try {
                return once(method, params, upload, Duration.ofSeconds(wait + 20L))
            } catch (e: TelegramException) {
                val retryAfter = (e as? RetryLater)?.seconds
                val retry = method != "getUpdates" && attempt < 3 && (retryAfter != null || e.code >= 500 || e.code == -1)
                if (!retry) throw if (e is RetryLater) TelegramException(e.message ?: method, 429) else e
                delay(1_000L * (retryAfter ?: attempt.toLong()).coerceIn(1, 30))
            }
        }
    }

    private class RetryLater(message: String, val seconds: Long) : TelegramException(message, 429)

    private suspend fun once(method: String, params: JsonObject, upload: Upload?, timeout: Duration): JsonElement {
        val req = HttpRequest.newBuilder(URI.create(base + method)).timeout(timeout)
        if (upload == null) {
            req.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(params.toString()))
        } else {
            val boundary = "jaganet-" + UUID.randomUUID()
            req.header("Content-Type", "multipart/form-data; boundary=$boundary").POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, params, upload)))
        }
        val res = try {
            http.sendAsync(req.build(), HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never put the URL in the message: it holds the token.
            val connect = e is ConnectException || e is HttpConnectTimeoutException || e.cause is ConnectException || e.cause is HttpConnectTimeoutException
            throw TelegramException("$method: ${e.javaClass.simpleName}", if (connect) -1 else 0)
        }
        val body = runCatching { Json.parseToJsonElement(res.body()).jsonObject }.getOrNull()
            ?: throw TelegramException("$method: HTTP ${res.statusCode()}", res.statusCode())
        if (body["ok"]?.jsonPrimitive?.boolean != true) {
            val code = body["error_code"]?.jsonPrimitive?.intOrNull ?: res.statusCode()
            val description = "$method: ${body["description"]?.jsonPrimitive?.content}"
            val after = body["parameters"]?.jsonObject?.get("retry_after")?.jsonPrimitive?.longOrNull
            throw if (code == 429 && after != null) RetryLater(description, after) else TelegramException(description, code)
        }
        return body["result"]!!
    }

    private fun multipart(boundary: String, params: JsonObject, upload: Upload): ByteArray {
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray())
        for ((k, v) in params) {
            // Strings go as-is; objects (reply_markup) as JSON text.
            val text = if (v is JsonPrimitive && v.isString) v.content else v.toString()
            w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$text\r\n")
        }
        w("--$boundary\r\nContent-Disposition: form-data; name=\"${upload.field}\"; filename=\"${upload.fileName}\"\r\nContent-Type: ${upload.contentType}\r\n\r\n")
        out.write(upload.bytes)
        w("\r\n--$boundary--\r\n")
        return out.toByteArray()
    }
}
