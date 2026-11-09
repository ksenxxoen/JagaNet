package dev.jaganet.server.http

import dev.jaganet.api.i18n.I18n
import dev.jaganet.server.notFound
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.security.MessageDigest

/**
 * The Telegram mini app (issue #87): one page, /tg/app, made of web/tg/app.html, app.js and app.css.
 * The page signs in with Telegram.WebApp.initData (POST /v1/auth/telegram/webapp), keeps the session
 * token in memory only and uses the same /v1 API as the website.
 *
 * The files are read once. Their links carry ?v=<hash of their content>, so a new release is loaded
 * at once while the files themselves are cached for long. Scripts: our own and Telegram's
 * telegram-web-app.js, nothing else (Content-Security-Policy). Off (404) unless TELEGRAM_MINIAPP=1.
 *
 * The new mini app (web/, React; epic #117, W-9) is a preview on /tg/app-next and /tg/open-next:
 * its pages (web-next/miniapp.html, tg-open.html) are templates with the same {{lang}}, {{bot}} and
 * {{t:…}}, served with the same switch, headers and CSP; their files live in /ui/assets (WebNext.kt).
 */
private object MiniAppFiles {
    private fun res(name: String): ByteArray = MiniAppFiles::class.java.getResource("/web/tg/$name")!!.readBytes()
    val html: String by lazy { String(res("app.html")) }
    val openHtml: String by lazy { String(res("open.html")) }
    val js: ByteArray by lazy { res("app.js") }
    val openJs: ByteArray by lazy { res("open.js") }
    val css: ByteArray by lazy { res("app.css") }
    val version: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(js + openJs + css).take(6).joinToString("") { "%02x".format(it) }
    }
    /** The new mini app's templates, null when the server is built without web/. Read once. */
    val nextHtml: String? by lazy { WebNext.read("miniapp.html")?.let { String(it) } }
    val nextOpenHtml: String? by lazy { WebNext.read("tg-open.html")?.let { String(it) } }
    private val TEXT = Regex("""\{\{t:([^}]+)}}""")

    /** The page with its version and bot filled in, and its {{t:English}} texts (the "open via bot" screen) in [lang]. */
    fun page(lang: dev.jaganet.api.i18n.Lang, bot: String?, template: String = html): String = template
        .replace("{{v}}", version)
        .replace("{{lang}}", lang.code)
        .replace("{{bot}}", esc(bot.orEmpty()))
        .replace(TEXT) { esc(I18n.tr(lang, it.groupValues[1])) }
}

/** Telegram's script, then ours only; no inline code; data from this site only; framed only by Telegram Web. */
const val MINIAPP_CSP = "default-src 'self'; script-src 'self' https://telegram.org; style-src 'self'; img-src 'self'; " +
    "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; " +
    "frame-ancestors https://web.telegram.org https://*.web.telegram.org"

private fun ApplicationCall.miniAppHeaders() {
    response.header("Content-Security-Policy", MINIAPP_CSP)
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "no-referrer")
    response.header("X-Robots-Tag", "noindex")
}

fun Route.miniAppPage(s: Services) {
    fun on() = s.ctx.cfg.telegram.miniApp
    get("/tg/app") {
        if (!on()) throw notFound("The Telegram mini app is off")
        call.miniAppHeaders()
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondText(MiniAppFiles.page(call.pageLang(), s.bot?.username), ContentType.Text.Html.withCharset(Charsets.UTF_8))
    }
    // The template itself is never served raw.
    get("/tg/app.html") { call.respondRedirect("/tg/app") }
    get("/tg/app.js") { call.asset(MiniAppFiles.js, ContentType.Text.JavaScript) }
    get("/tg/open.js") { call.asset(MiniAppFiles.openJs, ContentType.Text.JavaScript) }
    get("/tg/open.html") { call.respondRedirect("/tg/open") }
    // Hands an import link (happ://, v2raytun://) from the mini app to the app: Telegram opens only web links outside.
    get("/tg/open") {
        if (!on()) throw notFound("The Telegram mini app is off")
        call.miniAppHeaders()
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(MiniAppFiles.page(call.pageLang(), null, MiniAppFiles.openHtml), ContentType.Text.Html.withCharset(Charsets.UTF_8))
    }
    get("/tg/app.css") { call.asset(MiniAppFiles.css, ContentType.Text.CSS) }

    // The new mini app (preview): the same template filling, switch, headers and caching as above.
    get("/tg/app-next") {
        if (!on()) throw notFound("The Telegram mini app is off")
        val template = MiniAppFiles.nextHtml ?: throw notFound("The new mini app is not built into this server")
        call.miniAppHeaders()
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondText(MiniAppFiles.page(call.pageLang(), s.bot?.username, template), ContentType.Text.Html.withCharset(Charsets.UTF_8))
    }
    get("/tg/open-next") {
        if (!on()) throw notFound("The Telegram mini app is off")
        val template = MiniAppFiles.nextOpenHtml ?: throw notFound("The new mini app is not built into this server")
        call.miniAppHeaders()
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(MiniAppFiles.page(call.pageLang(), null, template), ContentType.Text.Html.withCharset(Charsets.UTF_8))
    }
}

private suspend fun ApplicationCall.asset(bytes: ByteArray, type: ContentType) {
    miniAppHeaders()
    val current = request.queryParameters["v"] == MiniAppFiles.version
    response.header(HttpHeaders.CacheControl, if (current) "public, max-age=31536000, immutable" else "no-cache")
    respondBytes(bytes, type.withCharset(Charsets.UTF_8))
}
