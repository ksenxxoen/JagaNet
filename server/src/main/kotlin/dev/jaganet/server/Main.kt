package dev.jaganet.server

import dev.jaganet.server.db.Db
import dev.jaganet.server.http.Services
import dev.jaganet.server.http.jaganet
import dev.jaganet.server.protocols.DriverRegistry
import dev.jaganet.server.protocols.ProtocolDriver
import dev.jaganet.server.protocols.AmneziaWgDriver
import dev.jaganet.server.protocols.WireGuardDriver
import dev.jaganet.server.services.ConsoleMailer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/** Drivers this build knows. Add a protocol: implement ProtocolDriver and list it here. */
val AVAILABLE_DRIVERS: Map<String, () -> ProtocolDriver> = mapOf(
    "amneziawg" to { AmneziaWgDriver() },
    "wireguard" to { WireGuardDriver() },
)

/** Production entry: real PostgreSQL (DATABASE_URL), real protocol drivers. */
fun main() {
    val cfg = Config.load()
    val db = Db.pooled(cfg.databaseUrl!!, cfg.databaseUser, cfg.databasePassword).also { it.migrate() }
    val drivers = DriverRegistry()
    // PROTOCOLS order = preference: the first is what "Automatic" picks on devices that support it.
    for (id in cfg.protocols) {
        val make = requireNotNull(AVAILABLE_DRIVERS[id]) { "No driver for protocol \"$id\". Known: ${AVAILABLE_DRIVERS.keys}" }
        drivers.register(make())
    }
    serve(Ctx(cfg, db, drivers, ConsoleMailer()), collectEveryMs = 60_000)
}

fun serve(ctx: Ctx, collectEveryMs: Long, wait: Boolean = true) {
    val services = Services(ctx)
    val log = LoggerFactory.getLogger("jaganet")
    embeddedServer(Netty, port = ctx.cfg.port, host = "0.0.0.0") {
        jaganet(services)
        launch {
            while (true) {
                delay(collectEveryMs)
                runCatching { services.traffic.collect() }.onFailure { log.error("traffic collection failed", it) }
            }
        }
    }.start(wait = wait)
}
