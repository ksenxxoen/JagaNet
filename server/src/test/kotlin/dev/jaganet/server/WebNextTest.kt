package dev.jaganet.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The new website's preview on /next/ (issue #118): served from the jar, the old site on / unchanged. */
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

        // Hashed assets with a long cache.
        val js = Regex("""/next/assets/[\w.-]+\.js""").find(html)!!.value
        val a = c.get(js)
        assertEquals(HttpStatusCode.OK, a.status)
        assertTrue("immutable" in a.headers["Cache-Control"]!!)
        assertTrue("javascript" in a.headers["Content-Type"]!!, a.headers["Content-Type"])
        val css = Regex("""/next/assets/[\w.-]+\.css""").find(html)!!.value
        assertTrue("text/css" in c.get(css).headers["Content-Type"]!!)

        // /next without the slash and /next/index.html lead to the same page.
        assertEquals(HttpStatusCode.OK, c.get("/next").status)
        assertEquals(html, c.get("/next/index.html").bodyAsText())

        // Nothing outside the build, no folders.
        assertEquals(HttpStatusCode.NotFound, c.get("/next/assets").status)
        assertEquals(HttpStatusCode.NotFound, c.get("/next/missing.js").status)
        assertEquals(HttpStatusCode.NotFound, c.get("/next/..%2Fweb%2Findex.html").status)

        // The old website is still on /.
        val old = c.get("/").bodyAsText()
        assertTrue("""<script src="/app.js"></script>""" in old, old)
        assertFalse("/next/assets/" in old)
        assertEquals(HttpStatusCode.OK, c.get("/app.js").status)
    }
}
