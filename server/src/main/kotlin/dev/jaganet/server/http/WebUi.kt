package dev.jaganet.server.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The web interface (web/, React + Vite; epic #117, docs/WEB-FRONTEND.md). The Gradle build puts it
 * into the jar as resources/web-ui: three pages and their files in assets/.
 *
 *   - / and /index.html: the website (index.html, never cached, so a release shows up at once);
 *   - /tg/app and /tg/open: the Telegram mini app's templates, filled and served by MiniApp.kt;
 *   - /ui/assets/…: the files of every page, with a content hash in their names, cached for a year.
 *
 * The addresses of the preview before the switch (W-10) stay for saved links and the bot's old buttons:
 * /next/, /tg/app-next and /tg/open-next lead to the main ones (to-main.js, keeping ?query and #fragment).
 * The server's own pages (/k/…, payment, /privacy, /terms) use resources/web: favicon.svg and pages.css.
 */
fun Route.webPages() {
    get("/") { call.siteIndex() }
    get("/index.html") { call.siteIndex() }
    for (path in listOf("/next", "/next/", "/next/index.html")) get(path) { call.toMain("/", miniApp = false) }
    get("/ui/${WebUi.TO_MAIN}") {
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.respondBytes(WebUi.toMainJs, ContentType.Text.JavaScript.withCharset(Charsets.UTF_8))
    }
    get("/ui/{path...}") {
        val parts = call.parameters.getAll("path").orEmpty()
        // Only plain file names in assets/: no "..", no hidden files, no backslashes, no pages (the templates stay private).
        val ok = parts.size == 2 && parts[0] == "assets" && parts.none { it.isEmpty() || it.startsWith(".") || '\\' in it } &&
            '.' in parts.last() && !parts.last().endsWith(".html")
        val path = parts.joinToString("/")
        val bytes = if (ok) WebUi.read(path) else null
        if (bytes == null) return@get call.respondText("Not found", status = HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.respondBytes(bytes, ContentType.defaultForFilePath(path))
    }
}

/** The website's page on / (the hash routes #/account, #/admin… never reach the server). */
private suspend fun ApplicationCall.siteIndex() {
    val html = WebUi.read("index.html") ?: return respondText("The website is not built into this server", status = HttpStatusCode.NotFound)
    response.header(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
    respondBytes(html, ContentType.Text.Html.withParameter("charset", "utf-8"))
}

/**
 * A preview address of W-10: a page whose script (to-main.js, our own file, so the mini app's CSP
 * allows it) replaces the address with [main], keeping ?query and #fragment. Without scripts, a link to [main].
 */
internal suspend fun ApplicationCall.toMain(main: String, miniApp: Boolean) {
    if (miniApp) miniAppHeaders()
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header("X-Robots-Tag", "noindex")
    respondText(
        """<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>Boop</title><script src="/ui/${WebUi.TO_MAIN}"></script></head>
<body><p><a href="$main">Boop</a></p></body></html>""",
        ContentType.Text.Html.withCharset(Charsets.UTF_8),
    )
}

internal object WebUi {
    const val TO_MAIN = "to-main.js"

    fun read(path: String): ByteArray? = WebUi::class.java.classLoader.getResourceAsStream("web-ui/$path")?.use { it.readBytes() }

    val toMainJs: ByteArray by lazy { WebUi::class.java.getResource("/web-redirect/$TO_MAIN")!!.readBytes() }
}
