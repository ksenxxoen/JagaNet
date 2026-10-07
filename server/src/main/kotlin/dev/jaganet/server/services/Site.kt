package dev.jaganet.server.services

import dev.jaganet.server.Ctx
import java.io.File

/** Links the website and the Telegram bot hand out. */
class Site(private val ctx: Ctx) {
    /** The APK the server hosts itself at /download/android, if it has one. */
    fun apkFile(): File? = ctx.cfg.downloadsDir?.let { File(it, "jaganet.apk") }?.takeIf { it.isFile }

    fun androidAppUrl(): String? = ctx.cfg.androidAppUrl ?: apkFile()?.let { "${ctx.cfg.publicUrl.trimEnd('/')}/download/android" }
    fun iosAppUrl(): String? = ctx.cfg.iosAppUrl

    /** Third-party apps that import our key links / QR codes. */
    val otherApps = listOf(
        "AmneziaVPN" to "https://amnezia.org/downloads",
        "AmneziaWG (Android)" to "https://play.google.com/store/apps/details?id=org.amnezia.awg",
    )
}
