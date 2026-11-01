package dev.jaganet.server.http

import dev.jaganet.api.OkRes
import dev.jaganet.api.Role
import dev.jaganet.server.forbidden
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** Owner-only server monitoring. */
fun Route.monitorApi(s: Services) {
    get("/admin/monitor") {
        if (call.principal(s).user.role != Role.OWNER) throw forbidden()
        call.respond(s.monitor.overview(call.request.queryParameters["range"] ?: "24h", call.apiLang()))
    }
    post("/admin/monitor/test") {
        if (call.principal(s).user.role != Role.OWNER) throw forbidden()
        s.monitor.testAlert()
        call.respond(OkRes())
    }
}
