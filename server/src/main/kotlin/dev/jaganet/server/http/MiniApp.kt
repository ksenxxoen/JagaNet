package dev.jaganet.server.http

import dev.jaganet.api.i18n.I18n
import dev.jaganet.server.notFound
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The Telegram mini app (issue #87; on the new frontend since epic #117, W-9): /tg/app and /tg/open.
 * The page signs in with Telegram.WebApp.initData (POST /v1/auth/telegram/webapp), keeps the session
 * token in memory only and uses the same /v1 API as the website.
 *
 * Its pages are templates from the web/ build (resources/web-ui/miniapp.html and tg-open.html, see
 * WebUi.kt) with {{lang}}, {{bot}} and {{t:English}} texts, filled here and never cached; their files
 * live in /ui/assets with a content hash in their names, cached for a year, so a release is loaded at once.
 * Scripts: our own and Telegram's telegram-web-app.js, nothing else (Content-Security-Policy).
 * Off (404) unless TELEGRAM_MINIAPP=1. /tg/app-next and /tg/open-next (the preview of W-9 and W-10)
 * lead to /tg/app and /tg/open for saved links, keeping the fragment.
 */
private object MiniAppFiles {
    /** The templates, null when the server is built without web/. Read once. */
    val html: String? by lazy { WebUi.read("miniapp.html")?.let { String(it) } }
    val openHtml: String? by lazy { WebUi.read("tg-open.html")?.let { String(it) } }
    private val TEXT = Regex("""\{\{t:([^}]+)}}""")

    /** The page with its bot filled in, and its {{t:English}} texts (the "open via bot" screen) in [lang]. */
    fun page(template: String, lang: dev.jaganet.api.i18n.Lang, bot: String?): String = template
        .replace("{{lang}}", lang.code)
        .replace("{{bot}}", esc(bot.orEmpty()))
        .replace(TEXT) { esc(I18n.tr(lang, it.groupValues[1])) }
}

/** Telegram's script, then ours only; no inline code; data from this site only; framed only by Telegram Web. */
const val MINIAPP_CSP = "default-src 'self'; script-src 'self' https://telegram.org; style-src 'self'; img-src 'self'; " +
    "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; " +
    "frame-ancestors https://web.telegram.org https://*.web.telegram.org"

internal fun ApplicationCall.miniAppHeaders() {
    response.header("Content-Security-Policy", MINIAPP_CSP)
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "no-referrer")
    response.header("X-Robots-Tag", "noindex")
}

fun Route.miniAppPage(s: Services) {
    fun on() = s.ctx.cfg.telegram.miniApp

    suspend fun ApplicationCall.page(template: String?, bot: String?, cache: String) {
        if (!on()) throw notFound("The Telegram mini app is off")
        val html = template ?: throw notFound("The mini app is not built into this server")
        miniAppHeaders()
        response.header(HttpHeaders.CacheControl, cache)
        respondText(MiniAppFiles.page(html, pageLang(), bot), ContentType.Text.Html.withCharset(Charsets.UTF_8))
    }

    get("/tg/app") { call.page(MiniAppFiles.html, s.bot?.username, "no-cache") }
    // Hands an import link (happ://, v2raytun://) from the mini app to the app: Telegram opens only web links outside.
    get("/tg/open") { call.page(MiniAppFiles.openHtml, null, "no-store") }
    // The preview's addresses (saved links, the bot's old buttons) lead to the main ones, keeping the fragment.
    get("/tg/app-next") {
        if (!on()) throw notFound("The Telegram mini app is off")
        call.toMain("/tg/app", miniApp = true)
    }
    get("/tg/open-next") {
        if (!on()) throw notFound("The Telegram mini app is off")
        call.toMain("/tg/open", miniApp = true)
    }
    // The templates themselves are never served raw.
    get("/tg/app.html") { call.respondRedirect("/tg/app") }
    get("/tg/open.html") { call.respondRedirect("/tg/open") }
}
