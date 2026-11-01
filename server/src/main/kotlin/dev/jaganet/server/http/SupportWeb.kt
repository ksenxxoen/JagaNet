package dev.jaganet.server.http

import dev.jaganet.api.OkRes
import dev.jaganet.server.services.SupportMessage
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
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

/** Legal documents (Russian, as published by the owner), served as pages of the site. */
private object Legal {
    val docs = mapOf(
        "privacy" to "Политика конфиденциальности",
        "terms" to "Пользовательское соглашение",
    )
    private val cache = HashMap<String, String>()

    @Synchronized fun page(slug: String): String = cache.getOrPut(slug) {
        val body = Legal::class.java.getResource("/legal/$slug.html")!!.readText()
        val title = docs.getValue(slug)
        """<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title | Boop VPN</title>
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&display=swap" rel="stylesheet">
<link rel="icon" href="/favicon.svg" type="image/svg+xml"><link rel="stylesheet" href="/style.css"></head>
<body><header class="top"><div class="top-in"><a class="logo" href="/"><img src="/favicon.svg" alt="">Boop</a>
<nav class="nav"><a class="btn secondary" href="/#/account">Личный кабинет</a></nav></div></header>
<main class="narrow"><article class="card legal">
$body
</article></main>
<footer><span>© Boop</span><nav><a href="/privacy">Политика конфиденциальности</a><a href="/terms">Пользовательское соглашение</a><a href="/#/support">Написать в поддержку</a><a href="/">На главную</a></nav></footer>
</body></html>"""
    }
}

fun Route.legalPages() {
    for (slug in Legal.docs.keys) get("/$slug") {
        call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
        call.respondText(Legal.page(slug), ContentType.Text.Html)
    }
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
