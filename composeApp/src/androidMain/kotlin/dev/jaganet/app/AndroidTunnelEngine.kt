package dev.jaganet.app

import android.content.Context
import android.content.Intent
import dev.jaganet.api.TunnelConfig
import dev.jaganet.app.tunnel.AppInfo
import dev.jaganet.app.tunnel.EngineStatus
import dev.jaganet.app.tunnel.LogLevel
import dev.jaganet.app.tunnel.LogLine
import dev.jaganet.app.tunnel.TunnelEngine
import dev.jaganet.app.tunnel.TunnelOptions
import dev.jaganet.app.tunnel.TunnelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * One implementation per protocol on Android. The app module picks the set at build
 * time (androidApp/src/<backend>/kotlin/.../Backends.kt), so native libraries that
 * clash (wireguard-android and amneziawg-android both ship libwg-go.so) never meet.
 */
interface AndroidTunnelBackend {
    val protocol: String
    /** Creates/loads this device's keys; returns the non-secret part for the server. */
    fun clientParams(): JsonObject
    suspend fun start(config: TunnelConfig, options: TunnelOptions)
    suspend fun stop()
    fun counters(): Pair<Long, Long>
}

class AndroidTunnelEngine(
    private val context: Context,
    backendList: List<AndroidTunnelBackend>,
    private val requestConsent: suspend () -> Boolean,
) : TunnelEngine {
    override val simulated = false
    override val status = MutableStateFlow(EngineStatus())
    override val logs = MutableSharedFlow<LogLine>(replay = 200, extraBufferCapacity = 64)

    private val backends: Map<String, AndroidTunnelBackend> = backendList.associateBy { it.protocol }
    private var active: AndroidTunnelBackend? = null

    override val protocols = backends.keys.toList()

    private fun log(level: LogLevel, msg: String) = logs.tryEmit(LogLine(System.currentTimeMillis(), level, msg))

    /** Backends call this when the OS reports the tunnel up or down. */
    fun onBackendState(up: Boolean) {
        status.update { it.copy(status = if (up) TunnelStatus.CONNECTED else TunnelStatus.DISCONNECTED, since = if (up) it.since ?: System.currentTimeMillis() else null) }
    }

    override suspend fun requestPermission(): Boolean = requestConsent()

    override suspend fun clientParams(protocol: String): JsonObject =
        requireNotNull(backends[protocol]) { "No backend for $protocol" }.clientParams()

    override suspend fun start(config: TunnelConfig, options: TunnelOptions) {
        val backend = requireNotNull(backends[config.protocol]) { "No backend for ${config.protocol}" }
        active?.takeIf { it !== backend }?.stop()
        status.value = EngineStatus(TunnelStatus.CONNECTING)
        log(LogLevel.INFO, "Starting ${config.protocol} tunnel to ${config.location}")
        try {
            backend.start(config, options)
            active = backend
            status.value = EngineStatus(TunnelStatus.CONNECTED, since = System.currentTimeMillis())
            log(LogLevel.INFO, "Tunnel up at ${config.address}")
            if (options.killSwitch) log(LogLevel.INFO, "For a full kill switch enable Always-on VPN + Block connections in Android settings")
        } catch (e: Exception) {
            log(LogLevel.ERROR, e.message ?: "Failed to start")
            status.value = EngineStatus(TunnelStatus.ERROR, error = e.message)
            throw e
        }
    }

    override suspend fun stop() {
        status.update { it.copy(status = TunnelStatus.DISCONNECTING) }
        active?.stop()
        active = null
        log(LogLevel.INFO, "Tunnel down")
        status.value = EngineStatus()
    }

    override suspend fun refresh() {
        val (rx, tx) = active?.counters() ?: return
        status.update { it.copy(rxBytes = rx, txBytes = tx) }
    }

    override suspend fun installedApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcher, 0)
            .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.id != context.packageName }
            .distinctBy { it.id }
            .sortedBy { it.label.lowercase() }
    }
}
