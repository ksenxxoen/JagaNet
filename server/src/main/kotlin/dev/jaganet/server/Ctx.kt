package dev.jaganet.server

import dev.jaganet.server.db.Db
import dev.jaganet.server.protocols.DriverRegistry
import dev.jaganet.server.services.Mailer
import java.time.Instant

/** Everything a service needs. Built once in Main.kt / Sim.kt / tests. */
class Ctx(
    val cfg: Config,
    val db: Db,
    val drivers: DriverRegistry,
    val mailer: Mailer,
    val now: () -> Instant = Instant::now,
)
