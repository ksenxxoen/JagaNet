package dev.jaganet.server.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
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
 *   - /ui/assets/… : the files of every page, with a content hash in their names, cached for a year.
 *   - /next/       : the website's preview (index.html, never cached, so a release shows up at once).
 *   - /tg/app-next, /tg/open-next: the mini app's preview, its pages are templates (MiniApp.kt).
 *
 * The old website on / and the old mini app on /tg/app are not touched.
 */
fun Route.webNextPreview() {
    get("/next") { call.respondRedirect("/next/") }
    get("/next/") { call.nextIndex() }
    get("/next/index.html") { call.nextIndex() }
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

private suspend fun ApplicationCall.nextIndex() {
    val html = WebNext.read("index.html") ?: return respondText("The new website is not built into this server", status = HttpStatusCode.NotFound)
    response.header(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
    response.header("X-Robots-Tag", "noindex")
    respondBytes(html, ContentType.Text.Html.withParameter("charset", "utf-8"))
}

internal object WebNext {
    fun read(path: String): ByteArray? = WebNext::class.java.classLoader.getResourceAsStream("web-next/$path")?.use { it.readBytes() }
}
