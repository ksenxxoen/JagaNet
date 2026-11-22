package dev.jaganet.server.http

import dev.jaganet.api.OkRes
import dev.jaganet.server.services.SupportMessage
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** The support form's request. [website] is a honeypot: people leave it empty, bots fill it in. */
@Serializable
data class SupportReq(
    val name: String? = null,
    val email: String,
    val subject: String,
    val message: String,
    val website: String? = null,
)

@Serializable
data class SupportInfoRes(val available: Boolean)

/** Published legal documents, served as PDF files under /docs. */
object LegalDocs {
    const val TERMS = "/docs/boopvpn-terms.pdf"
    const val PRIVACY = "/docs/boopvpn-privacy.pdf"
}

fun Route.legalPages() {
    // Keep the old /terms and /privacy addresses working for existing links (bot, mail, bookmarks).
    get("/terms") { call.respondRedirect(LegalDocs.TERMS) }
    get("/privacy") { call.respondRedirect(LegalDocs.PRIVACY) }
    get("/support") { call.respondRedirect("/#/support") }
}

private val SUPPORT_EMAIL_RE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

/** /v1/support: is the form working, and sending a message (signed in or not). */
fun Route.supportApi(s: Services) {
    get("/support") { call.respond(SupportInfoRes(s.support.available())) }
    post("/support") {
        val b = call.receive<SupportReq>()
        // A filled honeypot: answer as if it went through, send nothing.
        if (!b.website.isNullOrBlank()) return@post call.respond(OkRes())
        val email = b.email.trim().lowercase()
        if (!SUPPORT_EMAIL_RE.matches(email) || email.length > 254) throw dev.jaganet.server.badRequest("Invalid email")
        val p = call.optionalPrincipal(s)
        s.support.send(
            SupportMessage(
                name = b.name, email = email, subject = b.subject, message = b.message,
                userId = p?.user?.id, accountEmail = p?.user?.email, ip = call.clientIp(),
            ),
        )
        call.respond(OkRes())
    }
}

private suspend fun ApplicationCall.optionalPrincipal(s: Services) =
    request.headers[HttpHeaders.Authorization]?.let { h -> runCatching { s.auth.authenticate(h) }.getOrNull() }
