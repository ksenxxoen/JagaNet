package dev.jaganet.server

import dev.jaganet.api.Protocols
import dev.jaganet.api.TunnelConfig
import dev.jaganet.server.services.loadDesktopManifest
import dev.jaganet.server.services.loadLinuxManifest
import dev.jaganet.server.services.withEndpointIp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopReleaseTest {
    @Test fun `nothing is published until both files are in place`() {
        val dir = kotlin.io.path.createTempDirectory("boop-rel").toFile()
        assertNull(loadDesktopManifest(dir, "https://boopvpn.app"))
        File(dir, "Boop.exe").writeBytes(byteArrayOf(1, 2, 3, 4))
        assertNull(loadDesktopManifest(dir, "https://boopvpn.app"))
        File(dir, "desktop.json").writeText("""{"version":"1.1.0","mandatory":false,"notes":"nl1","sha256":"0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"}""")
        val m = loadDesktopManifest(dir, "https://boopvpn.app/")!!
        assertEquals("1.1.0", m.version)
        assertEquals("https://boopvpn.app/download/windows", m.url)
        assertEquals("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20", m.sha256)
        assertEquals("nl1", m.notes)
        assertEquals(false, m.mandatory)
    }

    @Test fun `a missing hash is computed and a bad document is ignored`() {
        val dir = kotlin.io.path.createTempDirectory("boop-rel").toFile()
        File(dir, "Boop.exe").writeBytes("boop".toByteArray())
        File(dir, "desktop.json").writeText("""{"version":"1.1.0","signature":"abc"}""")
        val m = loadDesktopManifest(dir, "https://boopvpn.app")!!
        assertEquals(64, m.sha256.length)
        assertEquals("abc", m.signature)
        File(dir, "desktop.json").writeText("not json")
        assertNull(loadDesktopManifest(dir, "https://boopvpn.app"))
    }
}

class LinuxReleaseTest {
    @Test fun `the Linux build is its own document and never the Windows one`() {
        val dir = kotlin.io.path.createTempDirectory("boop-rel").toFile()
        File(dir, "Boop.exe").writeBytes(byteArrayOf(1, 2, 3, 4))
        File(dir, "desktop.json").writeText("""{"version":"1.1.0"}""")
        assertNull(loadLinuxManifest(dir, "https://boopvpn.app", "ubuntu24.04"))
        File(dir, "desktop-linux.json").writeText("""{"version":"1.3.0","notes":"Linux","mandatory":true}""")
        assertNull(loadLinuxManifest(dir, "https://boopvpn.app", "ubuntu24.04"))
        File(dir, "boop-ubuntu24.04.deb").writeBytes("deb".toByteArray())
        val m = loadLinuxManifest(dir, "https://boopvpn.app/", "ubuntu24.04")!!
        assertEquals("1.3.0", m.version)
        assertEquals("https://boopvpn.app/download/linux/ubuntu24.04", m.url)
        assertEquals(64, m.sha256.length)
        assertEquals("Linux", m.notes)
        assertEquals(true, m.mandatory)
        // Another release without its .deb, an unknown one, none at all: nothing.
        assertNull(loadLinuxManifest(dir, "https://boopvpn.app", "ubuntu22.04"))
        assertNull(loadLinuxManifest(dir, "https://boopvpn.app", "../Boop.exe"))
        assertNull(loadLinuxManifest(dir, "https://boopvpn.app", null))
        // The Windows document is untouched.
        assertEquals("1.1.0", loadDesktopManifest(dir, "https://boopvpn.app")!!.version)
    }
}

class EndpointHintTest {
    @Test fun `endpoint ip is additive and replaces itself`() {
        val cfg = TunnelConfig(
            Protocols.AMNEZIAWG, "n1", "Amsterdam, NL", "10.8.0.2/32", listOf("1.1.1.1"), 1280,
            JsonObject(mapOf("endpoint" to JsonPrimitive("nl1.boopvpn.app:51820"), "obfuscation" to JsonObject(mapOf("Jc" to JsonPrimitive("5"))))),
        )
        assertEquals(cfg, cfg.withEndpointIp(null))
        assertEquals(cfg, cfg.withEndpointIp("not-an-ip"))
        val hinted = cfg.withEndpointIp("203.0.113.10")
        val ips = hinted.params["endpointIps"] as JsonArray
        assertEquals("203.0.113.10", (ips[0] as JsonPrimitive).content)
        assertEquals("nl1.boopvpn.app:51820", (hinted.params["endpoint"] as JsonPrimitive).content)
        assertTrue((hinted.params["obfuscation"] as JsonObject).containsKey("Jc"))
        val moved = hinted.withEndpointIp("45.95.175.205")
        assertEquals("45.95.175.205", ((moved.params["endpointIps"] as JsonArray)[0] as JsonPrimitive).content)
        assertTrue(Protocols.encode(cfg).toString() != Protocols.encode(moved).toString())
    }
}
