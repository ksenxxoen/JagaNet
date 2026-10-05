package dev.jaganet.app

import dev.jaganet.api.Platform
import dev.jaganet.app.platform.AppPlatform
import dev.jaganet.app.platform.SecureStore
import dev.jaganet.app.tunnel.SimulatedEngine
import dev.jaganet.app.tunnel.TunnelEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.util.prefs.Preferences

/** Java Preferences: fine for the simulator, which holds no real secrets. */
class PrefsStore(node: String = "dev/jaganet/simulator") : SecureStore {
    private val prefs = Preferences.userRoot().node(node)
    override fun get(key: String): String? = prefs.get(key, null)
    override fun set(key: String, value: String) = prefs.put(key, value)
    override fun remove(key: String) = prefs.remove(key)
}

/** The desktop target is the app simulator: real UI and API calls, simulated tunnel. */
class DesktopPlatform(
    override val apiUrl: String = System.getenv("JAGANET_API_URL") ?: "http://localhost:4000",
    override val store: SecureStore = PrefsStore(),
    override val tunnel: TunnelEngine = SimulatedEngine(),
    override val kind: Platform = Platform.DESKTOP,
) : AppPlatform {
    override val deviceName = "Simulator (${System.getProperty("os.name")})"
    override fun httpClient() = HttpClient(Java)
    override fun share(text: String) = copy(text)
    override fun copy(text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }
    override fun openUrl(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }
    }
}
