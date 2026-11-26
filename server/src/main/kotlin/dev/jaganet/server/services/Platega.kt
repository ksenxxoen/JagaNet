package dev.jaganet.server.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The Platega merchant (issue #154, docs.platega.io), from PLATEGA_* variables. Never logged or sent to a client:
 * [toString] hides the secret.
 */
data class PlategaConfig(
    val merchantId: String,
    val secret: String,
    /**
     * PLATEGA_PAYMENT_METHOD: Platega's numeric method code (2 SBP QR, 11 cards, 12 international cards,
     * 13 crypto, 14 SberPay…). Null = the buyer picks one of the shop's enabled methods on Platega's page.
     */
    val paymentMethod: Int? = null,
    /** PLATEGA_API_URL, for a sandbox or a new address. */
    val apiUrl: String = DEFAULT_API,
) {
    override fun toString() = "PlategaConfig(merchantId=$merchantId, secret=***, paymentMethod=$paymentMethod, apiUrl=$apiUrl)"

    companion object {
        const val DEFAULT_API = "https://api.payplatform.cc"

        /** Null unless both the merchant id and the secret are set. */
        fun load(env: Map<String, String>): PlategaConfig? {
            val id = env["PLATEGA_MERCHANT_ID"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val secret = env["PLATEGA_SECRET"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val method = env["PLATEGA_PAYMENT_METHOD"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
                requireNotNull(it.toIntOrNull()?.takeIf { n -> n > 0 }) { "PLATEGA_PAYMENT_METHOD must be a Platega method code such as 2 or 11" }
            }
            val api = env["PLATEGA_API_URL"]?.trim()?.takeIf { it.isNotEmpty() }?.trimEnd('/') ?: DEFAULT_API
            return PlategaConfig(id, secret, method, api)
        }
    }
}

/** One HTTP request to Platega; a stand-in in tests. */
fun interface PlategaHttp {
    /** The status code and the body. Throws on a network error or timeout. */
    suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String>

    companion object {
        private val client by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }

        val Jdk = PlategaHttp { method, url, headers, body ->
            val req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
            headers.forEach { (k, v) -> req.header(k, v) }
            val res = withContext(Dispatchers.IO) { client.send(req.build(), HttpResponse.BodyHandlers.ofString()) }
            res.statusCode() to res.body()
        }
    }
}

/**
 * Platega: a payment is created on Platega when the order is made ([start]), the buyer pays on Platega's
 * page, and Platega's callback (POST [CALLBACK_PATH]) or a status check ([check]) confirms it.
 * No recurring subscriptions: every renewal is a new one-off payment.
 */
class PlategaProvider(
    private val cfg: PlategaConfig,
    private val publicUrl: String,
    /** Replaced in tests. */
    @Volatile var http: PlategaHttp = PlategaHttp.Jdk,
) : PaymentProvider {
    override val id = ID

    private fun auth() = mapOf("X-MerchantId" to cfg.merchantId, "X-Secret" to cfg.secret)

    override suspend fun start(order: Order, buyer: Buyer): Started {
        // Back to the account (or the bot) whether the payment went through or not: the order shows which.
        val back = "${publicUrl.trimEnd('/')}/pay/return/${order.id}"
        val body = buildJsonObject {
            cfg.paymentMethod?.let { put("paymentMethod", it) }
            putJsonObject("paymentDetails") {
                put("amount", JsonPrimitive(BigDecimal.valueOf(order.amountMinor, 2)))
                put("currency", order.currency)
            }
            put("description", "Boop VPN: ${order.terms.name}")
            put("return", back)
            put("failedUrl", back)
            put("payload", order.id)
            put("orderId", order.id)
            putJsonObject("metadata") {
                put("user_email", buyer.email)
                put("userId", buyer.userId)
            }
        }
        // With a method: /transaction/process; without: /v2/…, where the buyer picks the method on Platega's page.
        val path = if (cfg.paymentMethod != null) "/transaction/process" else "/v2/transaction/process"
        val (code, text) = http.send("POST", cfg.apiUrl + path, auth() + ("Content-Type" to "application/json"), body.toString())
        if (code !in 200..299) throw ProviderError("Platega answered $code: ${text.take(300)}")
        val res = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw ProviderError("Platega sent no JSON")
        val tx = res.str("transactionId") ?: throw ProviderError("Platega sent no transactionId")
        val url = res.str("redirect") ?: res.str("url") ?: throw ProviderError("Platega sent no payment link")
        if (!url.startsWith("https://")) throw ProviderError("Platega's payment link is not HTTPS")
        return Started(tx, url)
    }

    override suspend fun check(order: Order, externalId: String): ProviderUpdate? {
        val (code, text) = http.send("GET", "${cfg.apiUrl}/transaction/$externalId", auth(), null)
        if (code == 404) return null
        if (code !in 200..299) throw ProviderError("Platega answered $code")
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw ProviderError("Platega sent no JSON")
        val details = o["paymentDetails"] as? JsonObject
        return ProviderUpdate(
            externalId = o.str("id") ?: externalId,
            status = o.str("status")?.uppercase() ?: return null,
            amountMinor = details?.get("amount")?.let(::minor),
            currency = details?.str("currency"),
            payload = o.str("payload"),
            body = o,
        )
    }

    /** The callback came from Platega: both headers match, compared in constant time. */
    fun authentic(merchantId: String?, secret: String?): Boolean {
        if (merchantId == null || secret == null) return false
        // Both checks always run, so the answer takes as long whichever one is wrong.
        val a = Crypto.safeEqual(merchantId.trim(), cfg.merchantId)
        val b = Crypto.safeEqual(secret.trim(), cfg.secret)
        return a and b
    }

    /** A callback body, or null if it isn't one. */
    fun parseCallback(text: String): ProviderUpdate? {
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val tx = o.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val status = o.str("status")?.uppercase()?.takeIf { it.isNotBlank() } ?: return null
        return ProviderUpdate(tx, status, o["amount"]?.let(::minor), o.str("currency"), o.str("payload"), o)
    }

    companion object {
        const val ID = "platega"
        /** The callback address to enter in Platega's dashboard (Settings → Callback URLs), after PUBLIC_URL. */
        const val CALLBACK_PATH = "/pay/platega/callback"

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

        /** 1500.25 → 150025; null if it isn't an amount with at most two decimals. */
        fun minor(e: JsonElement): Long? = runCatching {
            BigDecimal(e.jsonPrimitive.content).movePointRight(2).longValueExact()
        }.getOrNull()
    }
}

/** The provider could not create or check a payment. The message has no secrets. */
class ProviderError(message: String) : Exception(message)
