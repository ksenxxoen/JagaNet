package dev.jaganet.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json

class ApiException(val status: Int, val code: ErrorCode, message: String) : Exception(message)

/**
 * Typed client for the JagaNet REST API. The app passes an engine-specific
 * HttpClient (OkHttp, Darwin, Java); tests pass the Ktor test client.
 */
class ApiClient(
    baseUrl: String,
    httpClient: HttpClient,
    private val token: () -> String?,
    private val onUnauthorized: () -> Unit = {},
    /** Interface language code: the server answers errors in it. */
    private val lang: () -> String? = { null },
) {
    private val http = httpClient.config {
        expectSuccess = false
        install(ContentNegotiation) { json(Protocols.json) }
        defaultRequest { url(baseUrl.trimEnd('/') + "/v1/") }
    }


    private suspend inline fun <reified R> HttpResponse.read(): R {
        if (status.isSuccess()) return body()
        val err = runCatching { Protocols.json.decodeFromString(ErrorRes.serializer(), bodyAsText()).error }.getOrNull()
        if (status.value == 401) onUnauthorized()
        throw ApiException(status.value, err?.code ?: ErrorCode.INTERNAL, err?.message ?: "HTTP ${status.value}")
    }

    private fun HttpRequestBuilder.auth() {
        token()?.let { bearerAuth(it) }
        lang()?.let { header(HttpHeaders.AcceptLanguage, it) }
    }

    private suspend inline fun <reified R> get(path: String, crossinline q: HttpRequestBuilder.() -> Unit = {}): R =
        http.get(path) { auth(); q() }.read()

    private suspend inline fun <reified B, reified R> post(path: String, body: B): R =
        http.post(path) { auth(); contentType(ContentType.Application.Json); setBody(body) }.read()

    private suspend inline fun <reified R> delete(path: String): R = http.delete(path) { auth() }.read()

    // auth
    suspend fun startEmail(req: EmailStartReq): EmailStartRes = post("auth/email/start", req)
    suspend fun verifyEmail(req: EmailVerifyReq): SessionRes = post("auth/email/verify", req)
    suspend fun redeemPairing(req: PairRedeemReq): SessionRes = post("auth/pair/redeem", req)
    suspend fun logout(): OkRes = post("auth/logout", OkRes())

    // account
    suspend fun me(): MeRes = get("me")
    suspend fun payments(): PaymentsRes = get("me/payments")
    suspend fun deleteAccount(): OkRes = delete("me")

    // servers & tunnel
    suspend fun servers(): ServersRes = get("servers")
    suspend fun provisionTunnel(req: TunnelProvisionReq): TunnelConfig = post("tunnel", req)
    suspend fun connectionEvent(req: ConnectionEventReq): OkRes = post("tunnel/events", req)

    // devices
    suspend fun devices(): DevicesRes = get("devices")
    suspend fun renameDevice(id: String, name: String): OkRes =
        http.patch("devices/$id") { auth(); contentType(ContentType.Application.Json); setBody(RenameDeviceReq(name)) }.read()
    suspend fun removeDevice(id: String): OkRes = delete("devices/$id")
    suspend fun pairingCode(): PairingCodeRes = post("devices/pairing-code", OkRes())

    // stats
    suspend fun stats(period: StatsPeriod): StatsRes = get("stats") { parameter("period", period.name.lowercase()) }

    // billing
    suspend fun plans(): PlansRes = get("billing/plans")
    suspend fun devPurchase(productId: ProductId): MeRes = post("billing/dev/purchase", DevPurchaseReq(productId))
    suspend fun appleVerify(req: AppleVerifyReq): MeRes = post("billing/apple/verify", req)
    suspend fun googleVerify(req: GoogleVerifyReq): MeRes = post("billing/google/verify", req)

    // referrals & owner
    // Website / Telegram sales and VPN keys for other apps
    suspend fun site(): SiteInfo = get("site")
    suspend fun keys(): KeysRes = get("keys")
    suspend fun createKey(): AccessKey = post("keys", OkRes())
    suspend fun deleteKey(id: String): OkRes = delete("keys/$id")
    suspend fun createOrder(req: CreateOrderReq): OrderRes = post("orders", req)
    suspend fun order(id: String): OrderRes = get("orders/$id")

    suspend fun referrals(): ReferralRes = get("referrals")
    suspend fun adminOverview(): AdminOverviewRes = get("admin/overview")
}
