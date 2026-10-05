package dev.jaganet.server

import dev.jaganet.server.db.Db
import dev.jaganet.server.db.Embedded
import dev.jaganet.server.protocols.SimulatedDriver
import dev.jaganet.server.services.ConsoleMailer
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * `./gradlew :server:sim` — the whole backend in one process, nothing to install:
 * embedded PostgreSQL, a simulated VPN node (WireGuard + an example custom
 * protocol), demo data, sign-in codes returned by the API.
 */
fun main() {
    val env = mapOf(
        "JAGANET_MODE" to "simulation",
        "OWNER_EMAIL" to "owner@jaganet.dev",
        "PRICE_MONTHLY_MINOR" to "499",
        "PRICE_YEARLY_MINOR" to "3999",
    ) + System.getenv()
    val cfg = Config.load(env)
    val pg = Embedded.start(env["SIM_DATA_DIR"]?.let(::File))
    val db = Db(pg.postgresDatabase).also { it.migrate() }
    val ctx = Ctx(cfg, db, SimulatedDriver.registry(), ConsoleMailer())
    runBlocking { Seed(ctx).run() }
    println(
        """
        |
        |JagaNet simulation on http://localhost:${cfg.port}
        |  protocols: ${ctx.drivers.ids().joinToString()}
        |  accounts (sign in with the email; the code is printed here and returned by the API):
        |    alex@example.com   Pro yearly, 2 other devices, 30 days of history
        |    sam@example.com    Free plan, most of the monthly data used
        |    owner@jaganet.dev  Owner dashboard
        |""".trimMargin(),
    )
    serve(ctx, collectEveryMs = 5_000)
}
