package dev.jaganet.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import dev.jaganet.api.Platform
import dev.jaganet.app.demo.DemoBackend
import dev.jaganet.app.platform.AppPlatform
import dev.jaganet.app.platform.MemoryStore
import dev.jaganet.app.tunnel.SimulatedEngine

@JsFun("(url) => { window.open(url, '_blank'); }")
private external fun jsOpen(url: String)

@JsFun("(text) => { if (navigator.clipboard) navigator.clipboard.writeText(text).catch(() => {}); }")
private external fun jsCopy(text: String)

@JsFun("() => { const e = document.getElementById('loading'); if (e) e.remove(); }")
private external fun hideLoading()

/**
 * Online demo: the real app UI with the API answered in the page (DemoBackend)
 * and a simulated tunnel. Nothing is stored; reloading starts fresh.
 */
private class WebDemoPlatform : AppPlatform {
    private val backend = DemoBackend()
    override val kind = Platform.OTHER
    override val deviceName = "Web browser"
    override val apiUrl = "https://demo.jaganet.local"
    override val store = MemoryStore()
    override val tunnel = SimulatedEngine()
    override fun httpClient() = backend.client()
    override fun share(text: String) = jsCopy(text)
    override fun copy(text: String) = jsCopy(text)
    override fun openUrl(url: String) = jsOpen(url)
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val platform = WebDemoPlatform()
    hideLoading()
    // Fills <div id="app"> in index.html (fixed to the whole window).
    ComposeViewport(viewportContainerId = "app") {
        // Phone-width column in the middle of wide screens; full width on phones.
        Box(Modifier.fillMaxSize().background(Color(0xFFE4E2DC)), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 440.dp).fillMaxHeight()) { App(platform) }
        }
    }
}
