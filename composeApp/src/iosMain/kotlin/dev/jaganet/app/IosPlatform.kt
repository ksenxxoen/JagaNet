package dev.jaganet.app

import androidx.compose.ui.window.ComposeUIViewController
import dev.jaganet.api.Platform
import dev.jaganet.api.Protocols
import dev.jaganet.api.TunnelConfig
import dev.jaganet.app.platform.AppPlatform
import dev.jaganet.app.platform.SecureStore
import dev.jaganet.app.tunnel.EngineStatus
import dev.jaganet.app.tunnel.LogLine
import dev.jaganet.app.tunnel.TunnelEngine
import dev.jaganet.app.tunnel.TunnelOptions
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import platform.UIKit.UIViewController

/** Entry point called from Swift: `IosPlatformKt.MainViewController(bridge:apiUrl:)`. */
fun MainViewController(bridge: IosNativeBridge, apiUrl: String): UIViewController {
    val platform = IosPlatform(bridge, apiUrl)
    return ComposeUIViewController { App(platform) }
}

class IosPlatform(private val bridge: IosNativeBridge, override val apiUrl: String) : AppPlatform {
    override val kind = Platform.IOS
    override val deviceName = bridge.deviceName
    override val store = object : SecureStore {
        override fun get(key: String) = bridge.keychainGet(key)
        override fun set(key: String, value: String) = bridge.keychainSet(key, value)
        override fun remove(key: String) = bridge.keychainRemove(key)
    }
    override val tunnel: TunnelEngine = BridgedTunnelEngine(bridge)
    override fun httpClient() = HttpClient(Darwin)
    override fun share(text: String) = bridge.share(text)
    override fun copy(text: String) = bridge.copy(text)
    override fun openUrl(url: String) = bridge.openUrl(url)
}

/** Adapts the callback bridge to the shared suspend/Flow engine interface. */
class BridgedTunnelEngine(private val bridge: IosNativeBridge) : TunnelEngine {
    private val json = Protocols.json
    override val simulated = false
    override val protocols get() = bridge.protocols()
    override val status = MutableStateFlow(EngineStatus())
    override val logs = MutableSharedFlow<LogLine>(replay = 200, extraBufferCapacity = 64)

    init {
        bridge.setListener(
            onStatus = { runCatching { status.value = json.decodeFromString(EngineStatus.serializer(), it) } },
            onLog = { runCatching { logs.tryEmit(json.decodeFromString(LogLine.serializer(), it)) } },
        )
    }

    override suspend fun requestPermission() = suspendCancellableCoroutine { c -> bridge.requestPermission { c.resume(it) } }

    override suspend fun clientParams(protocol: String): JsonObject = suspendCancellableCoroutine { c ->
        bridge.clientParams(protocol) { out, err ->
            if (out != null) c.resume(json.parseToJsonElement(out) as JsonObject) else c.resumeWithException(IllegalStateException(err ?: "clientParams failed"))
        }
    }

    override suspend fun start(config: TunnelConfig, options: TunnelOptions) = suspendCancellableCoroutine { c ->
        bridge.start(json.encodeToString(TunnelConfig.serializer(), config), json.encodeToString(TunnelOptions.serializer(), options)) { err ->
            if (err == null) c.resume(Unit) else c.resumeWithException(IllegalStateException(err))
        }
    }

    override suspend fun stop() = suspendCancellableCoroutine { c -> bridge.stop { c.resume(Unit) } }

    override suspend fun refresh() {
        val s = suspendCancellableCoroutine { c -> bridge.status { c.resume(it) } }
        runCatching { status.value = json.decodeFromString(EngineStatus.serializer(), s) }
    }
}
