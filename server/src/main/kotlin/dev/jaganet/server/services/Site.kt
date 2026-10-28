package dev.jaganet.server.services

import dev.jaganet.server.Ctx
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** Links the website and the Telegram bot hand out. */
class Site(private val ctx: Ctx) {
    /** The APK the server hosts itself at /download/android, if it has one. */
    fun apkFile(): File? = ctx.cfg.downloadsDir?.let { File(it, "jaganet.apk") }?.takeIf { it.isFile }

    fun androidAppUrl(): String? = ctx.cfg.androidAppUrl ?: apkFile()?.let { "${ctx.cfg.publicUrl.trimEnd('/')}/download/android" }
    fun iosAppUrl(): String? = ctx.cfg.iosAppUrl

    /** Boop.exe, only after it has been placed in DOWNLOADS_DIR. Absent means no Windows build is published. */
    fun desktopExe(): File? = ctx.cfg.downloadsDir?.let { File(it, "Boop.exe") }?.takeIf { it.isFile }

    /**
     * Opt-in desktop update. Both Boop.exe and desktop.json must be in DOWNLOADS_DIR;
     * until then GET /v1/app/desktop is 404 and installed clients keep working.
     */
    fun desktopManifest(): DesktopAppManifest? = loadDesktopManifest(ctx.cfg.downloadsDir?.let(::File), ctx.cfg.publicUrl)

    /** Third-party apps that import our key links / QR codes. */
    val otherApps = listOf(
        "AmneziaVPN" to "https://amnezia.org/downloads",
    )
}

/** desktop.json next to Boop.exe. sha256 and signature may be omitted; the hash is then computed. */
@Serializable
data class DesktopReleaseFile(
    val version: String,
    val mandatory: Boolean = false,
    val notes: String? = null,
    val sha256: String? = null,
    val signature: String? = null,
)

/** GET /v1/app/desktop. signature is ed25519 over the raw exe, standard base64, and may be absent. */
@Serializable
data class DesktopAppManifest(
    val version: String,
    val url: String,
    val sha256: String,
    val signature: String? = null,
    val mandatory: Boolean = false,
    val notes: String? = null,
)

private val releaseJson = Json { ignoreUnknownKeys = true }
private val sha256Hex = Regex("^[0-9a-fA-F]{64}$")

/**
 * Nothing is published unless both files are present. Dropping only the exe
 * does not advertise an update.
 */
internal fun loadDesktopManifest(dir: File?, publicUrl: String): DesktopAppManifest? {
    if (dir == null || !dir.isDirectory) return null
    val exe = File(dir, "Boop.exe")
    val meta = File(dir, "desktop.json")
    if (!exe.isFile || !meta.isFile) return null
    val parsed = runCatching { releaseJson.decodeFromString<DesktopReleaseFile>(meta.readText()) }.getOrNull() ?: return null
    val version = parsed.version.trim()
    if (version.isEmpty()) return null
    val sha = parsed.sha256?.trim()?.takeIf { sha256Hex.matches(it) }?.lowercase() ?: sha256File(exe)
    return DesktopAppManifest(
        version = version,
        url = publicUrl.trimEnd('/') + "/download/windows",
        sha256 = sha,
        signature = parsed.signature?.trim()?.takeIf { it.isNotEmpty() },
        mandatory = parsed.mandatory,
        notes = parsed.notes?.trim()?.takeIf { it.isNotEmpty() },
    )
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
