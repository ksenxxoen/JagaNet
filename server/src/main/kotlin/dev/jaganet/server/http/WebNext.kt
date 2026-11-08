package dev.jaganet.server.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The new website (web/, React + Vite) as a preview on /next/ (epic #117, docs/WEB-FRONTEND.md).
 * The Gradle build puts it into the jar as resources/web-next. index.html is never cached, so a
 * release shows up at once; the files in assets/ have a content hash in their names and are cached
 * for a year. The old website on / is not touched.
 */
fun Route.webNextPreview() {
    get("/next") { call.respondRedirect("/next/") }
    get("/next/") { call.nextIndex() }
    get("/next/{path...}") {
        val parts = call.parameters.getAll("path").orEmpty()
        // Only plain file names: no "..", no hidden files, no backslashes, no folders.
        if (parts.isEmpty() || parts.any { it.isEmpty() || it.startsWith(".") || '\\' in it } || '.' !in parts.last()) return@get call.respondText("Not found", status = HttpStatusCode.NotFound)
        val path = parts.joinToString("/")
        if (path == "index.html") return@get call.nextIndex()
        val bytes = WebNext.read(path) ?: return@get call.respondText("Not found", status = HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.CacheControl, if (path.startsWith("assets/")) "public, max-age=31536000, immutable" else "no-cache")
        call.respondBytes(bytes, ContentType.defaultForFilePath(path))
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.nextIndex() {
    val html = WebNext.read("index.html") ?: return respondText("The new website is not built into this server", status = HttpStatusCode.NotFound)
    response.header(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
    response.header("X-Robots-Tag", "noindex")
    respondBytes(html, ContentType.Text.Html.withCharsetUtf8())
}

private fun ContentType.withCharsetUtf8() = withParameter("charset", "utf-8")

internal object WebNext {
    fun read(path: String): ByteArray? = WebNext::class.java.classLoader.getResourceAsStream("web-next/$path")?.use { it.readBytes() }
}
