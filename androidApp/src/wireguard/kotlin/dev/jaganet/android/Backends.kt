package dev.jaganet.android

import android.content.Context
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
import dev.jaganet.app.AndroidTunnelBackend
import dev.jaganet.app.platform.SecureStore
import dev.jaganet.app.tunnel.SplitMode
import dev.jaganet.app.tunnel.TunnelOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * Fallback build without the AmneziaWG library (scripts/build-amneziawg-android.sh not run):
 * WireGuard only, via wireguard-android from Maven. "Automatic" then picks WireGuard.
 */
object Backends {
    fun create(context: Context, store: SecureStore, onState: (Boolean) -> Unit) = listOf(WireGuardBackend(context, store, onState))
}

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

    /** Generated on the device, stored Keystore-encrypted, never sent anywhere. */
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
