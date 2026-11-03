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
        File(dir, "desktop.json").writeText("""{"version":"1.1.0","mandatory":false,"notes":"nl1","sha256":"9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"}""")
        val m = loadDesktopManifest(dir, "https://boopvpn.app/")!!
        assertEquals("1.1.0", m.version)
        assertEquals("https://boopvpn.app/download/windows", m.url)
        assertEquals("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a", m.sha256)
        assertEquals("nl1", m.notes)
        assertEquals(false, m.mandatory)
    }

    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private val pub = "https://boopvpn.app"
    private fun wire(m: dev.jaganet.server.services.DesktopAppManifest?) = Protocols.json.encodeToString(dev.jaganet.server.services.DesktopAppManifest.serializer(), m!!)

    @Test fun `an old document gives the same JSON as before the installer fields`() {
        val dir = kotlin.io.path.createTempDirectory("boop-rel").toFile()
        val exe = "boop".toByteArray()
        File(dir, "Boop.exe").writeBytes(exe)
        File(dir, "desktop.json").writeText("""{"version":"1.4.4","mandatory":false,"notes":"n","sha256":"${sha(exe)}"}""")
        assertEquals(
            """{"version":"1.4.4","url":"https://boopvpn.app/download/windows","sha256":"${sha(exe)}","mandatory":false,"notes":"n"}""",
            wire(loadDesktopManifest(dir, pub)),
        )
    }

    @Test fun `a wrong sha256 withholds the whole document`() {
        val dir = kotlin.io.path.createTempDirectory("boop-rel").toFile()
        File(dir, "Boop.exe").writeBytes("boop".toByteArray())
        File(dir, "desktop.json").writeText("""{"version":"1.4.4","sha256":"${"0".repeat(64)}"}""")
        assertNull(loadDesktopManifest(dir, pub))
    }

    @Test fun `installer fields pass through and a relative url is made absolute`() {
        val dir = kotlin.io.path.createTempDirectory("boop-rel").toFile()
        val bridge = "bridge".toByteArray(); val setup = "setup".toByteArray()
        File(dir, "Boop-bridge.exe").writeBytes(bridge)
        File(dir, "Boop-Setup.exe").writeBytes(setup)
        File(dir, "desktop.json").writeText(
            """{"version":"2.0.0","legacy_file":"Boop-bridge.exe","installer_version":"2.0.0","installer_url":"/download/windows/setup","min_proto":3,"kind":"installer"}""",
        )
        val m = loadDesktopManifest(dir, "$pub/")!!
        assertEquals(sha(bridge), m.sha256)
        assertEquals("$pub/download/windows/setup", m.installerUrl)
        assertEquals(sha(setup), m.installerSha256)
        assertEquals("2.0.0", m.installerVersion)
        assertEquals(3, m.minProto)
        assertEquals("installer", m.kind)
        // Absolute url of our own route is the same thing.
        File(dir, "desktop.json").writeText("""{"version":"2.0.0","legacy_file":"Boop-bridge.exe","installer_url":"$pub/download/windows/setup","installer_sha256":"${sha(setup)}"}""")
        assertEquals(sha(setup), loadDesktopManifest(dir, pub)!!.installerSha256)
    }

    @Test fun `a wrong installer hash or a missing setup drops only the installer`() {
        val dir = kotlin.io.path.createTempDirectory("boop-rel").toFile()
        val exe = "boop".toByteArray()
        File(dir, "Boop.exe").writeBytes(exe)
        File(dir, "desktop.json").writeText("""{"version":"2.0.0","installer_url":"/download/windows/setup"}""")
        val noSetup = loadDesktopManifest(dir, pub)!!
        assertNull(noSetup.installerUrl)
        assertEquals(sha(exe), noSetup.sha256)
        File(dir, "Boop-Setup.exe").writeBytes("setup".toByteArray())
        File(dir, "desktop.json").writeText("""{"version":"2.0.0","installer_url":"/download/windows/setup","installer_sha256":"${"1".repeat(64)}"}""")
        val bad = loadDesktopManifest(dir, pub)!!
        assertNull(bad.installerUrl); assertNull(bad.installerSha256); assertNull(bad.kind)
        assertEquals(sha(exe), bad.sha256)
        // Not our route and no hash given: cannot be vouched for.
        File(dir, "desktop.json").writeText("""{"version":"2.0.0","installer_url":"https://cdn.example/Boop-Setup.exe"}""")
        assertNull(loadDesktopManifest(dir, pub)!!.installerUrl)
    }

    @Test fun `legacy_file is limited to the two known names`() {
        val dir = kotlin.io.path.createTempDirectory("boop-rel").toFile()
        File(dir, "Boop.exe").writeBytes("boop".toByteArray())
        File(dir, "desktop.json").writeText("""{"version":"2.0.0","legacy_file":"../etc/passwd"}""")
        assertNull(loadDesktopManifest(dir, pub))
        File(dir, "desktop.json").writeText("""{"version":"2.0.0","legacy_file":"Boop-bridge.exe"}""")
        assertNull(loadDesktopManifest(dir, pub)) // named, but not there
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
