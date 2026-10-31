package dev.jaganet.server.http

import dev.jaganet.api.Role
import dev.jaganet.server.forbidden
import dev.jaganet.server.services.Actor
import dev.jaganet.server.services.CtlBlockReq
import dev.jaganet.server.services.CtlDaysReq
import dev.jaganet.server.services.CtlNoteReq
import dev.jaganet.server.services.CtlPlanReq
import dev.jaganet.server.services.CtlSettings
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/**
 * /v1/admin: control over users, payments, subscriptions and connections (issue #39). Owner only:
 * anyone else gets 403 (401 without a session). Node and Reality secrets never appear here.
 */
fun Route.controlApi(s: Services) {
    suspend fun ApplicationCall.actor(): Actor {
        val p = principal(s)
        if (p.user.role != Role.OWNER) throw forbidden()
        return Actor(p.user.id, p.user.email)
    }
    fun ApplicationCall.id() = parameters["id"]!!.trim()
    fun ApplicationCall.paymentFilter() = request.queryParameters.let { q -> s.control.filter(q["from"], q["to"], q["status"], q["q"]) }

    get("/admin/control/dashboard") { call.actor(); call.respond(s.control.dashboard()) }
    get("/admin/control/settings") { call.actor(); call.respond(s.control.settings()) }
    put("/admin/control/settings") { val a = call.actor(); call.respond(s.control.saveSettings(a, call.receive<CtlSettings>())) }
    get("/admin/control/audit") { call.actor(); call.respond(s.control.auditLog(call.request.queryParameters["user"])) }

    // ?q= e-mail, Telegram id or @username, user / device id, VLESS UUID, key link or its token, client IP, note.
    get("/admin/users") { call.actor(); call.respond(s.control.search(call.request.queryParameters["q"])) }
    get("/admin/users/{id}") { call.actor(); call.respond(s.control.card(call.id())) }
    post("/admin/users/{id}/extend") {
        val a = call.actor(); val b = call.receive<CtlDaysReq>()
        call.respond(s.control.extend(a, call.id(), b.days, b.comment))
    }
    post("/admin/users/{id}/grant") {
        val a = call.actor(); val b = call.receive<CtlDaysReq>()
        call.respond(s.control.grantFree(a, call.id(), b.days, b.tariffId, b.comment))
    }
    post("/admin/users/{id}/plan") {
        val a = call.actor(); val b = call.receive<CtlPlanReq>()
        call.respond(s.control.changePlan(a, call.id(), b.tariffId, b.comment))
    }
    post("/admin/users/{id}/block") { val a = call.actor(); call.respond(s.control.block(a, call.id(), call.receive<CtlBlockReq>().reason)) }
    post("/admin/users/{id}/unblock") { val a = call.actor(); call.respond(s.control.unblock(a, call.id())) }
    put("/admin/users/{id}/note") { val a = call.actor(); call.respond(s.control.note(a, call.id(), call.receive<CtlNoteReq>().note)) }
    post("/admin/users/{id}/devices/{device}/reissue") {
        val a = call.actor()
        call.respond(s.control.reissue(a, call.id(), call.parameters["device"]!!.trim()))
    }
    delete("/admin/users/{id}/devices/{device}") {
        val a = call.actor()
        call.respond(s.control.deleteDevice(a, call.id(), call.parameters["device"]!!.trim()))
    }

    // ?from=2026-10-01&to=2026-10-31&status=paid|pending|cancelled&q=e-mail or order id
    get("/admin/payments") { call.actor(); call.respond(s.control.payments(call.paymentFilter())) }
    get("/admin/payments.csv") {
        call.actor()
        val csv = s.control.paymentsCsv(call.paymentFilter())
        call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "payments.csv").toString())
        call.respondText("﻿" + csv, ContentType.Text.CSV.withParameter("charset", "utf-8"))
    }
}

