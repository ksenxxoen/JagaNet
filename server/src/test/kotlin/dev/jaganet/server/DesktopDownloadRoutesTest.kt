package dev.jaganet.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Issue #103: the panel serves the installer and the bridge and passes the installer fields on. */
class DesktopDownloadRoutesTest {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private val boop = "old-boop".toByteArray()
    private val bridge = "the-bridge".toByteArray()
    private val setup = "the-setup".toByteArray()

    private fun dir(vararg files: Pair<String, ByteArray>): File =
        kotlin.io.path.createTempDirectory("boop-dl").toFile().also { d -> files.forEach { (n, b) -> File(d, n).writeBytes(b) } }

    @Test fun `setup and bridge are served byte for byte, a missing file is 404`() {
        val d = dir("Boop-Setup.exe" to setup, "Boop-bridge.exe" to bridge, "Boop.exe" to boop)
        File(d, "desktop.json").writeText("""{"version":"2.0.0","installer_version":"2.0.0","installer_url":"/download/windows/setup"}""")
        harness(mapOf("DOWNLOADS_DIR" to d.path, "PUBLIC_URL" to "https://boopvpn.app")) {
            val c = b.createClient {}
            val s = c.get("/download/windows/setup")
            assertEquals(HttpStatusCode.OK, s.status)
            assertContentEquals(setup, s.readRawBytes())
            assertTrue("Boop-Setup-2.0.0.exe" in s.headers[HttpHeaders.ContentDisposition].orEmpty())
            assertContentEquals(bridge, c.get("/download/windows/bridge").readRawBytes())
            assertContentEquals(boop, c.get("/download/windows").readRawBytes())
            File(d, "Boop-bridge.exe").delete()
            File(d, "Boop-Setup.exe").delete()
            assertEquals(HttpStatusCode.NotFound, c.get("/download/windows/bridge").status)
            assertEquals(HttpStatusCode.NotFound, c.get("/download/windows/setup").status)
        }
    }

    @Test fun `no path from the request reaches the disk`() {
        val d = dir("Boop.exe" to boop)
        File(d, "secret.txt").writeText("secret")
        harness(mapOf("DOWNLOADS_DIR" to d.path)) {
            val c = b.createClient {}
            for (p in listOf("/download/windows/..%2Fsecret.txt", "/download/windows/secret.txt", "/download/windows/setup/../../secret.txt", "/download/windows/%2e%2e/secret.txt")) {
                val r = c.get(p)
                assertFalse(r.bodyAsText().trim() == "secret", p)
            }
        }
    }

    @Test fun `the old fields can point at the bridge and back again by files alone`() {
        val d = dir("Boop.exe" to boop, "Boop-bridge.exe" to bridge)
        File(d, "desktop.json").writeText("""{"version":"1.4.4","sha256":"${sha(boop)}"}""")
        harness(mapOf("DOWNLOADS_DIR" to d.path, "PUBLIC_URL" to "https://boopvpn.app")) {
            val c = b.createClient {}
            assertTrue(sha(boop) in c.get("/v1/app/desktop").bodyAsText())
            File(d, "desktop.json").writeText("""{"version":"2.0.0","legacy_file":"Boop-bridge.exe"}""")
            val text = c.get("/v1/app/desktop").bodyAsText()
            assertTrue(sha(bridge) in text)
            assertContentEquals(bridge, c.get("/download/windows").readRawBytes())
            File(d, "desktop.json").writeText("""{"version":"1.4.4","sha256":"${sha(boop)}"}""")
            assertContentEquals(boop, c.get("/download/windows").readRawBytes())
        }
    }
}
