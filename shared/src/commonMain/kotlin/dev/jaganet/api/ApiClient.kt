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
import io.ktor.client.request.put
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

    private suspend inline fun <reified B, reified R> put(path: String, body: B): R =
        http.put(path) { auth(); contentType(ContentType.Application.Json); setBody(body) }.read()

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

    // referrals & owner
    // Website / Telegram sales and VPN keys for other apps
    suspend fun site(): SiteInfo = get("site")
    suspend fun keys(): KeysRes = get("keys")
    suspend fun createKey(): AccessKey = post("keys", OkRes())
    suspend fun deleteKey(id: String): OkRes = delete("keys/$id")
    suspend fun createOrder(req: CreateOrderReq): OrderRes = post("orders", req)
    suspend fun order(id: String): OrderRes = get("orders/$id")

    suspend fun referrals(): ReferralRes = get("referrals")
    suspend fun redeemLoginLink(req: LinkLoginReq): SessionRes = post("auth/link/redeem", req)
    suspend fun adminSettings(): AdminSettingsRes = get("admin/settings")
    suspend fun savePlanSettings(p: PlanSettings): AdminSettingsRes = put("admin/settings/plans", p)
    suspend fun saveSmtpSettings(p: SmtpSettings?): AdminSettingsRes = put("admin/settings/smtp", p ?: SmtpSettings("", 0, from = ""))
    suspend fun saveAlertSettings(p: AlertSettings): AdminSettingsRes = put("admin/settings/alerts", p)
    suspend fun saveModeSettings(p: ModeSettings): AdminSettingsRes = put("admin/settings/modes", p)
    suspend fun saveNetworkSettings(p: NetworkSettings): AdminSettingsRes = put("admin/settings/network", p)
    suspend fun sendTestEmail(to: String): OkRes = post("admin/settings/smtp/test", TestEmailReq(to))
    suspend fun adminFinance(period: ReferralPeriod): FinanceRes = get("admin/finance") { parameter("period", Protocols.json.encodeToString(ReferralPeriod.serializer(), period).trim('"')) }
    suspend fun adminNodes(): AdminNodesRes = get("admin/nodes")
    suspend fun createNode(req: NodeReq): NodeInstallRes = post("admin/nodes", req)
    suspend fun updateNode(id: String, req: NodeReq): AdminNode = put("admin/nodes/$id", req)
    suspend fun newNodeToken(id: String): NodeInstallRes = post("admin/nodes/$id/token", OkRes())
    suspend fun adminTariffs(): AdminTariffsRes = get("admin/tariffs")
    suspend fun createTariff(req: TariffReq): AdminTariffsRes = post("admin/tariffs", req)
    suspend fun updateTariff(id: String, req: TariffReq): AdminTariffsRes = put("admin/tariffs/$id", req)
    /** range: "1h", "24h", "7d" or "30d". */
    suspend fun adminMonitor(range: String = "24h"): MonitorRes = get("admin/monitor") { parameter("range", range) }
    suspend fun adminTestAlert(): OkRes = post("admin/monitor/test", OkRes())
    suspend fun referralStats(period: ReferralPeriod): ReferralStatsRes = get("referrals/stats") { parameter("period", Protocols.json.encodeToString(ReferralPeriod.serializer(), period).trim('"')) }
    suspend fun createReferralLink(req: CreateReferralLinkReq): ReferralLink = post("referrals/links", req)
    suspend fun renameReferralLink(id: String, name: String): OkRes =
        http.patch("referrals/links/$id") { auth(); contentType(ContentType.Application.Json); setBody(RenameReferralLinkReq(name)) }.read()
    suspend fun archiveReferralLink(id: String): OkRes = delete("referrals/links/$id")
    suspend fun adminReferrals(period: ReferralPeriod): AdminReferralsRes = get("admin/referrals") { parameter("period", Protocols.json.encodeToString(ReferralPeriod.serializer(), period).trim('"')) }
    suspend fun adminOverview(): AdminOverviewRes = get("admin/overview")
}
