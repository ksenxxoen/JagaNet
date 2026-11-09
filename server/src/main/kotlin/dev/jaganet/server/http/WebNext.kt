package dev.jaganet.server.http

import dev.jaganet.server.WebUi
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The new web interface (web/, React + Vite; epic #117, docs/WEB-FRONTEND.md). The Gradle build
 * puts it into the jar as resources/web-next: three pages and their files in assets/.
 *
 * WEB_UI (Config.webUi, W-10) picks which interface the main addresses serve; both are in the jar:
 *
 *   - new (default): / and /index.html are the new website (index.html, never cached, so a release
 *     shows up at once); /tg/app and /tg/open the new mini app (MiniApp.kt). The preview addresses
 *     /next/, /tg/app-next and /tg/open-next lead to the main ones (to-main.js, keeping ?query and #fragment).
 *   - legacy: the old website on / (resources/web) and the old mini app; the previews work as before,
 *     so the new interface can still be looked at after a rollback.
 *
 * In both modes the old files (/app.js, /style.css, /tg/app.js…) stay served, so open tabs keep working,
 * and /ui/assets/… are the files of every new page, with a content hash in their names, cached for a year.
 */
fun Route.webPages(s: Services) {
    val newUi = WebNext.newUi(s)
    if (newUi) {
        get("/") { call.siteIndex(preview = false) }
        get("/index.html") { call.siteIndex(preview = false) }
        for (path in listOf("/next", "/next/", "/next/index.html")) get(path) { call.toMain("/", miniApp = false) }
    } else {
        get("/next") { call.respondRedirect("/next/") }
        get("/next/") { call.siteIndex(preview = true) }
        get("/next/index.html") { call.siteIndex(preview = true) }
    }
    get("/ui/${WebNext.TO_MAIN}") {
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.respondBytes(WebNext.toMainJs, ContentType.Text.JavaScript.withCharset(Charsets.UTF_8))
    }
    get("/ui/{path...}") {
        val parts = call.parameters.getAll("path").orEmpty()
        // Only plain file names in assets/: no "..", no hidden files, no backslashes, no pages (the templates stay private).
        val ok = parts.size == 2 && parts[0] == "assets" && parts.none { it.isEmpty() || it.startsWith(".") || '\\' in it } &&
            '.' in parts.last() && !parts.last().endsWith(".html")
        val path = parts.joinToString("/")
        val bytes = if (ok) WebNext.read(path) else null
        if (bytes == null) return@get call.respondText("Not found", status = HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.respondBytes(bytes, ContentType.defaultForFilePath(path))
    }
}

/** The new website's page: on / (WEB_UI=new) or as the preview on /next/ (WEB_UI=legacy, not indexed). */
private suspend fun ApplicationCall.siteIndex(preview: Boolean) {
    val html = WebNext.read("index.html") ?: return respondText("The new website is not built into this server", status = HttpStatusCode.NotFound)
    response.header(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
    if (preview) response.header("X-Robots-Tag", "noindex")
    respondBytes(html, ContentType.Text.Html.withParameter("charset", "utf-8"))
}

/**
 * A preview address once the new interface is the main one: a page whose script (to-main.js, our own
 * file, so the mini app's CSP allows it) replaces the address with [main], keeping ?query and #fragment.
 * Without scripts, a link to [main].
 */
internal suspend fun ApplicationCall.toMain(main: String, miniApp: Boolean) {
    if (miniApp) miniAppHeaders()
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header("X-Robots-Tag", "noindex")
    respondText(
        """<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>Boop</title><script src="/ui/${WebNext.TO_MAIN}"></script></head>
<body><p><a href="$main">Boop</a></p></body></html>""",
        ContentType.Text.Html.withCharset(Charsets.UTF_8),
    )
}

internal object WebNext {
    const val TO_MAIN = "to-main.js"

    fun read(path: String): ByteArray? = WebNext::class.java.classLoader.getResourceAsStream("web-next/$path")?.use { it.readBytes() }

    val toMainJs: ByteArray by lazy { WebNext::class.java.getResource("/web-switch/$TO_MAIN")!!.readBytes() }

    /** Whether the main addresses serve the new interface: WEB_UI=new and the build is in the jar (else the old one, with a warning). */
    fun newUi(s: Services): Boolean {
        if (s.ctx.cfg.webUi != WebUi.NEW) return false
        if (built) return true
        if (!warned) {
            warned = true
            org.slf4j.LoggerFactory.getLogger("web").warn("WEB_UI=new, but the new interface is not built into this server: serving the old one")
        }
        return false
    }

    private val built: Boolean by lazy { listOf("index.html", "miniapp.html", "tg-open.html").all { WebNext::class.java.classLoader.getResource("web-next/$it") != null } }

    @Volatile private var warned = false
}
