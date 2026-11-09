package dev.jaganet.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The new website's preview on /next/ (issues #118, #126): served from the jar, files in /ui/assets, the old site on / unchanged. */
class WebNextTest {
    @Test fun `next serves the new website and the root keeps the old one`() = harness {
        val c = b.createClient {}
        val r = c.get("/next/")
        assertEquals(HttpStatusCode.OK, r.status)
        val html = r.bodyAsText()
        assertTrue("""<meta name="robots" content="noindex"""" in html, html)
        assertTrue("""<div id="root">""" in html, html)
        assertTrue("no-cache" in r.headers["Cache-Control"]!!, r.headers["Cache-Control"])
        assertEquals("noindex", r.headers["X-Robots-Tag"])

        // Hashed assets under /ui/assets (the same for every page) with a long cache.
        val js = Regex("""/ui/assets/[\w.-]+\.js""").find(html)!!.value
        val a = c.get(js)
        assertEquals(HttpStatusCode.OK, a.status)
        assertTrue("immutable" in a.headers["Cache-Control"]!!)
        assertTrue("javascript" in a.headers["Content-Type"]!!, a.headers["Content-Type"])
        val css = Regex("""/ui/assets/[\w.-]+\.css""").find(html)!!.value
        assertTrue("text/css" in c.get(css).headers["Content-Type"]!!)

        // /next without the slash and /next/index.html lead to the same page.
        assertEquals(HttpStatusCode.OK, c.get("/next").status)
        assertEquals(html, c.get("/next/index.html").bodyAsText())

        // Nothing outside the build's assets, no folders, and the mini app's templates are never served raw.
        // An encoded slash is refused before routing (400).
        assertTrue(c.get("/next/..%2Fweb%2Findex.html").status.value in setOf(400, 404))
        for (bad in listOf("/next/assets", "/next/missing.js", "/ui/assets", "/ui/assets/missing.js",
            "/ui/index.html", "/ui/miniapp.html", "/ui/tg-open.html", "/next/miniapp.html", "/ui/assets/.x.js")) {
            assertEquals(HttpStatusCode.NotFound, c.get(bad).status, bad)
        }
        assertTrue(c.get("/ui/assets/..%2F..%2Fminiapp.html").status.value in setOf(400, 404))

        // The old website is still on /.
        val old = c.get("/").bodyAsText()
        assertTrue("""<script src="/app.js"></script>""" in old, old)
        assertFalse("/ui/assets/" in old)
        assertEquals(HttpStatusCode.OK, c.get("/app.js").status)
    }
}
