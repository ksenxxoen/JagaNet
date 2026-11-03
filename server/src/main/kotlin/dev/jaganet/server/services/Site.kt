package dev.jaganet.server.services

import dev.jaganet.server.Ctx
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Links the website and the Telegram bot hand out. */
class Site(private val ctx: Ctx) {
    /** The APK the server hosts itself at /download/android, if it has one. */
    fun apkFile(): File? = ctx.cfg.downloadsDir?.let { File(it, "jaganet.apk") }?.takeIf { it.isFile }

    fun androidAppUrl(): String? = ctx.cfg.androidAppUrl ?: apkFile()?.let { "${ctx.cfg.publicUrl.trimEnd('/')}/download/android" }
    fun iosAppUrl(): String? = ctx.cfg.iosAppUrl

    /**
     * The file /download/windows serves (what the 1.4.x fields url/sha256 describe): Boop.exe, or
     * Boop-bridge.exe when desktop.json says `"legacy_file": "Boop-bridge.exe"`. Absent means
     * no Windows build is published.
     */
    fun desktopExe(): File? = ctx.cfg.downloadsDir?.let { legacyDesktopFile(File(it)) }

    /** Boop-Setup.exe for /download/windows/setup, only after it has been placed in DOWNLOADS_DIR. */
    fun desktopSetup(): File? = ctx.cfg.downloadsDir?.let { File(it, SETUP_FILE) }?.takeIf { it.isFile }

    /** Boop-bridge.exe for /download/windows/bridge, only after it has been placed in DOWNLOADS_DIR. */
    fun desktopBridge(): File? = ctx.cfg.downloadsDir?.let { File(it, BRIDGE_FILE) }?.takeIf { it.isFile }

    /**
     * Opt-in desktop update. Both Boop.exe and desktop.json must be in DOWNLOADS_DIR;
     * until then GET /v1/app/desktop is 404 and installed clients keep working.
     */
    fun desktopManifest(): DesktopAppManifest? = loadDesktopManifest(ctx.cfg.downloadsDir?.let(::File), ctx.cfg.publicUrl)

    /**
     * GET /v1/app/desktop?platform=…&distro=…. No platform (every Windows client so far) or
     * "windows" is the Windows build, exactly as before; "linux" is the .deb for that Ubuntu
     * release (the Linux app only shows the version and opens the link). Anything else: none.
     */
    fun desktopManifest(platform: String?, distro: String?): DesktopAppManifest? = when (platform?.trim()?.lowercase()) {
        null, "", "windows" -> desktopManifest()
        "linux" -> loadLinuxManifest(ctx.cfg.downloadsDir?.let(::File), ctx.cfg.publicUrl, distro)
        else -> null
    }

    /** The .deb for one Ubuntu release, only after it has been placed in DOWNLOADS_DIR. */
    fun linuxDeb(distro: String): File? = ctx.cfg.downloadsDir?.let { linuxDebFile(File(it), distro) }

    /** Third-party apps that import our key links / QR codes. */
    val otherApps = listOf(
        "AmneziaVPN" to "https://amnezia.org/downloads",
    )
}

/**
 * desktop.json next to the Windows files. sha256 and signature may be omitted; the hash is then
 * computed. Keys the panel reads (others are ignored):
 *  - version, mandatory, notes, sha256, signature: the 1.4.x fields. They describe the file at
 *    /download/windows, which is Boop.exe unless `legacy_file` names Boop-bridge.exe.
 *  - installer_*, min_proto, kind: the installer fields for 1.5.4+ and the bridge; they are passed
 *    on only when `installer_url` is present.
 */
@Serializable
data class DesktopReleaseFile(
    val version: String,
    val mandatory: Boolean = false,
    val notes: String? = null,
    val sha256: String? = null,
    val signature: String? = null,
    @SerialName("legacy_file") val legacyFile: String? = null,
    @SerialName("installer_url") val installerUrl: String? = null,
    @SerialName("installer_sha256") val installerSha256: String? = null,
    @SerialName("installer_version") val installerVersion: String? = null,
    @SerialName("min_proto") val minProto: Int? = null,
    val kind: String? = null,
)

/**
 * GET /v1/app/desktop. signature is ed25519 over the raw exe, standard base64, and may be absent.
 * The installer fields are added only when desktop.json has them and they match the file.
 */
@Serializable
data class DesktopAppManifest(
    val version: String,
    val url: String,
    val sha256: String,
    val signature: String? = null,
    val mandatory: Boolean = false,
    val notes: String? = null,
    @SerialName("installer_url") val installerUrl: String? = null,
    @SerialName("installer_sha256") val installerSha256: String? = null,
    @SerialName("installer_version") val installerVersion: String? = null,
    @SerialName("min_proto") val minProto: Int? = null,
    val kind: String? = null,
)

private val releaseJson = Json { ignoreUnknownKeys = true }
private val sha256Hex = Regex("^[0-9a-fA-F]{64}$")

const val SETUP_FILE = "Boop-Setup.exe"
const val BRIDGE_FILE = "Boop-bridge.exe"
private const val EXE_FILE = "Boop.exe"

/** The only files the old fields (url/sha256) may point to. */
private val LEGACY_FILES = mapOf("boop.exe" to EXE_FILE, "boop-bridge.exe" to BRIDGE_FILE)

private fun readRelease(dir: File): DesktopReleaseFile? {
    val meta = File(dir, "desktop.json")
    if (!meta.isFile) return null
    return runCatching { releaseJson.decodeFromString<DesktopReleaseFile>(meta.readText()) }.getOrNull()
}

/** The file behind /download/windows: Boop.exe by default, or the whitelisted `legacy_file`. */
internal fun legacyDesktopFile(dir: File): File? {
    if (!dir.isDirectory) return null
    val name = readRelease(dir)?.legacyFile?.let { LEGACY_FILES[it.trim().lowercase()] } ?: EXE_FILE
    return File(dir, name).takeIf { it.isFile }
}

/**
 * Nothing is published unless desktop.json and the file for the old fields are present. Dropping
 * only the exe does not advertise an update. A sha256 that is not the file's hash means no
 * document at all; a wrong installer_sha256 only drops the installer fields.
 */
internal fun loadDesktopManifest(dir: File?, publicUrl: String): DesktopAppManifest? {
    if (dir == null || !dir.isDirectory) return null
    val parsed = readRelease(dir) ?: return null
    val version = parsed.version.trim()
    if (version.isEmpty()) return null
    val legacyName = if (parsed.legacyFile == null) EXE_FILE else LEGACY_FILES[parsed.legacyFile.trim().lowercase()] ?: return null
    val exe = File(dir, legacyName).takeIf { it.isFile } ?: return null
    val actual = cachedSha256(exe)
    val declared = parsed.sha256?.trim()?.takeIf { sha256Hex.matches(it) }?.lowercase()
    if (declared != null && declared != actual) return null
    val base = publicUrl.trimEnd('/')
    val m = DesktopAppManifest(
        version = version,
        url = "$base/download/windows",
        sha256 = actual,
        signature = parsed.signature?.trim()?.takeIf { it.isNotEmpty() },
        mandatory = parsed.mandatory,
        notes = parsed.notes?.trim()?.takeIf { it.isNotEmpty() },
    )
    val rawUrl = parsed.installerUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return m
    val installerUrl = if (rawUrl.startsWith("/")) base + rawUrl else rawUrl
    val declaredInstaller = parsed.installerSha256?.trim()?.takeIf { sha256Hex.matches(it) }?.lowercase()
    val installerSha = if (installerUrl.substringBefore('?') == "$base/download/windows/setup") {
        // Our own route: the hash is the served file's. Without the file there is nothing to offer.
        val setup = File(dir, SETUP_FILE).takeIf { it.isFile } ?: return m
        cachedSha256(setup).takeIf { declaredInstaller == null || declaredInstaller == it } ?: return m
    } else {
        declaredInstaller ?: return m // not served by us: we cannot compute it
    }
    return m.copy(
        installerUrl = installerUrl,
        installerSha256 = installerSha,
        installerVersion = parsed.installerVersion?.trim()?.takeIf { it.isNotEmpty() },
        minProto = parsed.minProto,
        kind = parsed.kind?.trim()?.takeIf { it.isNotEmpty() },
    )
}

private class HashEntry(val mtime: Long, val size: Long, val sha: String)
private val hashCache = ConcurrentHashMap<String, HashEntry>()

/** SHA-256 of a file, recomputed only when its mtime or size changes (clients poll every 6 h). */
private fun cachedSha256(f: File): String {
    val key = f.absolutePath
    val mtime = f.lastModified()
    val size = f.length()
    hashCache[key]?.let { if (it.mtime == mtime && it.size == size) return it.sha }
    val sha = sha256File(f)
    hashCache[key] = HashEntry(mtime, size, sha)
    return sha
}

private fun sha256File(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Ubuntu releases a Linux build is made for (build-linux.sh), as the app names them. */
val LINUX_DISTROS = setOf("ubuntu22.04", "ubuntu24.04")

/** boop-<distro>.deb in DOWNLOADS_DIR, e.g. boop-ubuntu24.04.deb. */
internal fun linuxDebFile(dir: File, distro: String): File? =
    distro.takeIf { it in LINUX_DISTROS }?.let { File(dir, "boop-$it.deb") }?.takeIf { it.isFile }

/**
 * desktop-linux.json (same fields as desktop.json) and the release's .deb must both be in
 * DOWNLOADS_DIR; until then the Linux app hears 404 and offers nothing. The hash is the .deb's.
 */
internal fun loadLinuxManifest(dir: File?, publicUrl: String, distro: String?): DesktopAppManifest? {
    if (dir == null || !dir.isDirectory || distro == null) return null
    val deb = linuxDebFile(dir, distro.trim().lowercase()) ?: return null
    val meta = File(dir, "desktop-linux.json")
    if (!meta.isFile) return null
    val parsed = runCatching { releaseJson.decodeFromString<DesktopReleaseFile>(meta.readText()) }.getOrNull() ?: return null
    val version = parsed.version.trim()
    if (version.isEmpty()) return null
    return DesktopAppManifest(
        version = version,
        url = publicUrl.trimEnd('/') + "/download/linux/" + distro.trim().lowercase(),
        sha256 = sha256File(deb),
        mandatory = parsed.mandatory,
        notes = parsed.notes?.trim()?.takeIf { it.isNotEmpty() },
    )
}

/** Store pages of the apps that import a subscription link (the /k/{token} page, issue #88). */
object AppStores {
    val androidApps = listOf(
        "Happ" to "https://play.google.com/store/apps/details?id=com.happproxy",
        "v2RayTun" to "https://play.google.com/store/apps/details?id=com.v2raytun.android",
        "Hiddify" to "https://play.google.com/store/apps/details?id=app.hiddify.com",
    )
    val iosApps = listOf(
        "Happ" to "https://apps.apple.com/us/app/happ-proxy-utility/id6504287215",
        "Happ Plus" to "https://apps.apple.com/ru/app/happ-proxy-utility-plus/id6746188973",
        "v2RayTun" to "https://apps.apple.com/us/app/v2raytun/id6476628951",
        "Hiddify" to "https://apps.apple.com/us/app/hiddify-proxy-vpn/id6596777532",
    )
}
