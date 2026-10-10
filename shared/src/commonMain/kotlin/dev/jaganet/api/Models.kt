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
enum class BillingSource {
    @SerialName("apple") APPLE, @SerialName("google") GOOGLE, @SerialName("referral") REFERRAL, @SerialName("dev") DEV,
    /** Paid on the website, through the Telegram bot or from the app (all through our checkout). */
    @SerialName("web") WEB, @SerialName("telegram") TELEGRAM, @SerialName("app") APP,
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
    /** The bought tariff's name, as it was when bought; null for the free plan, invite rewards and the owner. */
    val tariffName: String? = null,
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
data class Payment(
    val id: String,
    /** "referral" for invite rewards, else the tariff id. */
    val productId: String,
    val source: BillingSource,
    val startedAt: String,
    val expiresAt: String,
    val status: PaymentStatus,
    val tariffName: String? = null,
)

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
enum class DurationUnit { @SerialName("days") DAYS, @SerialName("months") MONTHS }

/** A tariff on sale, priced in the reader's currency (rubles for Russian, euros otherwise). */
@Serializable
data class Tariff(
    val id: String,
    val name: String,
    val durationValue: Int,
    val durationUnit: DurationUnit,
    /** Minor units (kopecks, cents). */
    val priceMinor: Long,
    val currency: String,
    val deviceLimit: Int,
    /** Per calendar month; null = unlimited. */
    val monthlyDataLimitBytes: Long? = null,
    val badge: String? = null,
)

@Serializable
data class FreePlan(val monthlyDataLimitBytes: Long, val deviceLimit: Int)

@Serializable
data class PlansRes(
    val tariffs: List<Tariff>,
    val free: FreePlan,
    /** Buying works right now (a payment service is connected). */
    val paymentsEnabled: Boolean = false,
)

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
    /** A feature that isn't set up on this server (e-mail, payments, store purchases). */
    SERVICE_UNAVAILABLE,
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
data class CreateOrderReq(val tariffId: String, /** "web" (default) or "app". */ val channel: String? = null)

@Serializable
data class OrderRes(
    val id: String,
    val tariffName: String,
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
    /** Sign-in by e-mail works (e-mail is set up, or codes are shown in test mode). */
    val emailSignIn: Boolean = true,
    /** Sign-in codes are shown on screen (test mode). */
    val testCodes: Boolean = false,
)

/* ---------- server monitoring (owner only) ---------- */

@Serializable
enum class CheckLevel { @SerialName("ok") OK, @SerialName("warning") WARNING, @SerialName("critical") CRITICAL, @SerialName("unknown") UNKNOWN }

/**
 * One health check. [message] is an English text (translation key) with `{placeholders}`
 * filled from [args], e.g. "Packet loss {loss}% to {host}".
 */
@Serializable
data class HealthCheck(
    /** Stable id: "vpn", "db", "https", "cert", "bot", "cpu", "memory", "disk", "channel", "net_errors", "ping", "dns", "traffic". */
    val key: String,
    val level: CheckLevel,
    val message: String,
    val args: Map<String, String> = emptyMap(),
)

/** One minute (or an average over a longer bucket) of server measurements. */
@Serializable
data class MetricPoint(
    val ts: String,
    /** 0..1 */
    val cpu: Double,
    val memUsed: Long,
    val memTotal: Long,
    val diskFree: Long,
    val diskTotal: Long,
    /** Internet interface throughput, bits per second. */
    val rxBps: Long,
    val txBps: Long,
    /** Busiest direction / channel capacity, 0..1; null when the capacity is unknown. */
    val utilization: Double? = null,
    /** Average round trip to the ping targets, ms; null when all pings failed. */
    val pingMs: Double? = null,
    /** 0..100 */
    val lossPct: Double? = null,
    val peers: Int = 0,
    /** Peers with a handshake in the last 3 minutes. */
    val online: Int = 0,
    /** Interface errors and drops in this interval. */
    val errors: Long = 0,
    val drops: Long = 0,
)

@Serializable
data class NetworkInfo(
    /** The internet-facing interface, e.g. "enp1s0". */
    val iface: String?,
    /** Channel capacity in Mbit/s used for the load percentage. */
    val capacityMbps: Long? = null,
    /** "config" (CHANNEL_MBPS), "link" (reported by the network card) or null. */
    val capacitySource: String? = null,
    val monthRxBytes: Long = 0,
    val monthTxBytes: Long = 0,
    /** The hosting plan's monthly traffic allowance, if set. */
    val monthLimitBytes: Long? = null,
)

@Serializable
data class MonitorAlert(
    val id: String,
    val key: String,
    val level: CheckLevel,
    val message: String,
    val args: Map<String, String> = emptyMap(),
    val openedAt: String,
    val resolvedAt: String? = null,
)

@Serializable
data class NotifySettings(val emails: List<String>, val emailReady: Boolean, val telegramChats: Int)

@Serializable
data class MonitorRes(
    val checkedAt: String?,
    val overall: CheckLevel,
    val checks: List<HealthCheck>,
    val network: NetworkInfo,
    /** Oldest first. */
    val series: List<MetricPoint>,
    /** Open alerts first, then the latest resolved ones. */
    val alerts: List<MonitorAlert>,
    val notify: NotifySettings,
)

/* ---------- one-time sign-in links (Telegram /login, owner login from the server) ---------- */

@Serializable
data class LinkLoginReq(val token: String, val device: DeviceInfo)

/* ---------- owner admin panel: settings ---------- */

@Serializable
data class PlanSettings(
    val freeMonthlyGb: Long,
    val freeDeviceLimit: Int,
    /** Devices during invite reward days. */
    val proDeviceLimit: Int,
    val referralRewardDays: Int,
)

/* ---------- owner admin panel: tariff builder ---------- */

@Serializable
enum class TariffStatus {
    /** On sale. */
    @SerialName("active") ACTIVE,
    /** Not on sale for now. */
    @SerialName("hidden") HIDDEN,
    /** Retired, kept for history. */
    @SerialName("archived") ARCHIVED,
}

/** What the owner sends to create or change a tariff. Already bought subscriptions keep their old terms. */
@Serializable
data class TariffReq(
    val name: String,
    val durationValue: Int,
    val durationUnit: DurationUnit,
    val priceRubMinor: Long,
    val priceEurMinor: Long,
    val deviceLimit: Int,
    /** Per calendar month; null = unlimited. */
    val trafficGb: Long? = null,
    val badge: String? = null,
    val sort: Int = 0,
    val status: TariffStatus = TariffStatus.ACTIVE,
)

@Serializable
data class AdminTariff(
    val id: String,
    val name: String,
    val durationValue: Int,
    val durationUnit: DurationUnit,
    val priceRubMinor: Long,
    val priceEurMinor: Long,
    val deviceLimit: Int,
    val trafficGb: Long? = null,
    val badge: String? = null,
    val sort: Int,
    val status: TariffStatus,
    /** Paid subscriptions bought with it, all time. */
    val sold: Int,
    /** People using it right now. */
    val activeNow: Int,
    val createdAt: String,
)

@Serializable
data class AdminTariffsRes(val tariffs: List<AdminTariff>)

@Serializable
data class SmtpSettings(
    val host: String,
    val port: Int,
    val user: String? = null,
    /** Write only: the server never sends it back. null when saving = keep the current one. */
    val password: String? = null,
    val from: String,
    /** "starttls", "ssl" or "none" */
    val security: String = "starttls",
)

@Serializable
data class AlertSettings(val emails: List<String>, val telegramChats: List<Long>)

/** The server's internet channel, for the load percentage and the monthly traffic warning. */
@Serializable
data class NetworkSettings(
    /** Channel speed limit in Mbit/s (from the hosting plan); null = take what the network card reports. */
    val channelMbps: Long? = null,
    /** Monthly traffic included in the hosting plan, GB; null = unlimited. */
    val monthlyTrafficGb: Long? = null,
)

@Serializable
data class ModeSettings(
    /** Show sign-in codes on screen instead of e-mailing them. Anyone can then sign in as anyone. */
    val showSignInCodes: Boolean,
    /** Built-in test checkout: subscriptions without real money. */
    val testPayments: Boolean,
)

@Serializable
data class AdminSettingsRes(
    val plans: PlanSettings,
    /** null = e-mail not set up. Its password is never returned. */
    val smtp: SmtpSettings? = null,
    val smtpHasPassword: Boolean = false,
    val alerts: AlertSettings,
    val modes: ModeSettings,
    val network: NetworkSettings = NetworkSettings(),
    /** A real payment service is connected. */
    val paymentsConnected: Boolean,
    val botEnabled: Boolean,
)

@Serializable
data class TestEmailReq(val to: String)

/* ---------- owner admin panel: money ---------- */

@Serializable
data class FinanceDay(val day: String, val revenue: List<MoneyAmount>, val orders: Int, val newSubscriptions: Int, val renewals: Int)

@Serializable
data class PaymentRow(
    val at: String,
    val email: String,
    /** The tariff's name as bought. */
    val product: String,
    /** null for store purchases (the stores report amounts in their own consoles). */
    val amount: MoneyAmount? = null,
    /** "web", "telegram", "apple", "google", "dev" */
    val channel: String,
    val renewal: Boolean,
)

@Serializable
data class FinanceRes(
    val period: ReferralPeriod,
    /** Received in the period, per currency (website and Telegram payments). */
    val revenue: List<MoneyAmount>,
    val paidOrders: Int,
    val newSubscriptions: Int,
    val renewals: Int,
    /** People with a paid subscription right now. */
    val activeSubscribers: Int,
    /** Monthly recurring revenue of active website / Telegram subscriptions, per currency. */
    val mrr: List<MoneyAmount>,
    /** Paid subscriptions in the period by channel ("web", "telegram", "app"). */
    val byChannel: List<CountBy>,
    /** By tariff name. */
    val byProduct: List<CountBy>,
    val days: List<FinanceDay>,
    val recent: List<PaymentRow>,
    /** Orders started but not paid in the period (abandoned checkouts). */
    val unpaidOrders: Int,
)

/* ---------- owner admin panel: VPN nodes (locations) ---------- */

@Serializable
enum class NodeState {
    /** Created; the install command hasn't been run yet. */
    @SerialName("waiting") WAITING,
    /** The agent reports in. */
    @SerialName("online") ONLINE,
    /** No report for a few minutes. */
    @SerialName("offline") OFFLINE,
    /** Switched off by the owner: gets no new devices. */
    @SerialName("disabled") DISABLED,
}

@Serializable
data class AdminNode(
    val id: String,
    val name: String,
    val city: String,
    val countryCode: String,
    /** False for the VPN on the main server itself. */
    val remote: Boolean,
    val state: NodeState,
    val active: Boolean,
    val maxPeers: Int,
    /** Devices and keys on this node. */
    val peers: Int,
    /** Of those, seen in the last 3 minutes. */
    val online: Int,
    val endpoint: String? = null,
    val hostname: String? = null,
    /** The machine's own address, from its last registration. */
    val publicIp: String? = null,
    val protocols: List<String> = emptyList(),
    val lastReportAt: String? = null,
    /** Latest health from the agent (0..1 and bits per second). */
    val cpu: Double? = null,
    val memUsed: Long? = null,
    val memTotal: Long? = null,
    val rxBps: Long? = null,
    val txBps: Long? = null,
    val createdAt: String,
)

@Serializable
data class AdminNodesRes(val nodes: List<AdminNode>)

@Serializable
data class NodeReq(
    val name: String,
    val city: String,
    /** Two letters, e.g. "DE". */
    val countryCode: String,
    val maxPeers: Int = 250,
    val active: Boolean = true,
    /**
     * Optional DNS name for the VPN address, e.g. de1.vpn.example.com. With it, key files
     * keep working when the node moves to another machine: point the name at the new address.
     */
    val hostname: String? = null,
)

/** A new node or a new token: [command] is run once as root on the node. Shown only now. */
@Serializable
data class NodeInstallRes(val node: AdminNode, val command: String)
