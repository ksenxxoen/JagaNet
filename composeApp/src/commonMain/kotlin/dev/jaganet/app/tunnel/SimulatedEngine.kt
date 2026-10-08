package dev.jaganet.app.tunnel

import dev.jaganet.api.Protocols
import dev.jaganet.api.TunnelConfig
import dev.jaganet.app.i18n.t
import dev.jaganet.app.i18n.tp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.Clock

/** Behaves like a native engine without touching the network: for the desktop simulator and previews. */
class SimulatedEngine(private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) : TunnelEngine {
    override val simulated = true
    override val protocols = listOf(Protocols.AMNEZIAWG, Protocols.WIREGUARD, "jaga-custom")
    override val status = MutableStateFlow(EngineStatus())
    override val logs = MutableSharedFlow<LogLine>(replay = 200, extraBufferCapacity = 64)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var traffic: Job? = null
    /** Stand-in for a key kept in the Keychain/Keystore. Any 32 bytes form a valid WireGuard key shape. */
    private val fakePublicKey = Base64.encode(Random.nextBytes(32))

    private fun log(level: LogLevel, msg: String) = logs.tryEmit(LogLine(now(), level, msg))

    override suspend fun requestPermission() = true

    override suspend fun clientParams(protocol: String): JsonObject = when (protocol) {
        Protocols.AMNEZIAWG, Protocols.WIREGUARD -> JsonObject(mapOf("publicKey" to JsonPrimitive(fakePublicKey)))
        else -> JsonObject(emptyMap())
    }

    override suspend fun start(config: TunnelConfig, options: TunnelOptions) {
        status.update { it.copy(status = TunnelStatus.CONNECTING, error = null) }
        log(LogLevel.INFO, t("Starting {protocol} tunnel to {location}", "protocol" to ProtocolInfo.label(config.protocol), "location" to config.location))
        delay(900)
        if (options.killSwitch) log(LogLevel.INFO, t("Kill switch armed"))
        (config.params["obfuscation"] as? JsonObject)?.let { o ->
            val junk = o["Jc"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
            log(LogLevel.INFO, t("Obfuscation on: {packets}, padded handshake, custom headers", "packets" to tp(junk, "{n} junk packet|{n} junk packets")))
        }
        log(LogLevel.INFO, t("Handshake completed with {endpoint}", "endpoint" to (config.params["endpoint"]?.jsonPrimitive?.content ?: config.serverId)))
        log(LogLevel.INFO, t("Tunnel up, address {address}", "address" to config.address))
        log(LogLevel.INFO, t("DNS set to {dns}", "dns" to config.dns.joinToString()))
        status.value = EngineStatus(TunnelStatus.CONNECTED, since = now())
        traffic = scope.launch {
            while (isActive) {
                delay(1000)
                val down = 2_500_000L + Random.nextLong(3_000_000)
                status.update { it.copy(rxBytes = it.rxBytes + down, txBytes = it.txBytes + down * 22 / 100) }
            }
        }
    }

    override suspend fun stop() {
        traffic?.cancel()
        status.update { it.copy(status = TunnelStatus.DISCONNECTING) }
        delay(300)
        log(LogLevel.INFO, t("Tunnel down"))
        status.value = EngineStatus(TunnelStatus.DISCONNECTED)
    }

    override suspend fun installedApps() = listOf("Browser", "Messenger", "Mail", "Maps", "Banking", "Video streaming", "Work chat")
        .map { AppInfo("sim.${it.lowercase().replace(' ', '.')}", it) }
}
