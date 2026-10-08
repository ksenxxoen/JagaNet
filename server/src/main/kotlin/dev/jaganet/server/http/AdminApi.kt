package dev.jaganet.server.http

import dev.jaganet.api.AlertSettings
import dev.jaganet.api.ErrorCode
import dev.jaganet.api.LinkLoginReq
import dev.jaganet.api.ModeSettings
import dev.jaganet.api.NetworkSettings
import dev.jaganet.api.OkRes
import dev.jaganet.api.PlanSettings
import dev.jaganet.api.ReferralPeriod
import dev.jaganet.api.Protocols
import dev.jaganet.api.Role
import dev.jaganet.api.SmtpSettings
import dev.jaganet.api.TestEmailReq
import dev.jaganet.api.i18n.I18n
import dev.jaganet.server.AppError
import dev.jaganet.server.badRequest
import dev.jaganet.server.forbidden
import dev.jaganet.server.services.EmailSender
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/** /v1: one-time sign-in links and the owner's admin panel (settings, money). */
fun Route.adminApi(s: Services) {
    post("/auth/link/redeem") {
        val b = call.receive<LinkLoginReq>()
        call.respond(s.auth.redeemLoginLink(b.token.trim(), b.device))
    }

    suspend fun ApplicationCall.owner() = principal(s).also { if (it.user.role != Role.OWNER) throw forbidden() }
    fun settings() = s.settings.get(s.ctx.cfg.telegramBotToken != null)

    get("/admin/settings") { call.owner(); call.respond(settings()) }
    put("/admin/settings/plans") { call.owner(); s.settings.savePlans(call.receive<PlanSettings>()); call.respond(settings()) }
    put("/admin/settings/smtp") { call.owner(); s.settings.saveSmtp(call.receive<SmtpSettings>()); call.respond(settings()) }
    put("/admin/settings/alerts") { call.owner(); s.settings.saveAlerts(call.receive<AlertSettings>()); call.respond(settings()) }
    put("/admin/settings/modes") { call.owner(); s.settings.saveModes(call.receive<ModeSettings>()); call.respond(settings()) }
    put("/admin/settings/network") { call.owner(); s.settings.saveNetwork(call.receive<NetworkSettings>()); call.respond(settings()) }
    post("/admin/settings/smtp/test") {
        call.owner()
        val to = call.receive<TestEmailReq>().to.trim()
        if ('@' !in to) throw badRequest("Invalid email")
        val smtp = s.ctx.live.smtp ?: throw AppError(503, ErrorCode.SERVICE_UNAVAILABLE, "E-mail is not set up")
        val lang = call.apiLang()
        try {
            EmailSender(smtp).send(listOf(to), "[JagaNet] " + I18n.tr(lang, "Test e-mail"), I18n.tr(lang, "E-mail from your JagaNet server works."))
        } catch (e: Exception) {
            throw AppError(502, ErrorCode.SERVICE_UNAVAILABLE, "The mail server refused, {reason}", mapOf("reason" to (e.message ?: e.javaClass.simpleName).take(200)))
        }
        call.respond(OkRes())
    }
    get("/admin/finance") {
        call.owner()
        val q = call.request.queryParameters["period"]
        val period = ReferralPeriod.entries.firstOrNull { Protocols.json.encodeToString(ReferralPeriod.serializer(), it).trim('"') == q } ?: ReferralPeriod.D30
        call.respond(s.finance.get(period))
    }
}

/**
 * The owner's sign-in link, asked for from the server itself (the jaganet-admin command):
 * only from 127.0.0.1 and not through the web proxy (which adds X-Forwarded-For).
 */
fun Route.ownerLoginLink(s: Services) {
    get("/internal/owner-login") {
        val local = call.request.origin.remoteHost in setOf("127.0.0.1", "0:0:0:0:0:0:0:1", "::1", "localhost") &&
            call.request.headers["X-Forwarded-For"] == null
        if (!local) throw forbidden()
        call.respondText(s.auth.ownerLoginLink() + "\n", ContentType.Text.Plain)
    }
}
