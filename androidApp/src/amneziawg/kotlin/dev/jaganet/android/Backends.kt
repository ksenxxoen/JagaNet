package dev.jaganet.android

import android.content.Context
import dev.jaganet.api.AmneziaWG
import dev.jaganet.api.Protocols
import dev.jaganet.api.TunnelConfig
import dev.jaganet.api.WireGuard
import dev.jaganet.app.AndroidTunnelBackend
import dev.jaganet.app.platform.SecureStore
import dev.jaganet.app.tunnel.SplitMode
import dev.jaganet.app.tunnel.TunnelOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import org.amnezia.awg.crypto.Key
import org.amnezia.awg.crypto.KeyPair

/** Build with amneziawg-android: it runs AmneziaWG and, with no obfuscation values, plain WireGuard. */
object Backends {
    fun create(context: Context, store: SecureStore, onState: (Boolean) -> Unit) = listOf(
        AwgBackend(Protocols.AMNEZIAWG, context, store, onState),
        AwgBackend(Protocols.WIREGUARD, context, store, onState),
    )
}

class AwgBackend(
    override val protocol: String,
    private val context: Context,
    private val store: SecureStore,
    private val onState: (Boolean) -> Unit,
) : AndroidTunnelBackend {
    private val tunnel = object : Tunnel {
        override fun getName() = "jaganet"
        override fun onStateChange(newState: Tunnel.State) = onState(newState == Tunnel.State.UP)
    }

    /** Generated on the device, stored Keystore-encrypted, never sent anywhere. One key per protocol. */
    private fun keyPair(): KeyPair {
        val k = "$protocol.privateKey"
        store.get(k)?.let { return KeyPair(Key.fromBase64(it)) }
        return KeyPair().also { store.set(k, it.privateKey.toBase64()) }
    }

    override fun clientParams() = Protocols.encode(WireGuard.ClientParams(keyPair().publicKey.toBase64()))

    override suspend fun start(config: TunnelConfig, options: TunnelOptions) = withContext(Dispatchers.IO) {
        val p = if (protocol == Protocols.AMNEZIAWG) Protocols.decode<AmneziaWG.ServerParams>(config.params)
        else Protocols.decode<WireGuard.ServerParams>(config.params).let { AmneziaWG.ServerParams(it.serverPublicKey, it.endpoint, it.allowedIps, it.persistentKeepalive, emptyMap()) }
        val split = when (options.split.mode) {
            SplitMode.ONLY -> listOf("IncludedApplications = ${options.split.apps.joinToString(", ")}")
            SplitMode.EXCLUDE -> listOf("ExcludedApplications = ${options.split.apps.joinToString(", ")}")
            SplitMode.ALL -> emptyList()
        }
        val text = AmneziaWG.quickConfig(keyPair().privateKey.toBase64(), config.address, config.dns, config.mtu, p, split)
        backend.setState(tunnel, Tunnel.State.UP, Config.parse(text.byteInputStream()))
        Unit
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        backend.setState(tunnel, Tunnel.State.DOWN, null)
        Unit
    }

    override fun counters(): Pair<Long, Long> = backend.getStatistics(tunnel).let { it.totalRx() to it.totalTx() }

    private val backend get() = shared ?: synchronized(Backends) { shared ?: GoBackend(context).also { shared = it } }

    private companion object {
        /** One GoBackend per process, shared by both protocols (it owns the single VpnService). */
        @Volatile var shared: GoBackend? = null
    }
}
