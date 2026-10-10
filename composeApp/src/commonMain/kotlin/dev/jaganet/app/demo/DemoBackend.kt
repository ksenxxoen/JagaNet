package dev.jaganet.app.demo

import dev.jaganet.api.AdminOverviewRes
import dev.jaganet.api.AdminReferralsRes
import dev.jaganet.api.BillingSource
import dev.jaganet.api.Format
import dev.jaganet.api.i18n.I18n
import dev.jaganet.api.i18n.Lang
import dev.jaganet.api.ConnectionEventReq
import dev.jaganet.api.ConnectionEventType
import dev.jaganet.api.CountBy
import dev.jaganet.api.CreateReferralLinkReq
import dev.jaganet.api.DayCount
import dev.jaganet.api.CreateOrderReq
import dev.jaganet.api.DurationUnit
import dev.jaganet.api.OrderRes
import dev.jaganet.api.OrderStatus
import dev.jaganet.api.Tariff
import dev.jaganet.api.Device
import dev.jaganet.api.DeviceTraffic
import dev.jaganet.api.DevicesRes
import dev.jaganet.api.EmailStartReq
import dev.jaganet.api.EmailStartRes
import dev.jaganet.api.EmailVerifyReq
import dev.jaganet.api.Entitlement
import dev.jaganet.api.ErrorBody
import dev.jaganet.api.ErrorCode
import dev.jaganet.api.ErrorRes
import dev.jaganet.api.FreePlan
import dev.jaganet.api.Funnel
import dev.jaganet.api.MeRes
import dev.jaganet.api.MoneyAmount
import dev.jaganet.api.OkRes
import dev.jaganet.api.PairRedeemReq
import dev.jaganet.api.PairingCodeRes
import dev.jaganet.api.Payment
import dev.jaganet.api.PaymentStatus
import dev.jaganet.api.PaymentsRes
import dev.jaganet.api.PlanId
import dev.jaganet.api.Platform
import dev.jaganet.api.PlansRes
import dev.jaganet.api.Protocols
import dev.jaganet.api.ReferralDay
import dev.jaganet.api.ReferralLink
import dev.jaganet.api.ReferralPeriod
import dev.jaganet.api.ReferralRes
import dev.jaganet.api.ReferralStatsRes
import dev.jaganet.api.ReferredStatus
import dev.jaganet.api.ReferredUser
import dev.jaganet.api.RenameReferralLinkReq
import dev.jaganet.api.RenameDeviceReq
import dev.jaganet.api.Revenue
import dev.jaganet.api.Role
import dev.jaganet.api.ServerHealth
import dev.jaganet.api.ServerLocation
import dev.jaganet.api.ServersRes
import dev.jaganet.api.SessionRes
import dev.jaganet.api.StatsBucket
import dev.jaganet.api.StatsPeriod
import dev.jaganet.api.StatsRes
import dev.jaganet.api.TunnelConfig
import dev.jaganet.api.TopReferrer
import dev.jaganet.api.TunnelProvisionReq
import dev.jaganet.api.Usage
import dev.jaganet.api.User
import dev.jaganet.api.WireGuard
import dev.jaganet.api.AmneziaWG
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The JagaNet API, answered inside the app. Same endpoints and data shapes as the
 * real server (shared module), with the simulation's demo accounts and history.
 * Used by the web demo, so the app runs from a single web page with no server.
 * Business rules are simplified; the real ones live in server/.
 */
class DemoBackend(private val now: () -> Instant = { Clock.System.now() }) {
    private val json = Protocols.json
    private val rnd = Random(7)

    private class DUser(
        val id: String, val email: String, val role: Role, val createdAt: Instant,
        var plan: PlanId = PlanId.FREE, var tariffName: String? = null, var source: BillingSource? = null,
        var expiresAt: Instant? = null, val code: String,
        val payments: MutableList<Payment> = mutableListOf(),
        var invited: Int = 0, var subscribed: Int = 0, var daysEarned: Int = 0,
    )

    private class DDevice(
        val id: String, val userId: String, var name: String, val platform: Platform, val createdAt: Instant,
        var protocol: String? = null, var address: String? = null, var lastSeen: Instant? = null,
        var connectedSince: Instant? = null,
    )

    /** A referral link. [weight] scales its made-up daily traffic; new links start at 0. */
    private class DLink(
        val id: String, val userId: String, var name: String, val code: String, val main: Boolean, val createdAt: Instant,
        val weight: Int = 0, var archived: Boolean = false,
    )

    private class Sample(val deviceId: String, val hour: Instant, val rx: Long, val tx: Long)
    private class Session(val deviceId: String, val start: Instant, val end: Instant?, val peak: Long?)

    private val users = mutableListOf<DUser>()
    private val devices = mutableListOf<DDevice>()
    private val samples = mutableListOf<Sample>()
    private val sessions = mutableListOf<Session>()
    private val links = mutableListOf<DLink>()
    private val tokens = mutableMapOf<String, Pair<String, String>>() // token -> (userId, deviceId)
    private val codes = mutableMapOf<String, String>() // email -> sign-in code
    private var pairing: Pair<String, String>? = null // code -> userId
    private var nextId = 1
    private var nextIp = 10

    private fun id() = "d${nextId++}"
    private fun hour(t: Instant) = Instant.fromEpochSeconds(t.epochSeconds / 3600 * 3600)

    init {
        val t = now()
        val owner = DUser(id(), "owner@jaganet.dev", Role.OWNER, t - 120.days, code = "OWNER-0000")
        val alex = DUser(id(), "alex@example.com", Role.USER, t - 60.days, PlanId.PRO, "Pro на год", BillingSource.WEB,
            t - 25.days + 365.days, "ALEX-7Q2K", invited = 13, subscribed = 3)
        alex.payments += Payment(id(), "t-year", BillingSource.WEB, (t - 25.days).toString(), (t - 25.days + 365.days).toString(), PaymentStatus.ACTIVE, "Pro на год")
        alex.payments += Payment(id(), "t-month", BillingSource.WEB, (t - 55.days).toString(), (t - 25.days).toString(), PaymentStatus.EXPIRED, "Pro на месяц")
        val sam = DUser(id(), "sam@example.com", Role.USER, t - 20.days, code = "SAM-4F8D")
        users += listOf(owner, alex, sam)

        val laptop = DDevice(id(), alex.id, "Work laptop", Platform.DESKTOP, t - 50.days, Protocols.AMNEZIAWG, "10.8.0.${nextIp++}", t)
        val tablet = DDevice(id(), alex.id, "Tablet", Platform.ANDROID, t - 50.days, Protocols.WIREGUARD, "10.8.0.${nextIp++}", t - 3.days)
        val samPhone = DDevice(id(), sam.id, "Old phone", Platform.ANDROID, t - 18.days)
        devices += listOf(laptop, tablet, samPhone)

        history(laptop.id, 60e6, sessionsPerDay = 1)
        history(tablet.id, 8e6, sessionsPerDay = 0)
        samples += Sample(samPhone.id, hour(t - 2.days), 5_900_000_000, 1_100_000_000) // ~70 % of 10 GB

        links += DLink(id(), alex.id, "Main link", alex.code, true, alex.createdAt, weight = 2)
        links += DLink(id(), alex.id, "Instagram", "ALEX-INSTA", false, t - 45.days, weight = 5)
        links += DLink(id(), alex.id, "YouTube", "ALEX-YT", false, t - 20.days, weight = 3)
        links += DLink(id(), owner.id, "Main link", owner.code, true, owner.createdAt, weight = 1)
    }

    /** Hourly traffic with an evening peak, plus one session a day. */
    private fun history(deviceId: String, scale: Double, sessionsPerDay: Int) {
        val t = now()
        for (h in 30 * 24 downTo 1) {
            val at = hour(t - h.hours)
            val hod = (at.epochSeconds / 3600 % 24).toInt()
            val shape = 0.35 + 0.65 * (1 + sin((hod - 14) / 24.0 * 2 * PI)) / 2
            val rx = (scale * shape * (0.5 + rnd.nextDouble())).toLong()
            samples += Sample(deviceId, at, rx, (rx * 0.19).toLong())
        }
        for (d in 30 downTo 1) repeat(sessionsPerDay) {
            val start = t - d.days + 9.hours
            sessions += Session(deviceId, start, start + (60 + rnd.nextInt(120)).minutes, (60e6 + rnd.nextDouble() * 60e6).toLong())
        }
    }

    // ------------------------------------------------------------ helpers

    private fun toUser(u: DUser) = User(u.id, u.email, u.role, u.createdAt.toString())

    private fun entitlement(u: DUser): Entitlement {
        val active = u.plan == PlanId.PRO && (u.expiresAt?.let { it > now() } ?: false)
        return if (active) Entitlement(PlanId.PRO, u.tariffName, u.source, u.expiresAt.toString(), false, 5, null)
        else Entitlement(PlanId.FREE, deviceLimit = 1, monthlyDataLimitBytes = 10_000_000_000)
    }

    private fun monthStart(): Instant {
        val t = now()
        // Good enough for a demo: the last 30 days count as "this month".
        return t - 30.days
    }

    private fun usage(u: DUser): Long {
        val mine = devices.filter { it.userId == u.id }.map { it.id }.toSet()
        return samples.filter { it.deviceId in mine && it.hour >= monthStart() }.sumOf { it.rx + it.tx }
    }

    private fun me(u: DUser) = MeRes(toUser(u), entitlement(u), Usage(monthStart().toString(), usage(u), devices.count { it.userId == u.id && it.address != null }))

    private fun session(u: DUser, device: Pair<String, Platform>): SessionRes {
        val d = DDevice(id(), u.id, device.first, device.second, now(), lastSeen = now())
        devices += d
        val token = "demo-${Random.nextLong()}"
        tokens[token] = u.id to d.id
        return SessionRes(token, toUser(u), d.id)
    }

    private fun stats(userId: String, period: StatsPeriod): StatsRes {
        val (n, size) = when (period) {
            StatsPeriod.DAY -> 12 to 2.hours
            StatsPeriod.WEEK -> 7 to 24.hours
            StatsPeriod.MONTH -> 30 to 24.hours
        }
        val sizeS = size.inWholeSeconds
        val end = Instant.fromEpochSeconds((now().epochSeconds / sizeS + 1) * sizeS)
        val start = end - size * n
        val mine = devices.filter { it.userId == userId }.associateBy { it.id }
        val rx = LongArray(n)
        val tx = LongArray(n)
        val byDevice = mutableMapOf<String, Long>()
        for (s in samples) {
            if (s.deviceId !in mine || s.hour < start || s.hour >= end) continue
            val i = ((s.hour.epochSeconds - start.epochSeconds) / sizeS).toInt()
            rx[i] += s.rx; tx[i] += s.tx
            byDevice[s.deviceId] = (byDevice[s.deviceId] ?: 0) + s.rx + s.tx
        }
        val mySessions = sessions.filter { it.deviceId in mine && it.start < end && (it.end ?: now()) > start }
        val protectedS = mySessions.sumOf { (minOf(it.end ?: now(), end, now()) - maxOf(it.start, start)).inWholeSeconds.coerceAtLeast(0) }
        return StatsRes(
            period = period,
            buckets = (0 until n).map { StatsBucket((start + size * it).toString(), rx[it], tx[it]) },
            totalRxBytes = rx.sum(),
            totalTxBytes = tx.sum(),
            protectedSeconds = protectedS,
            sessions = mySessions.size,
            avgDownBps = if (protectedS > 0) rx.sum() * 8 / protectedS else null,
            peakDownBps = mySessions.mapNotNull { it.peak }.maxOrNull(),
            byDevice = byDevice.entries.sortedByDescending { it.value }.map { DeviceTraffic(it.key, mine.getValue(it.key).name, it.value) },
        )
    }

    // ------------------------------------------------------------ referral program

    private fun dayOf(t: Instant) = t.epochSeconds / 86400
    private fun isoDay(day: Long) = Instant.fromEpochSeconds(day * 86400).toString().take(10)

    /** Every user has a main link with their invite code. */
    private fun linksOf(u: DUser): List<DLink> {
        if (links.none { it.userId == u.id && it.main }) links.add(0, DLink(id(), u.id, "Main link", u.code, true, u.createdAt))
        return links.filter { it.userId == u.id && !it.archived }.sortedWith(compareByDescending<DLink> { it.main }.thenBy { it.createdAt })
    }

    /** Made-up but stable numbers for one link on one day. */
    private fun linkDay(l: DLink, day: Long): ReferralDay {
        if (l.weight == 0 || day < dayOf(l.createdAt)) return ReferralDay(isoDay(day), 0, 0, 0)
        val r = Random(l.code.hashCode() * 31 + day.toInt())
        val clicks = r.nextInt(l.weight * 4 + 1)
        val signups = (0 until clicks).count { r.nextInt(9) == 0 }
        val paid = (0 until signups).count { r.nextInt(3) == 0 }
        return ReferralDay(isoDay(day), clicks, signups, paid)
    }

    private fun periodOf(q: String?) = when (q) { "7d" -> ReferralPeriod.D7; "90d" -> ReferralPeriod.D90; "all" -> ReferralPeriod.ALL; else -> ReferralPeriod.D30 }

    private fun firstDay(period: ReferralPeriod, ls: List<DLink>): Long {
        val today = dayOf(now())
        return when (period) {
            ReferralPeriod.D7 -> today - 6
            ReferralPeriod.D30 -> today - 29
            ReferralPeriod.D90 -> today - 89
            ReferralPeriod.ALL -> ls.minOfOrNull { dayOf(it.createdAt) } ?: today
        }
    }

    private fun series(ls: List<DLink>, from: Long): List<ReferralDay> = (from..dayOf(now())).map { d ->
        val days = ls.map { linkDay(it, d) }
        ReferralDay(isoDay(d), days.sumOf { it.clicks }, days.sumOf { it.signups }, days.sumOf { it.paid })
    }

    private fun funnel(days: List<ReferralDay>, cur: String, monthly: Long): Funnel {
        val paid = days.sumOf { it.paid }
        val purchases = paid + paid / 2
        return Funnel(
            clicks = days.sumOf { it.clicks }, visitors = days.sumOf { (it.clicks * 4 + 4) / 5 },
            signups = days.sumOf { it.signups }, paidUsers = paid, purchases = purchases,
            revenue = if (purchases > 0) listOf(MoneyAmount(purchases * monthly, cur)) else emptyList(),
        )
    }

    /** Where clicks came from and where people signed up, split from the totals. */
    private fun sources(ls: List<DLink>, from: Long): List<CountBy> {
        val m = mutableMapOf<String, Int>()
        for (l in ls) {
            val clicks = series(listOf(l), from).sumOf { it.clicks }
            when (l.name) {
                "Instagram" -> m["instagram.com"] = (m["instagram.com"] ?: 0) + clicks
                "YouTube" -> m["youtube.com"] = (m["youtube.com"] ?: 0) + clicks
                else -> {
                    m["telegram"] = (m["telegram"] ?: 0) + clicks * 2 / 5
                    m["direct"] = (m["direct"] ?: 0) + clicks - clicks * 2 / 5
                }
            }
        }
        return m.filterValues { it > 0 }.map { CountBy(it.key, it.value) }.sortedByDescending { it.count }
    }

    private fun channels(signups: Int): List<CountBy> {
        val web = signups * 55 / 100
        val app = signups * 30 / 100
        return listOf(CountBy("website", web), CountBy("app", app), CountBy("telegram", signups - web - app)).filter { it.count > 0 }.sortedByDescending { it.count }
    }

    private val demoPeople = listOf("ma***@gmail.com", "ol***@yandex.ru", "Telegram", "jo***@proton.me", "ka***@mail.ru", "de***@gmail.com", "Telegram", "an***@outlook.com")

    private fun recent(ls: List<DLink>, from: Long): List<ReferredUser> {
        val out = mutableListOf<ReferredUser>()
        for (d in dayOf(now()) downTo from) {
            for (l in ls) {
                val day = linkDay(l, d)
                repeat(day.signups) { i ->
                    val n = out.size
                    val who = demoPeople[n % demoPeople.size]
                    val channel = if (who == "Telegram") "telegram" else if (n % 3 == 1) "app" else "website"
                    val status = when { i < day.paid && n % 4 == 3 -> ReferredStatus.LAPSED; i < day.paid -> ReferredStatus.ACTIVE; else -> ReferredStatus.REGISTERED }
                    val purchases = if (status == ReferredStatus.REGISTERED) 0 else 1 + n % 2
                    out += ReferredUser(who, l.name, Instant.fromEpochSeconds(d * 86400 + 3600L * (9 + n % 12)).toString(), channel, status, purchases)
                }
                if (out.size >= 50) return out
            }
        }
        return out
    }

    private fun referralStats(u: DUser, period: ReferralPeriod, lang: Lang): ReferralStatsRes {
        val (cur, monthly, _) = prices(lang)
        val ls = linksOf(u)
        val from = firstDay(period, ls)
        val days = series(ls, from)
        val totals = funnel(days, cur, monthly)
        return ReferralStatsRes(
            period = period, totals = totals, days = days,
            links = ls.map { l ->
                ReferralLink(l.id, l.name, l.code, l.main, "https://jaganet.dev/r/${l.code}", "https://t.me/JagaNetBot?start=${l.code}",
                    l.createdAt.toString(), funnel(series(listOf(l), from), cur, monthly))
            },
            sources = sources(ls, from), channels = channels(totals.signups), recent = recent(ls, from),
            rewardDays = 30, daysEarned = u.daysEarned,
        )
    }

    private fun adminReferrals(period: ReferralPeriod, lang: Lang): AdminReferralsRes {
        val (cur, monthly, _) = prices(lang)
        val active = links.filter { !it.archived }
        val from = firstDay(period, active)
        val days = series(active, from)
        val totals = funnel(days, cur, monthly)
        val top = users.mapNotNull { u ->
            val ls = active.filter { it.userId == u.id }
            if (ls.isEmpty()) null else TopReferrer(u.email, ls.size, funnel(series(ls, from), cur, monthly))
        }.filter { it.funnel.clicks > 0 }.sortedByDescending { it.funnel.signups }
        return AdminReferralsRes(period, totals, days, sources(active, from), channels(totals.signups), top)
    }

    // ------------------------------------------------------------ routing

    private class ApiError(val status: HttpStatusCode, val code: ErrorCode, message: String, val n: Int? = null) : Exception(message)

    /** [msg] is an English server message (translation key); [n] the count for plural messages. */
    private fun langOf(req: HttpRequestData) = Lang.of(req.headers[HttpHeaders.AcceptLanguage]) ?: Lang.DEFAULT

    /** Like the real server: rubles for Russian, euros otherwise. (currency, monthly, yearly) in minor units. */
    private fun prices(lang: Lang): Triple<String, Long, Long> = if (lang == Lang.RU) Triple("RUB", 29_900L, 249_000L) else Triple("EUR", 499L, 3_999L)

    private fun tariffs(lang: Lang): List<Tariff> = prices(lang).let { (cur, monthly, yearly) ->
        listOf(
            Tariff("t-year", "Pro на год", 12, DurationUnit.MONTHS, yearly, cur, 5, badge = "Выгодно"),
            Tariff("t-month", "Pro на месяц", 1, DurationUnit.MONTHS, monthly, cur, 5),
        )
    }

    private fun err(status: HttpStatusCode, code: ErrorCode, msg: String, n: Int? = null): Nothing = throw ApiError(status, code, msg, n)

    val engine = MockEngine { req -> handle(req) }

    fun client() = HttpClient(engine)

    private suspend fun MockRequestHandleScope.handle(req: HttpRequestData): HttpResponseData {
        val path = req.url.encodedPath.substringAfter("/v1/").trimEnd('/')
        val body = req.body.toByteArray().decodeToString()
        return try {
            val (out, serializer) = route(req.method, path, body, req)
            @Suppress("UNCHECKED_CAST")
            respond(json.encodeToString(serializer as KSerializer<Any>, out), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        } catch (e: ApiError) {
            val lang = Lang.of(req.headers[HttpHeaders.AcceptLanguage]) ?: Lang.DEFAULT
            val text = e.n?.let { I18n.plural(lang, it.toLong(), e.message ?: "") } ?: I18n.tr(lang, e.message ?: "")
            respond(json.encodeToString(ErrorRes.serializer(), ErrorRes(ErrorBody(e.code, text))), e.status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

    private inline fun <reified T> read(body: String): T = json.decodeFromString(body)

    private fun auth(req: HttpRequestData): Pair<DUser, DDevice> {
        val token = req.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ") ?: err(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED, "Sign in again")
        val (uid, did) = tokens[token] ?: err(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED, "Sign in again")
        val u = users.firstOrNull { it.id == uid } ?: err(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED, "Sign in again")
        val d = devices.firstOrNull { it.id == did } ?: err(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED, "Sign in again")
        return u to d
    }

    private fun route(method: HttpMethod, path: String, body: String, req: HttpRequestData): Pair<Any, KSerializer<*>> {
        val ok = OkRes() to OkRes.serializer()
        when {
            method == HttpMethod.Post && path == "auth/email/start" -> {
                val r = read<EmailStartReq>(body)
                val email = r.email.trim().lowercase()
                if (!email.contains('@')) err(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Invalid email")
                val code = (100000 + Random.nextInt(900000)).toString()
                codes[email] = code
                return EmailStartRes(devCode = code) to EmailStartRes.serializer()
            }
            method == HttpMethod.Post && path == "auth/email/verify" -> {
                val r = read<EmailVerifyReq>(body)
                val email = r.email.trim().lowercase()
                if (codes[email] != r.code) err(HttpStatusCode.BadRequest, ErrorCode.INVALID_CODE, "Wrong code")
                codes.remove(email)
                val u = users.firstOrNull { it.email == email }
                    ?: DUser(id(), email, Role.USER, now(), code = email.substringBefore('@').take(5).uppercase() + "-DEMO").also { users += it }
                return session(u, r.device.name to r.device.platform) to SessionRes.serializer()
            }
            method == HttpMethod.Post && path == "auth/pair/redeem" -> {
                val r = read<PairRedeemReq>(body)
                val p = pairing?.takeIf { it.first == r.code } ?: err(HttpStatusCode.BadRequest, ErrorCode.INVALID_CODE, "Code is wrong or has expired")
                pairing = null
                return session(users.first { it.id == p.second }, r.device.name to r.device.platform) to SessionRes.serializer()
            }
            method == HttpMethod.Post && path == "auth/logout" -> { auth(req); return ok }
        }

        val (u, me) = auth(req)
        me.lastSeen = now()
        when {
            method == HttpMethod.Get && path == "me" -> return me(u) to MeRes.serializer()
            method == HttpMethod.Get && path == "me/payments" -> return PaymentsRes(u.payments.toList()) to PaymentsRes.serializer()
            method == HttpMethod.Delete && path == "me" -> {
                users.remove(u); devices.removeAll { it.userId == u.id }; tokens.entries.removeAll { it.value.first == u.id }
                return ok
            }
            method == HttpMethod.Get && path == "servers" ->
                return ServersRes(listOf(ServerLocation("demo-1", "Demo node", "Frankfurt", "DE", 0.04, listOf(Protocols.AMNEZIAWG, Protocols.WIREGUARD, "jaga-custom")))) to ServersRes.serializer()
            method == HttpMethod.Post && path == "tunnel" -> {
                val r = read<TunnelProvisionReq>(body)
                val ent = entitlement(u)
                ent.monthlyDataLimitBytes?.let { if (usage(u) >= it) err(HttpStatusCode.Forbidden, ErrorCode.DATA_LIMIT, "Monthly data used up") }
                if (devices.count { it.userId == u.id && it.address != null && it.id != me.id } >= ent.deviceLimit) {
                    err(HttpStatusCode.Forbidden, ErrorCode.DEVICE_LIMIT, "Your plan allows {n} device|Your plan allows {n} devices", ent.deviceLimit)
                }
                me.protocol = r.protocol
                me.address = me.address ?: "10.8.0.${nextIp++}"
                val key = "ZGVtby1zZXJ2ZXIta2V5LW5vdC1hLXJlYWwta2V5LTA="
                val params = when (r.protocol) {
                    Protocols.AMNEZIAWG -> Protocols.encode(AmneziaWG.ServerParams(key, "vpn1.demo.jaganet.dev:51821", listOf("0.0.0.0/0", "::/0"), 25,
                        mapOf("Jc" to "6", "Jmin" to "50", "Jmax" to "900", "S1" to "86", "S2" to "31", "H1" to "305419896-305420896")))
                    Protocols.WIREGUARD -> Protocols.encode(WireGuard.ServerParams(key, "vpn1.demo.jaganet.dev:51820", listOf("0.0.0.0/0", "::/0"), 25))
                    else -> JsonObject(mapOf("endpoint" to JsonPrimitive("vpn1.demo.jaganet.dev:443"), "token" to JsonPrimitive("demo")))
                }
                return TunnelConfig(r.protocol, "demo-1", "Frankfurt, DE", "${me.address}/32", listOf("1.1.1.1"), 1280, params) to TunnelConfig.serializer()
            }
            method == HttpMethod.Post && path == "tunnel/events" -> {
                val ev = read<ConnectionEventReq>(body)
                if (ev.type == ConnectionEventType.CONNECTED) {
                    me.connectedSince = now()
                } else {
                    val start = me.connectedSince ?: now()
                    val secs = (now() - start).inWholeSeconds.coerceAtLeast(1)
                    val bytes = secs * 3_500_000
                    samples += Sample(me.id, hour(now()), bytes, bytes / 5)
                    sessions += Session(me.id, start, now(), ev.peakDownBps)
                    me.connectedSince = null
                }
                return ok
            }
            method == HttpMethod.Get && path == "devices" -> {
                val list = devices.filter { it.userId == u.id }.map { d ->
                    val seen = if (d.connectedSince != null || d.id == me.id) now() else d.lastSeen
                    Device(d.id, d.name, d.platform, d.protocol, d.address, seen?.toString(),
                        online = seen != null && now() - seen < 3.minutes, isCurrent = d.id == me.id, createdAt = d.createdAt.toString())
                }
                return DevicesRes(list, entitlement(u).deviceLimit) to DevicesRes.serializer()
            }
            method == HttpMethod.Patch && path.startsWith("devices/") -> {
                val d = devices.firstOrNull { it.id == path.removePrefix("devices/") && it.userId == u.id } ?: err(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Device not found")
                d.name = read<RenameDeviceReq>(body).name.trim().take(60)
                return ok
            }
            method == HttpMethod.Delete && path.startsWith("devices/") -> {
                val target = path.removePrefix("devices/")
                devices.removeAll { it.id == target && it.userId == u.id }
                tokens.entries.removeAll { it.value.second == target }
                return ok
            }
            method == HttpMethod.Post && path == "devices/pairing-code" -> {
                val code = (100000 + Random.nextInt(900000)).toString()
                pairing = code to u.id
                return PairingCodeRes(code, (now() + 10.minutes).toString()) to PairingCodeRes.serializer()
            }
            method == HttpMethod.Get && path == "stats" -> {
                val p = req.url.parameters["period"]?.let { q -> StatsPeriod.entries.firstOrNull { it.name.equals(q, true) } } ?: StatsPeriod.WEEK
                return stats(u.id, p) to StatsRes.serializer()
            }
            method == HttpMethod.Get && path == "billing/plans" -> return PlansRes(tariffs(langOf(req)), FreePlan(10_000_000_000, 1), paymentsEnabled = true) to PlansRes.serializer()
            // Demo: the order is paid at once, no checkout.
            method == HttpMethod.Post && path == "orders" -> {
                val x = tariffs(langOf(req)).firstOrNull { it.id == read<CreateOrderReq>(body).tariffId }
                    ?: err(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "This plan is no longer on sale")
                val start = maxOf(now(), u.expiresAt ?: now())
                u.plan = PlanId.PRO; u.tariffName = x.name; u.source = BillingSource.APP
                u.expiresAt = start + (if (x.durationUnit == DurationUnit.DAYS) x.durationValue else x.durationValue * 30).days
                u.payments.add(0, Payment(id(), x.id, BillingSource.APP, start.toString(), u.expiresAt.toString(), PaymentStatus.ACTIVE, x.name))
                return OrderRes(id(), x.name, OrderStatus.PAID, Format.money(x.priceMinor, x.currency, langOf(req)), null) to OrderRes.serializer()
            }
            method == HttpMethod.Get && path == "referrals" ->
                return ReferralRes(u.code, u.invited, u.subscribed, 30, u.daysEarned, "https://jaganet.dev/r/${u.code}") to ReferralRes.serializer()
            method == HttpMethod.Get && path == "referrals/stats" -> {
                val p = periodOf(req.url.parameters["period"])
                return referralStats(u, p, langOf(req)) to ReferralStatsRes.serializer()
            }
            method == HttpMethod.Post && path == "referrals/links" -> {
                val r = read<CreateReferralLinkReq>(body)
                val name = r.name.trim()
                if (name.isEmpty() || name.length > 40) err(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Name must be 1 to 40 characters")
                val custom = r.code?.trim()?.uppercase()?.ifEmpty { null }
                if (custom != null && !Regex("^[A-Z0-9-]{3,32}$").matches(custom)) err(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Code must be 3 to 32 letters, digits or hyphens")
                if (linksOf(u).size >= 50) err(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "You can have up to 50 links")
                val code = custom ?: generateSequence { (1..7).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[Random.nextInt(32)] }.joinToString("") }.first { c -> links.none { it.code == c } }
                if (links.any { it.code == code } || users.any { it.code == code }) err(HttpStatusCode.Conflict, ErrorCode.BAD_REQUEST, "This code is taken")
                val l = DLink(id(), u.id, name, code, false, now())
                links += l
                return ReferralLink(l.id, l.name, l.code, false, "https://jaganet.dev/r/${l.code}", "https://t.me/JagaNetBot?start=${l.code}", l.createdAt.toString(), Funnel()) to ReferralLink.serializer()
            }
            method == HttpMethod.Patch && path.startsWith("referrals/links/") -> {
                val l = links.firstOrNull { it.id == path.removePrefix("referrals/links/") && it.userId == u.id && !it.main && !it.archived }
                    ?: err(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Link not found")
                val name = read<RenameReferralLinkReq>(body).name.trim()
                if (name.isEmpty() || name.length > 40) err(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Name must be 1 to 40 characters")
                l.name = name
                return ok
            }
            method == HttpMethod.Delete && path.startsWith("referrals/links/") -> {
                val l = links.firstOrNull { it.id == path.removePrefix("referrals/links/") && it.userId == u.id && !it.main && !it.archived }
                    ?: err(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Link not found")
                l.archived = true
                return ok
            }
            method == HttpMethod.Get && path == "admin/referrals" -> {
                if (u.role != Role.OWNER) err(HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN, "Not allowed")
                val p = periodOf(req.url.parameters["period"])
                return adminReferrals(p, langOf(req)) to AdminReferralsRes.serializer()
            }
            method == HttpMethod.Get && path == "admin/overview" -> {
                if (u.role != Role.OWNER) err(HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN, "Not allowed")
                return admin(langOf(req)) to AdminOverviewRes.serializer()
            }
        }
        err(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Not found")
    }

    private fun admin(lang: Lang): AdminOverviewRes {
        val (cur, pm, py) = prices(lang)
        val paying = 12 + users.count { it.plan == PlanId.PRO && it.source == BillingSource.DEV && it.email != "alex@example.com" }
        val free = 60 + users.count { it.plan == PlanId.FREE }
        val today = now().epochSeconds / 86400
        val week = listOf(2, 1, 3, 1, 0, 2, 3)
        return AdminOverviewRes(
            Revenue(
                mrrMinor = 9 * py / 12 + 4 * pm, currency = cur, paying = paying, free = free,
                conversion = paying.toDouble() / (paying + free), newSubsThisWeek = week.sum(), cancelledThisMonth = 3,
                yearly = 9, monthly = 4, fromReferrals = 2,
                newPayingLast7Days = week.mapIndexed { i, c -> DayCount(Instant.fromEpochSeconds((today - 6 + i) * 86400).toString().take(10), c) },
            ),
            ServerHealth(
                id = "demo-1", name = "Demo node", connectedNow = 2 + devices.count { it.connectedSince != null }, maxPeers = 50,
                cpu = 0.18 + rnd.nextDouble() * 0.1, memUsedBytes = 812L * 1_048_576, memTotalBytes = 1024L * 1_048_576,
                trafficThisMonthBytes = samples.sumOf { it.rx + it.tx }, trafficLimitBytes = 1_000_000_000_000,
                uptimeSeconds = 14 * 86400L + 6 * 3600, paidThrough = (now() + 40.days).toString().take(10),
                warnings = listOf("Memory is near its limit. Pause new sign-ups or add a second server before you grow further."),
            ),
        )
    }
}
