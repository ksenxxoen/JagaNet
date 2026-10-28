package dev.jaganet.server.http

import dev.jaganet.api.DiagnosticsReq
import dev.jaganet.api.ErrorCode
import dev.jaganet.api.Protocols
import dev.jaganet.api.Role
import dev.jaganet.server.AppError
import dev.jaganet.server.forbidden
import dev.jaganet.server.services.Diagnostics
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

private fun tooLarge() = AppError(413, ErrorCode.BAD_REQUEST, "The report is too large")

/** /v1: problem reports from the Boop apps (issue #11) and the owner's view of them. */
fun Route.diagnosticsApi(s: Services) {
    suspend fun ApplicationCall.owner() = principal(s).also { if (it.user.role != Role.OWNER) throw forbidden() }

    // A signed-in device sends one report. The body is read up to the limit and no further.
    post("/diagnostics") {
        val p = call.principal(s)
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > Diagnostics.MAX_BYTES) throw tooLarge()
        val body = call.receiveChannel().readRemaining(Diagnostics.MAX_BYTES + 1L).readByteArray()
        if (body.size > Diagnostics.MAX_BYTES) throw tooLarge()
        val req = Protocols.json.decodeFromString(DiagnosticsReq.serializer(), body.decodeToString())
        call.respond(s.diagnostics.submit(p, req))
    }

    get("/admin/diagnostics") { call.owner(); call.respond(s.diagnostics.list(call.request.queryParameters["user"])) }
    get("/admin/diagnostics/{id}") { call.owner(); call.respond(s.diagnostics.get(call.parameters["id"]!!)) }
}
