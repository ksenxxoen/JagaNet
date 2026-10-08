package dev.jaganet.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * The JagaNet API contract, shared by the server and the apps.
 * Times are ISO-8601 strings; byte counts are Long; rates are bits per second.
 */

@Serializable
enum class Platform { @SerialName("ios") IOS, @SerialName("android") ANDROID, @SerialName("desktop") DESKTOP, @SerialName("other") OTHER }

@Serializable
enum class PlanId { @SerialName("free") FREE, @SerialName("pro") PRO }

@Serializable
enum class ProductId(val periodDays: Int) {
    @SerialName("pro_monthly") PRO_MONTHLY(30),
    @SerialName("pro_yearly") PRO_YEARLY(365),
}

@Serializable
enum class BillingSource {
    @SerialName("apple") APPLE, @SerialName("google") GOOGLE, @SerialName("referral") REFERRAL, @SerialName("dev") DEV,
    /** Paid on the website or through the Telegram bot. */
    @SerialName("web") WEB, @SerialName("telegram") TELEGRAM,
}

@Serializable
data class DeviceInfo(val name: String, val platform: Platform)

/* ---------- auth ---------- */

@Serializable
data class EmailStartReq(val email: String, val referralCode: String? = null)

@Serializable
data class EmailStartRes(val ok: Boolean = true, /** Simulation mode only. */ val devCode: String? = null)

@Serializable
data class EmailVerifyReq(val email: String, val code: String, val device: DeviceInfo)

@Serializable
data class PairRedeemReq(val code: String, val device: DeviceInfo)

@Serializable
enum class Role { @SerialName("user") USER, @SerialName("owner") OWNER }

@Serializable
data class User(val id: String, val email: String, val role: Role, val createdAt: String)

@Serializable
data class SessionRes(val token: String, val user: User, val deviceId: String)

/* ---------- account ---------- */

@Serializable
data class Entitlement(
    val plan: PlanId,
    val productId: ProductId? = null,
    val source: BillingSource? = null,
    val expiresAt: String? = null,
    val autoRenew: Boolean = false,
    val deviceLimit: Int,
    /** null = unlimited */
    val monthlyDataLimitBytes: Long? = null,
)

@Serializable
data class Usage(val periodStart: String, val bytesUsed: Long, val devicesUsed: Int)

@Serializable
data class MeRes(val user: User, val entitlement: Entitlement, val usage: Usage)

@Serializable
enum class PaymentStatus { @SerialName("active") ACTIVE, @SerialName("expired") EXPIRED, @SerialName("cancelled") CANCELLED, @SerialName("refunded") REFUNDED }

@Serializable
data class Payment(val id: String, val productId: String, val source: BillingSource, val startedAt: String, val expiresAt: String, val status: PaymentStatus)

@Serializable
data class PaymentsRes(val payments: List<Payment>)

/* ---------- servers & tunnel ---------- */

@Serializable
data class ServerLocation(
    val id: String,
    val name: String,
    val city: String,
    val countryCode: String,
    val load: Double,
    /** Protocols the node terminates, in the server's order of preference. */
    val protocols: List<String>,
)

@Serializable
data class ServersRes(val servers: List<ServerLocation>)

/**
 * The core API is protocol-agnostic: everything protocol-specific travels in
 * `clientParams` / `params` JSON objects whose shape each protocol defines (see Protocols.kt).
 */
@Serializable
data class TunnelProvisionReq(
    val protocol: String,
    val serverId: String? = null,
    /** Non-secret data from the device, e.g. a public key. */
    val clientParams: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class TunnelConfig(
    val protocol: String,
    val serverId: String,
    val location: String,
    /** Tunnel address of the device, e.g. 10.8.0.2/32 */
    val address: String,
    val dns: List<String>,
    val mtu: Int,
    /** Read only by the engine for [protocol]. */
    val params: JsonObject,
)

@Serializable
enum class ConnectionEventType { @SerialName("connected") CONNECTED, @SerialName("disconnected") DISCONNECTED }

@Serializable
data class ConnectionEventReq(val type: ConnectionEventType, val at: String, val peakDownBps: Long? = null)

/* ---------- devices ---------- */

@Serializable
data class Device(
    val id: String,
    val name: String,
    val platform: Platform,
    val protocol: String? = null,
    val tunnelAddress: String? = null,
    val lastSeenAt: String? = null,
    val online: Boolean,
    val isCurrent: Boolean,
    val createdAt: String,
)

@Serializable
data class DevicesRes(val devices: List<Device>, val limit: Int)

@Serializable
data class RenameDeviceReq(val name: String)

@Serializable
data class PairingCodeRes(val code: String, val expiresAt: String)

/* ---------- stats ---------- */

@Serializable
enum class StatsPeriod { @SerialName("day") DAY, @SerialName("week") WEEK, @SerialName("month") MONTH }

@Serializable
data class StatsBucket(val start: String, val rxBytes: Long, val txBytes: Long)

@Serializable
data class DeviceTraffic(val deviceId: String, val name: String, val bytes: Long)

@Serializable
data class StatsRes(
    val period: StatsPeriod,
    val buckets: List<StatsBucket>,
    val totalRxBytes: Long,
    val totalTxBytes: Long,
    val protectedSeconds: Long,
    val sessions: Int,
    val avgDownBps: Long? = null,
    val peakDownBps: Long? = null,
    val byDevice: List<DeviceTraffic>,
)

/* ---------- billing ---------- */

@Serializable
data class Product(
    val id: ProductId,
    val title: String,
    val period: String,
    /** Display only. Real prices always come from the App Store / Play Store. */
    val displayPrice: String,
    val appleProductId: String,
    val googleProductId: String,
    /** List price in minor units (cents), for formatting in the reader's language. */
    val priceMinor: Long? = null,
    val currency: String? = null,
)

@Serializable
data class FreePlan(val monthlyDataLimitBytes: Long, val deviceLimit: Int)

@Serializable
data class ProPlan(val deviceLimit: Int)

@Serializable
data class PlansRes(val products: List<Product>, val free: FreePlan, val pro: ProPlan)

@Serializable
data class DevPurchaseReq(val productId: ProductId)

@Serializable
data class AppleVerifyReq(val signedTransaction: String)

@Serializable
data class GoogleVerifyReq(val productId: ProductId, val purchaseToken: String)

/* ---------- referrals ---------- */

@Serializable
data class ReferralRes(val code: String, val invited: Int, val subscribed: Int, val rewardDays: Int, val daysEarned: Int, val shareUrl: String)

/* ---------- referral program: links and statistics ---------- */

@Serializable
enum class ReferralPeriod { @SerialName("7d") D7, @SerialName("30d") D30, @SerialName("90d") D90, @SerialName("all") ALL }

@Serializable
data class MoneyAmount(val minor: Long, val currency: String)

/** The funnel for a link, a user or the whole program, within a period. */
@Serializable
data class Funnel(
    /** Link opened (website visit or Telegram /start). */
    val clicks: Int = 0,
    /** Distinct visitors among the clicks. */
    val visitors: Int = 0,
    /** New accounts that came through the link. */
    val signups: Int = 0,
    /** Of those, people who paid for the first time in the period. */
    val paidUsers: Int = 0,
    /** All paid purchases by referred people in the period, renewals included. */
    val purchases: Int = 0,
    /** Website and Telegram payments by referred people, per currency. Store purchases have no amount here. */
    val revenue: List<MoneyAmount> = emptyList(),
)

@Serializable
data class ReferralLink(
    val id: String,
    /** "Main link" for the automatic one (translate it), else the user's own name. */
    val name: String,
    val code: String,
    val main: Boolean,
    val webUrl: String,
    /** Opens the Telegram bot with this code, when the bot is on. */
    val telegramUrl: String? = null,
    val createdAt: String,
    val funnel: Funnel,
)

@Serializable
data class ReferralDay(val day: String, val clicks: Int, val signups: Int, val paid: Int)

@Serializable
data class CountBy(val key: String, val count: Int)

@Serializable
enum class ReferredStatus {
    /** Signed up, never paid. */
    @SerialName("registered") REGISTERED,
    /** Has Pro now. */
    @SerialName("active") ACTIVE,
    /** Paid before, Pro has ended. */
    @SerialName("lapsed") LAPSED,
}

@Serializable
data class ReferredUser(
    /** Masked: "al***@gmail.com", or "Telegram". */
    val who: String,
    val linkName: String,
    val joinedAt: String,
    /** "website", "app" or "telegram". */
    val channel: String,
    val status: ReferredStatus,
    val purchases: Int,
)

@Serializable
data class ReferralStatsRes(
    val period: ReferralPeriod,
    val totals: Funnel,
    val days: List<ReferralDay>,
    val links: List<ReferralLink>,
    /** Where clicks came from: utm_source or referring site; "direct" when unknown, "telegram" for the bot. */
    val sources: List<CountBy>,
    /** Sign-ups by where they signed up: "website", "app", "telegram". */
    val channels: List<CountBy>,
    val recent: List<ReferredUser>,
    val rewardDays: Int,
    val daysEarned: Int,
)

@Serializable
data class CreateReferralLinkReq(val name: String, /** Optional custom code, 3 to 32 letters, digits or "-". */ val code: String? = null)

@Serializable
data class RenameReferralLinkReq(val name: String)

@Serializable
data class TopReferrer(val email: String, val links: Int, val funnel: Funnel)

/** Owner view of the whole program. */
@Serializable
data class AdminReferralsRes(
    val period: ReferralPeriod,
    val totals: Funnel,
    val days: List<ReferralDay>,
    val sources: List<CountBy>,
    val channels: List<CountBy>,
    val topReferrers: List<TopReferrer>,
)

/* ---------- owner dashboard ---------- */

@Serializable
data class DayCount(val day: String, val count: Int)

@Serializable
data class Revenue(
    /** Monthly recurring revenue in minor units, from configured list prices. */
    val mrrMinor: Long,
    val currency: String,
    val paying: Int,
    val free: Int,
    val conversion: Double,
    val newSubsThisWeek: Int,
    val cancelledThisMonth: Int,
    val yearly: Int,
    val monthly: Int,
    val fromReferrals: Int,
    val newPayingLast7Days: List<DayCount>,
)

@Serializable
data class ServerHealth(
    val id: String,
    val name: String,
    val connectedNow: Int,
    val maxPeers: Int,
    val cpu: Double,
    val memUsedBytes: Long,
    val memTotalBytes: Long,
    val trafficThisMonthBytes: Long,
    val trafficLimitBytes: Long? = null,
    val uptimeSeconds: Long,
    val paidThrough: String? = null,
    val warnings: List<String>,
)

@Serializable
data class AdminOverviewRes(val revenue: Revenue, val server: ServerHealth)

/* ---------- errors ---------- */

@Serializable
enum class ErrorCode {
    BAD_REQUEST, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, INVALID_CODE, TOO_MANY_ATTEMPTS,
    DEVICE_LIMIT, DATA_LIMIT, SERVER_FULL, UNSUPPORTED_PROTOCOL, NOT_IMPLEMENTED, INTERNAL,
}

@Serializable
data class ErrorBody(val code: ErrorCode, val message: String)

@Serializable
data class ErrorRes(val error: ErrorBody)

@Serializable
data class OkRes(val ok: Boolean = true)

/* ---------- VPN keys (website / Telegram purchases) ---------- */

/**
 * A server-generated VPN config for apps other than JagaNet (AmneziaVPN, AmneziaWG, WireGuard).
 * The link URLs carry a secret token: whoever has one can use the key.
 */
@Serializable
data class AccessKey(
    val id: String,
    val name: String,
    val protocol: String,
    val location: String,
    /** Page with the QR code, download and setup steps. */
    val pageUrl: String,
    /** The .conf file. */
    val configUrl: String,
    /** QR code (PNG) of the .conf text. */
    val qrUrl: String,
    val createdAt: String,
)

@Serializable
data class KeysRes(val keys: List<AccessKey>)

/* ---------- orders (website / Telegram) ---------- */

@Serializable
enum class OrderStatus { @SerialName("pending") PENDING, @SerialName("paid") PAID, @SerialName("cancelled") CANCELLED }

@Serializable
data class CreateOrderReq(val productId: ProductId)

@Serializable
data class OrderRes(
    val id: String,
    val productId: ProductId,
    val status: OrderStatus,
    /** Formatted, e.g. "$4.99". */
    val amount: String,
    /** Where to send the buyer to pay; null once paid. */
    val checkoutUrl: String?,
)

/** Public settings the website needs. */
@Serializable
data class SiteInfo(
    val androidAppUrl: String?,
    val iosAppUrl: String?,
    val telegramBotUrl: String?,
    /** Payments go through the built-in test checkout (no real money). */
    val testPayments: Boolean,
    val paymentsEnabled: Boolean,
)
