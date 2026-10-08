package dev.jaganet.server.http

import dev.jaganet.api.DevPurchaseReq
import dev.jaganet.api.EmailStartReq
import dev.jaganet.api.EmailStartRes
import dev.jaganet.api.EmailVerifyReq
import dev.jaganet.api.ErrorBody
import dev.jaganet.api.ErrorCode
import dev.jaganet.api.ErrorRes
import dev.jaganet.api.ConnectionEventReq
import dev.jaganet.api.DevicesRes
import dev.jaganet.api.OkRes
import dev.jaganet.api.PairRedeemReq
import dev.jaganet.api.PaymentsRes
import dev.jaganet.api.Protocols
import dev.jaganet.api.RenameDeviceReq
import dev.jaganet.api.Role
import dev.jaganet.api.ServersRes
import dev.jaganet.api.StatsPeriod
import dev.jaganet.api.TunnelProvisionReq
import dev.jaganet.server.AppError
import dev.jaganet.server.Ctx
import dev.jaganet.server.Mode
import dev.jaganet.server.badRequest
import dev.jaganet.server.forbidden
import dev.jaganet.server.services.Admin
import dev.jaganet.server.services.AuthService
import dev.jaganet.server.services.Billing
import dev.jaganet.server.services.Entitlements
import dev.jaganet.server.services.Keys
import dev.jaganet.server.services.Payments
import dev.jaganet.server.services.Site
import dev.jaganet.server.services.Principal
import dev.jaganet.server.services.Referrals
import dev.jaganet.server.services.Stats
import dev.jaganet.server.services.Traffic
import dev.jaganet.server.services.Tunnels
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlin.time.Duration.Companion.minutes

/** All services wired together. */
class Services(val ctx: Ctx) {
    val auth = AuthService(ctx)
    val ent = Entitlements(ctx)
    val tunnels = Tunnels(ctx, ent)
    val traffic = Traffic(ctx, ent, tunnels)
    val stats = Stats(ctx)
    val billing = Billing(ctx)
    val referrals = Referrals(ctx)
    val admin = Admin(ctx)
    val keys = Keys(ctx, tunnels)
    val payments = Payments(ctx, billing, keys)
    val site = Site(ctx)
    /** Set when TELEGRAM_BOT_TOKEN is configured and the bot started. */
    @Volatile var bot: dev.jaganet.server.telegram.TelegramBot? = null
}

private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
private val SIX = Regex("^\\d{6}$")
private val AUTH_LIMIT = RateLimitName("auth")

suspend fun ApplicationCall.principal(s: Services): Principal = s.auth.authenticate(request.headers[HttpHeaders.Authorization])

fun Application.jaganet(s: Services) {
    val ctx = s.ctx
    install(ContentNegotiation) { json(Protocols.json) }
    if (ctx.cfg.mode != Mode.PRODUCTION) install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
    }
    install(RateLimit) {
        register(AUTH_LIMIT) { rateLimiter(limit = if (ctx.cfg.mode == Mode.TEST) 1000 else 10, refillPeriod = 1.minutes) }
    }
    install(StatusPages) {
        suspend fun ApplicationCall.err(status: Int, code: ErrorCode, msg: String) =
            respond(HttpStatusCode.fromValue(status), ErrorRes(ErrorBody(code, msg)))
        exception<AppError> { call, e -> call.err(e.status, e.code, translate(call.apiLang(), e.message ?: "", e.args)) }
        exception<BadRequestException> { call, e -> call.err(400, ErrorCode.BAD_REQUEST, e.cause?.message ?: e.message ?: "Bad request") }
        exception<SerializationException> { call, e -> call.err(400, ErrorCode.BAD_REQUEST, e.message ?: "Bad request") }
        exception<IllegalArgumentException> { call, e -> call.err(400, ErrorCode.BAD_REQUEST, e.message ?: "Bad request") }
        status(HttpStatusCode.TooManyRequests) { call, _ -> call.err(429, ErrorCode.TOO_MANY_ATTEMPTS, translate(call.apiLang(), "Too many requests, wait a minute")) }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("unhandled", e)
            call.err(500, ErrorCode.INTERNAL, translate(call.apiLang(), "Something went wrong"))
        }
    }

    suspend fun ApplicationCall.owner(): Principal = principal(s).also { if (it.user.role != Role.OWNER) throw forbidden() }
    fun email(e: String) = e.trim().lowercase().also { if (!EMAIL.matches(it) || it.length > 254) throw badRequest("Invalid email") }
    fun code(c: String) = c.also { if (!SIX.matches(it)) throw badRequest("Code must be 6 digits") }

    routing {
        website(s)
        get("/health") {
            call.respond(buildJsonObject {
                put("ok", true)
                put("mode", ctx.cfg.mode.name.lowercase())
                putJsonArray("protocols") { ctx.drivers.ids().forEach { add(it) } }
            })
        }

        route("/v1") {
            rateLimit(AUTH_LIMIT) {
                post("/auth/email/start") {
                    val b = call.receive<EmailStartReq>()
                    val code = s.auth.startEmailLogin(email(b.email), b.referralCode?.trim()?.take(32))
                    call.respond(EmailStartRes(devCode = code.takeIf { ctx.cfg.exposeOtp }))
                }
                post("/auth/email/verify") {
                    val b = call.receive<EmailVerifyReq>()
                    call.respond(s.auth.verifyEmailLogin(email(b.email), code(b.code), b.device))
                }
                post("/auth/pair/redeem") {
                    val b = call.receive<PairRedeemReq>()
                    call.respond(s.auth.redeemPairingCode(code(b.code), b.device))
                }
            }
            post("/auth/logout") {
                s.auth.logout(call.principal(s))
                call.respond(OkRes())
            }

            get("/me") { call.respond(s.ent.me(call.principal(s))) }
            get("/me/payments") { call.respond(PaymentsRes(s.ent.payments(call.principal(s)))) }
            delete("/me") {
                val p = call.principal(s)
                ctx.db.tx { sql ->
                    sql.query("SELECT id FROM devices WHERE user_id=?::uuid", p.user.id).forEach { s.tunnels.revoke(sql, it.str("id")) }
                    sql.exec("DELETE FROM users WHERE id=?::uuid", p.user.id)
                }
                call.respond(OkRes())
            }

            get("/servers") {
                call.principal(s)
                call.respond(ServersRes(s.tunnels.servers()))
            }
            post("/tunnel") {
                val p = call.principal(s)
                val b = call.receive<TunnelProvisionReq>()
                if (!Protocols.ID.matches(b.protocol)) throw badRequest("Invalid protocol id")
                call.respond(s.tunnels.provision(p, b))
            }
            post("/tunnel/events") {
                val p = call.principal(s)
                s.tunnels.connectionEvent(p, call.receive<ConnectionEventReq>())
                call.respond(OkRes())
            }

            get("/devices") {
                val p = call.principal(s)
                val lang = call.apiLang()
                call.respond(DevicesRes(s.tunnels.devices(p).map { it.copy(name = keyName(it.name, lang)) }, s.ent.me(p).entitlement.deviceLimit))
            }
            patch("/devices/{id}") {
                val p = call.principal(s)
                s.tunnels.rename(p, call.parameters["id"]!!, call.receive<RenameDeviceReq>().name)
                call.respond(OkRes())
            }
            delete("/devices/{id}") {
                s.tunnels.remove(call.principal(s), call.parameters["id"]!!)
                call.respond(OkRes())
            }
            post("/devices/pairing-code") { call.respond(s.auth.createPairingCode(call.principal(s))) }

            get("/stats") {
                val p = call.principal(s)
                val period = call.request.queryParameters["period"]?.let { q -> StatsPeriod.entries.firstOrNull { it.name.equals(q, true) } ?: throw badRequest("Invalid period") }
                call.respond(s.stats.get(p, period ?: StatsPeriod.WEEK))
            }

            get("/billing/plans") { call.respond(s.billing.plans(call.apiLang())) }
            post("/billing/dev/purchase") {
                val p = call.principal(s)
                s.billing.devPurchase(p, call.receive<DevPurchaseReq>().productId)
                call.respond(s.ent.me(p))
            }
            post("/billing/apple/verify") { call.principal(s); s.billing.notImplementedStore() }
            post("/billing/google/verify") { call.principal(s); s.billing.notImplementedStore() }
            post("/billing/apple/notifications") { s.billing.notImplementedStore() }
            post("/billing/google/rtdn") { s.billing.notImplementedStore() }

            get("/referrals") { call.respond(s.referrals.get(call.principal(s))) }
            salesApi(s)
            get("/admin/overview") {
                call.owner()
                call.respond(s.admin.overview(call.apiLang()))
            }
        }
    }
}
