package dev.jaganet.app

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.jaganet.api.EmailStartReq
import dev.jaganet.api.EmailVerifyReq
import dev.jaganet.api.Platform
import dev.jaganet.app.platform.MemoryStore
import dev.jaganet.app.state.AppState
import dev.jaganet.app.state.Route
import dev.jaganet.app.tunnel.SimulatedEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Renders every screen headlessly against a running simulation server
 * (`./gradlew :server:sim`), at iPhone size and 2× density, into the given folder.
 * Usage: `./gradlew :composeApp:screenshots`
 */
fun main(args: Array<String>) {
    runBlocking { render(args) }
    kotlin.system.exitProcess(0)
}

private suspend fun render(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val out = File(args.firstOrNull() ?: "build/screenshots").apply { mkdirs() }
    val api = System.getenv("JAGANET_API_URL") ?: "http://localhost:4000"

    suspend fun session(email: String, platform: Platform = Platform.IOS): AppState {
        val state = withContext(Dispatchers.Main) { AppState(DesktopPlatform(api, MemoryStore(), SimulatedEngine(), platform)) }
        val code = state.api.startEmail(EmailStartReq(email)).devCode ?: error("server is not in simulation mode")
        state.signedIn(state.api.verifyEmail(EmailVerifyReq(email, code, state.thisDevice)))
        return state
    }

    suspend fun shot(state: AppState, route: Route, name: String, height: Int = 844, before: suspend () -> Unit = {}) {
        withContext(Dispatchers.Main) { state.router.reset(route) }
        before()
        val scene = withContext(Dispatchers.Main) { ImageComposeScene(390 * 2, height * 2, Density(2f)) { App(state) } }
        try {
            // Let data load: render frames until the network calls settle.
            repeat(30) { i ->
                withContext(Dispatchers.Main) { scene.render(i * 100_000_000L) }
                delay(100)
            }
            val img = withContext(Dispatchers.Main) { scene.render(3_100_000_000L) }
            File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            println("wrote $name.png")
        } finally {
            withContext(Dispatchers.Main) { scene.close() }
        }
    }

    val signedOut = withContext(Dispatchers.Main) { AppState(DesktopPlatform(api, MemoryStore(), SimulatedEngine(), Platform.IOS)) }
    shot(signedOut, Route.SignIn, "01-sign-in")
    shot(signedOut, Route.Verify("alex@example.com", "482917"), "02-verify")

    val sam = session("sam@example.com", Platform.ANDROID)
    shot(sam, Route.Home, "03-home-free")
    shot(sam, Route.Plans, "08-plans", 960)
    shot(sam, Route.SplitTunnel, "13-split-tunnel-android", 900) {
        sam.settings.update { it.copy(split = it.split.copy(mode = dev.jaganet.app.tunnel.SplitMode.ONLY, apps = listOf("sim.browser", "sim.mail"))) }
    }

    val alex = session("alex@example.com")
    alex.tunnel.connect().join()
    delay(3000)
    shot(alex, Route.Home, "04-home-connected")
    shot(alex, Route.Stats, "05-stats", 1360)
    shot(alex, Route.Devices, "06-devices", 900)
    shot(alex, Route.Settings, "07-settings-ios", 1300)
    shot(alex, Route.Account, "09-account", 1100)
    shot(alex, Route.Referral, "10-referral")
    shot(alex, Route.Protocol, "11-protocol")
    shot(alex, Route.Logs, "12-logs")
    alex.tunnel.disconnect().join()

    val owner = session("owner@jaganet.dev")
    shot(owner, Route.Admin, "14-owner-dashboard", 1280)
}
