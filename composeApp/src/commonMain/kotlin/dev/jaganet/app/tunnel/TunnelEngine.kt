package dev.jaganet.app.tunnel

import dev.jaganet.api.TunnelConfig
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class TunnelStatus { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

@Serializable
enum class SplitMode { ALL, ONLY, EXCLUDE }

@Serializable
data class SplitTunnel(val mode: SplitMode = SplitMode.ALL, val apps: List<String> = emptyList())

@Serializable
data class TunnelOptions(val killSwitch: Boolean, val split: SplitTunnel)

@Serializable
data class EngineStatus(
    val status: TunnelStatus = TunnelStatus.DISCONNECTED,
    /** epoch ms when the tunnel came up */
    val since: Long? = null,
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val error: String? = null,
)

@Serializable
enum class LogLevel { INFO, WARN, ERROR }

@Serializable
data class LogLine(val time: Long, val level: LogLevel, val msg: String)

/**
 * The device side of the data plane. One engine runs every protocol: it receives
 * the generic TunnelConfig and hands it to the backend for `config.protocol`
 * (Android: Kotlin backends; iOS: Packet Tunnel extension; desktop: simulated).
 *
 * Secrets never reach shared code: the engine creates and keeps protocol keys and
 * only returns the non-secret clientParams the server needs.
 */
interface TunnelEngine {
    val simulated: Boolean
    /** Protocol ids this device can run. */
    val protocols: List<String>
    val status: StateFlow<EngineStatus>
    val logs: SharedFlow<LogLine>

    /** Shows the OS VPN consent dialog if needed. */
    suspend fun requestPermission(): Boolean
    /** Non-secret params for TunnelProvisionReq, e.g. a WireGuard public key. */
    suspend fun clientParams(protocol: String): JsonObject
    suspend fun start(config: TunnelConfig, options: TunnelOptions)
    suspend fun stop()
    /** Fresh byte counters (native engines only emit on state changes). */
    suspend fun refresh() {}
    /** Installed apps for split tunneling (Android). Empty where the OS has no per-app VPN. */
    suspend fun installedApps(): List<AppInfo> = emptyList()
}

@Serializable
data class AppInfo(val id: String, val label: String)
