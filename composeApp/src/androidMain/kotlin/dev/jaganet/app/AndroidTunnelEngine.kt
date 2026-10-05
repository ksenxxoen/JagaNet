package dev.jaganet.app

import android.content.Context
import android.content.Intent
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import dev.jaganet.api.Protocols
import dev.jaganet.api.TunnelConfig
import dev.jaganet.api.WireGuard
import dev.jaganet.app.platform.SecureStore
import dev.jaganet.app.tunnel.AppInfo
import dev.jaganet.app.tunnel.EngineStatus
import dev.jaganet.app.tunnel.LogLevel
import dev.jaganet.app.tunnel.LogLine
import dev.jaganet.app.tunnel.SplitMode
import dev.jaganet.app.tunnel.TunnelEngine
import dev.jaganet.app.tunnel.TunnelOptions
import dev.jaganet.app.tunnel.TunnelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.net.InetAddress

/** One implementation per protocol on Android. Register new ones in [AndroidTunnelEngine.backends]. */
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
    private val store: SecureStore,
    private val requestConsent: suspend () -> Boolean,
) : TunnelEngine {
    override val simulated = false
    override val status = MutableStateFlow(EngineStatus())
    override val logs = MutableSharedFlow<LogLine>(replay = 200, extraBufferCapacity = 64)

    private val backends: Map<String, AndroidTunnelBackend> = listOf(
        WireGuardBackend(context, store) { up -> onBackendState(up) },
    ).associateBy { it.protocol }
    private var active: AndroidTunnelBackend? = null

    override val protocols = backends.keys.toList()

    private fun log(level: LogLevel, msg: String) = logs.tryEmit(LogLine(System.currentTimeMillis(), level, msg))

    private fun onBackendState(up: Boolean) {
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
            log(LogLevel.INFO, "Tunnel up · ${config.address}")
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

/** WireGuard through wireguard-android's userspace GoBackend (it brings its own VpnService). */
class WireGuardBackend(
    private val context: Context,
    private val store: SecureStore,
    private val onState: (Boolean) -> Unit,
) : AndroidTunnelBackend {
    override val protocol = Protocols.WIREGUARD
    private var backend: GoBackend? = null
    private val tunnel = object : Tunnel {
        override fun getName() = "jaganet"
        override fun onStateChange(newState: Tunnel.State) = onState(newState == Tunnel.State.UP)
    }

    /** The private key is generated on the device and stored encrypted by the Keystore; it never leaves the phone. */
    private fun keyPair(): KeyPair {
        store.get(KEY)?.let { return KeyPair(Key.fromBase64(it)) }
        return KeyPair().also { store.set(KEY, it.privateKey.toBase64()) }
    }

    override fun clientParams() = Protocols.encode(WireGuard.ClientParams(keyPair().publicKey.toBase64()))

    override suspend fun start(config: TunnelConfig, options: TunnelOptions) = withContext(Dispatchers.IO) {
        val p = Protocols.decode<WireGuard.ServerParams>(config.params)
        val iface = Interface.Builder()
            .setKeyPair(keyPair())
            .addAddress(InetNetwork.parse(config.address))
            .setMtu(config.mtu)
        config.dns.forEach { iface.addDnsServer(InetAddress.getByName(it)) }
        when (options.split.mode) {
            SplitMode.ONLY -> iface.includeApplications(options.split.apps)
            SplitMode.EXCLUDE -> iface.excludeApplications(options.split.apps)
            SplitMode.ALL -> Unit
        }
        val peer = Peer.Builder()
            .setPublicKey(Key.fromBase64(p.serverPublicKey))
            .setEndpoint(InetEndpoint.parse(p.endpoint))
            .setPersistentKeepalive(p.persistentKeepalive)
        p.allowedIps.forEach { peer.addAllowedIp(InetNetwork.parse(it)) }

        val wg = backend ?: GoBackend(context).also { backend = it }
        wg.setState(tunnel, Tunnel.State.UP, Config.Builder().setInterface(iface.build()).addPeer(peer.build()).build())
        Unit
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        backend?.setState(tunnel, Tunnel.State.DOWN, null)
        Unit
    }

    override fun counters(): Pair<Long, Long> {
        val s = backend?.getStatistics(tunnel) ?: return 0L to 0L
        return s.totalRx() to s.totalTx()
    }

    private companion object { const val KEY = "wireguard.privateKey" }
}
