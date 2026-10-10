package dev.jaganet.server.http

import dev.jaganet.api.AdminNodesRes
import dev.jaganet.api.NodeReq
import dev.jaganet.api.OkRes
import dev.jaganet.api.Role
import dev.jaganet.server.forbidden
import dev.jaganet.server.notFound
import dev.jaganet.server.services.NodeRegisterReq
import dev.jaganet.server.services.NodeReportReq
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

private fun ApplicationCall.nodeToken() = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()

/** /v1: the node agents' API (token per node) and the owner's node list. */
fun Route.nodeApi(s: Services) {
    post("/node/register") { call.respond(s.nodes.register(call.nodeToken(), call.receive<NodeRegisterReq>())) }
    get("/node/peers") { call.respond(s.nodes.peers(call.nodeToken(), call.request.queryParameters["version"])) }
    post("/node/report") {
        s.nodes.report(call.nodeToken(), call.receive<NodeReportReq>())
        call.respond(OkRes())
    }

    suspend fun ApplicationCall.owner() = principal(s).also { if (it.user.role != Role.OWNER) throw forbidden() }
    get("/admin/nodes") { call.owner(); call.respond(AdminNodesRes(s.nodes.list())) }
    post("/admin/nodes") { call.owner(); call.respond(s.nodes.create(call.receive<NodeReq>())) }
    put("/admin/nodes/{id}") { call.owner(); call.respond(s.nodes.update(call.parameters["id"]!!, call.receive<NodeReq>())) }
    post("/admin/nodes/{id}/token") { call.owner(); call.respond(s.nodes.newToken(call.parameters["id"]!!)) }
}

/** The node install script and agent, served by the main server so a node needs nothing else. */
fun Route.nodeFiles() {
    for (file in listOf("install.sh", "agent.py")) get("/node/$file") {
        val text = javaClass.classLoader.getResource("node/$file")?.readText() ?: throw notFound()
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(text, ContentType.Text.Plain)
    }
}
