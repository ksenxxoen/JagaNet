package dev.jaganet.app.tunnel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jaganet.api.ApiClient
import dev.jaganet.api.ApiException
import dev.jaganet.api.ConnectionEventReq
import dev.jaganet.api.ConnectionEventType
import dev.jaganet.api.ErrorCode
import dev.jaganet.api.TunnelConfig
import dev.jaganet.api.TunnelProvisionReq
import dev.jaganet.app.state.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** Display info for the protocols this app knows. Unknown ids still work, shown by id. */
object ProtocolInfo {
    private val known = mapOf(
        "amneziawg" to ("AmneziaWG" to "WireGuard speed, disguised so networks that block VPNs can’t spot it."),
        "wireguard" to ("WireGuard" to "Plain WireGuard. Slightly lighter, but easy for censors to detect."),
        "jaga-custom" to ("Custom (demo)" to "Example of a custom protocol plugged into JagaNet."),
    )
    fun label(id: String) = known[id]?.first ?: id
    fun description(id: String) = known[id]?.second ?: "Custom protocol"
}

/**
 * Connect / disconnect flow, shared by all platforms:
 * pick server + protocol → engine makes clientParams → server provisions → engine starts.
 */
class TunnelController(
    private val api: ApiClient,
    val engine: TunnelEngine,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit,
) {
    var config by mutableStateOf<TunnelConfig?>(null)
        private set
    /** Error code from the API (DATA_LIMIT, DEVICE_LIMIT, …) so screens can offer the fix. */
    var blockedBy by mutableStateOf<ErrorCode?>(null)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    var downBps by mutableStateOf(0L)
        private set
    var upBps by mutableStateOf(0L)
        private set
    val logs = mutableListOf<LogLine>()

    private var peak = 0L
    private var last: Triple<Long, Long, Long>? = null

    val isUp get() = engine.status.value.status == TunnelStatus.CONNECTED

    init {
        scope.launch { engine.logs.collect { logs.add(0, it); if (logs.size > 500) logs.removeAt(logs.lastIndex) } }
        scope.launch {
            engine.status.collect { s ->
                val now = Clock.System.now().toEpochMilliseconds()
                val prev = last
                if (s.status == TunnelStatus.CONNECTED && prev != null && now > prev.first) {
                    val dt = (now - prev.first) / 1000.0
                    downBps = (((s.rxBytes - prev.second) * 8) / dt).toLong().coerceAtLeast(0)
                    upBps = (((s.txBytes - prev.third) * 8) / dt).toLong().coerceAtLeast(0)
                    peak = maxOf(peak, downBps)
                }
                last = Triple(now, s.rxBytes, s.txBytes)
            }
        }
        if (!engine.simulated) scope.launch { while (isActive) { delay(1000); if (isUp) engine.refresh() } }
    }

    fun connect() = scope.launch {
        blockedBy = null
        failure = null
        try {
            if (!engine.requestPermission()) {
                failure = "VPN permission was not granted"
                return@launch
            }
            val s = settings.state.value
            val servers = api.servers().servers
            val server = servers.firstOrNull { it.id == s.serverId } ?: servers.firstOrNull() ?: error("No server available")
            // The user's choice, else the first protocol the server offers that this device can run.
            val usable = server.protocols.filter { it in engine.protocols }
            val protocol = s.protocol?.takeIf { it in usable } ?: usable.firstOrNull() ?: error("No common protocol with ${server.name}")
            val cfg = api.provisionTunnel(TunnelProvisionReq(protocol, server.id, engine.clientParams(protocol)))
            config = cfg
            peak = 0
            last = null
            engine.start(cfg, TunnelOptions(s.killSwitch, s.split))
            runCatching { api.connectionEvent(ConnectionEventReq(ConnectionEventType.CONNECTED, Clock.System.now().toString())) }
        } catch (e: ApiException) {
            blockedBy = e.code
            failure = e.message
        } catch (e: Exception) {
            failure = e.message ?: "Could not connect"
        } finally {
            onChanged()
        }
    }

    fun disconnect() = scope.launch {
        engine.stop()
        downBps = 0
        upBps = 0
        runCatching { api.connectionEvent(ConnectionEventReq(ConnectionEventType.DISCONNECTED, Clock.System.now().toString(), peak)) }
        onChanged()
    }
}
