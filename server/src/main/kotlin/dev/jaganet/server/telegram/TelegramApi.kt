package dev.jaganet.server.telegram

import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
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

class TelegramException(message: String) : Exception(message)

class HttpTelegramApi(token: String) : TelegramApi {
    private val base = "https://api.telegram.org/bot$token/"
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    override suspend fun call(method: String, params: JsonObject, upload: Upload?): JsonElement {
        val req = HttpRequest.newBuilder(URI.create(base + method)).timeout(Duration.ofSeconds(70))
        if (upload == null) {
            req.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(params.toString()))
        } else {
            val boundary = "jaganet-" + UUID.randomUUID()
            req.header("Content-Type", "multipart/form-data; boundary=$boundary").POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, params, upload)))
        }
        val res = http.sendAsync(req.build(), HttpResponse.BodyHandlers.ofString()).await()
        val body = runCatching { Json.parseToJsonElement(res.body()).jsonObject }.getOrNull()
            ?: throw TelegramException("$method: HTTP ${res.statusCode()}")
        if (body["ok"]?.jsonPrimitive?.boolean != true) throw TelegramException("$method: ${body["description"]}")
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
