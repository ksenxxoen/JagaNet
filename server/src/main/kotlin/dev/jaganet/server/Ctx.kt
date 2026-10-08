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
) {
    /** Settings the owner can change in the admin panel; start from the environment. */
    val live = Live(cfg)
}

/** Current values of the settings editable at runtime (saved in the settings table). */
class Live(cfg: Config) {
    @Volatile var plans: Plans = cfg.plans
    @Volatile var smtp: Smtp? = cfg.smtp
    @Volatile var alertEmails: List<String> = cfg.monitor.alertEmails
    @Volatile var alertTelegramChats: List<Long> = cfg.monitor.alertTelegramChats
    /** Test mode: sign-in codes in the API response instead of e-mail. */
    @Volatile var showSignInCodes: Boolean = cfg.exposeOtp
    /** Internet channel capacity, Mbit/s (CHANNEL_MBPS or the admin panel). */
    @Volatile var channelMbps: Long? = cfg.monitor.channelMbps
    /** Monthly traffic of the hosting plan (HOST_TRAFFIC_LIMIT_GB or the admin panel). */
    @Volatile var monthlyTrafficLimitBytes: Long? = cfg.monitor.monthlyTrafficLimitBytes
    /** "test" or a real provider id; null = selling is off. */
    @Volatile var paymentProvider: String? = cfg.paymentProvider
}
