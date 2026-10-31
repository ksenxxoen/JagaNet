package dev.jaganet.server.services

import dev.jaganet.api.MoneyAmount
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * The admin panel's user, payment and service control (issue #39): what /v1/admin/control/…,
 * /v1/admin/users… and /v1/admin/payments… send and take. Only the website's admin pages use
 * these, so they live with the server, not in the shared models of the apps.
 */

/** One user in the search list. [state]: owner, active, free, free_ended, expired; [blocked] on top of it. */
@Serializable
data class CtlUserRow(
    val id: String,
    val email: String,
    val telegramId: Long? = null,
    val telegramUsername: String? = null,
    val role: String,
    val createdAt: String,
    val state: String,
    val tariffName: String? = null,
    /** Access ends (any active subscription: paid, gift, invite reward, admin grant); null = no end. */
    val accessUntil: String? = null,
    /** Paid time ends (bought subscriptions only); null when nothing was bought or it is over. */
    val paidUntil: String? = null,
    val blocked: Boolean = false,
    val devices: Int = 0,
    val online: Boolean = false,
    /** Different client addresses in the last 24 hours, over all devices and keys. */
    val ips24h: Int = 0,
    /** More addresses than the sharing threshold: maybe the subscription is shared. */
    val sharing: Boolean = false,
    val note: String? = null,
    /** What the search matched: email, telegram, uuid, token, ip, id, note. */
    val matched: String? = null,
)

@Serializable
data class CtlUsersRes(val users: List<CtlUserRow>, val sharingThreshold: Int, val telegramUsernames: Boolean)

@Serializable
data class CtlSubscription(
    val id: String,
    val source: String,
    /** order, admin, gift, test, referral, store (App Store / Google Play), other. */
    val kind: String,
    val tariffName: String? = null,
    val startedAt: String,
    val expiresAt: String,
    /** active, expired, cancelled, refunded, upcoming (starts later). */
    val status: String,
    val deviceLimit: Int? = null,
    val monthlyBytes: Long? = null,
    val renewal: Boolean = false,
    val amount: MoneyAmount? = null,
)

@Serializable
data class CtlPayment(
    val id: String,
    val createdAt: String,
    val paidAt: String? = null,
    val userId: String,
    val email: String,
    /** The tariff bought, as it was when ordered. */
    val product: String,
    val amount: MoneyAmount,
    /** web, telegram, app. */
    val channel: String,
    /** The payment service (test, …). */
    val provider: String,
    /** pending, paid, cancelled. */
    val status: String,
    val externalId: String? = null,
)

@Serializable
data class CtlTotals(
    val revenueToday: List<MoneyAmount>,
    val revenueWeek: List<MoneyAmount>,
    val revenueMonth: List<MoneyAmount>,
    /** Over the filter's period. */
    val revenue: List<MoneyAmount>,
    val paidCount: Int,
    val pendingCount: Int,
    val cancelledCount: Int,
    /** Users whose first paid order is in the period. */
    val newPayers: Int,
)

@Serializable
data class CtlPaymentsRes(val payments: List<CtlPayment>, val totals: CtlTotals, val truncated: Boolean = false)

@Serializable
data class CtlIp(val ip: String, val lastSeen: String, val deviceId: String? = null, val serverId: String? = null)

@Serializable
data class CtlDevice(
    val id: String,
    val name: String,
    /** device (a Boop app), key (a key for other apps), vless (the VLESS key outside the limit). */
    val kind: String,
    val platform: String,
    val createdAt: String,
    val protocol: String? = null,
    val serverId: String? = null,
    val serverName: String? = null,
    val address: String? = null,
    /** Last handshake (AmneziaWG) or the last time its counters moved (VLESS). */
    val lastSeenAt: String? = null,
    val online: Boolean = false,
    val lastIp: String? = null,
    val lastIpAt: String? = null,
    val ips24h: List<CtlIp> = emptyList(),
    val traffic24h: Long = 0,
    val trafficTotal: Long = 0,
    /** The VLESS key's UUID (its credential on the nodes). */
    val uuid: String? = null,
    /** The start of the key link's token; the whole token is never shown. */
    val tokenHint: String? = null,
    val rekeyPending: Boolean = false,
    /** Off its node: the user is blocked or has no access. */
    val suspended: Boolean = false,
)

@Serializable
data class CtlReferrer(val id: String, val email: String, val link: String? = null)

@Serializable
data class CtlAudit(
    val id: Long,
    val at: String,
    val adminEmail: String,
    val userId: String? = null,
    val userEmail: String? = null,
    val action: String,
    val details: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class CtlTariff(val id: String, val name: String, val status: String)

@Serializable
data class CtlUserCard(
    val user: CtlUserRow,
    val lang: String? = null,
    /** When the current subscription (or run of back-to-back ones) started. */
    val startedAt: String? = null,
    val deviceLimit: Int,
    val monthlyBytes: Long? = null,
    val usageThisMonth: Long = 0,
    val blockedAt: String? = null,
    val blockedReason: String? = null,
    val subscriptions: List<CtlSubscription>,
    val payments: List<CtlPayment>,
    val devices: List<CtlDevice>,
    /** Addresses over all devices in 24 hours. */
    val ips24h: List<CtlIp>,
    /** Devices and keys seen in 24 hours. */
    val devicesActive24h: Int,
    val sharingThreshold: Int,
    /** Who invited them; null until invites are tied to users (issue #40) or when nobody did. */
    val referrer: CtlReferrer? = null,
    val referrals: Int = 0,
    val audit: List<CtlAudit>,
    /** Tariffs for "change plan" and "grant a period". */
    val tariffs: List<CtlTariff>,
)

@Serializable
data class CtlServer(
    val id: String,
    val name: String,
    val countryCode: String,
    val protocols: List<String>,
    /** online, offline, waiting, disabled (as on the Nodes tab). */
    val state: String,
    val remote: Boolean,
    val peers: Int,
    val maxPeers: Int,
    val online: Int,
    val traffic24h: Long,
    val lastReportAt: String? = null,
    /** The agent's version from its report; null for agents from before issue #39. */
    val agentVersion: String? = null,
    val cpu: Double? = null,
    val rxBps: Long? = null,
    val txBps: Long? = null,
)

@Serializable
data class CtlDashboard(
    val usersTotal: Int,
    /** With Pro access now (paid, gift, invite reward or admin grant); the owner not counted. */
    val usersActive: Int,
    val usersExpiring3d: Int,
    val usersBlocked: Int,
    val newUsers24h: Int,
    val newUsers7d: Int,
    val onlineDevices: Int,
    val onlineUsers: Int,
    val servers: List<CtlServer>,
    val revenueToday: List<MoneyAmount>,
    val revenueWeek: List<MoneyAmount>,
    val revenueMonth: List<MoneyAmount>,
    val paidOrdersMonth: Int,
    val newPayersMonth: Int,
    val sharingThreshold: Int,
    /** Users over the sharing threshold in the last 24 hours, most addresses first. */
    val sharing: List<CtlUserRow>,
    val expiring: List<CtlUserRow>,
)

@Serializable
data class CtlAuditRes(val entries: List<CtlAudit>)

@Serializable
data class CtlSettings(val sharingThreshold: Int = DEFAULT_SHARING_THRESHOLD)

const val DEFAULT_SHARING_THRESHOLD = 5

/* Requests */

@Serializable data class CtlDaysReq(val days: Int, val tariffId: String? = null, val comment: String? = null)
@Serializable data class CtlPlanReq(val tariffId: String, val comment: String? = null)
@Serializable data class CtlBlockReq(val reason: String? = null)
@Serializable data class CtlNoteReq(val note: String? = null)
@Serializable data class CtlReissueRes(val mode: String, val uuid: String? = null)
