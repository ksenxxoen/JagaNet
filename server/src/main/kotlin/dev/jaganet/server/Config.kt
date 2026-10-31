package dev.jaganet.server

import dev.jaganet.api.i18n.Lang

enum class Mode { PRODUCTION, SIMULATION, TEST }

data class Plans(
    /** 0 = no data limit. */
    val freeMonthlyBytes: Long,
    val freeDeviceLimit: Int,
    /** Devices on paid subscriptions bought before tariffs kept their own terms. */
    val proDeviceLimit: Int,
    /** Speed per device on Free, 0 = no limit. */
    val freeSpeedMbps: Int = 0,
    /** Days of Free after signing up, then the user is asked to buy; 0 = for ever. */
    val freeDays: Int = 0,
    /** Prices ("RUB", "EUR") of the two tariffs made on first start; after that tariffs live in the database. */
    val prices: Map<String, Price>,
) {
    /** Russian pays in rubles, German and English in euros. */
    fun currencyFor(lang: Lang): String = if (lang == Lang.RU) "RUB" else "EUR"
}

/** Prices in minor units (kopecks, cents). */
data class Price(val monthlyMinor: Long, val yearlyMinor: Long)

data class Config(
    val mode: Mode,
    val port: Int,
    val databaseUrl: String?,
    val databaseUser: String?,
    val databasePassword: String?,
    val authSecret: String,
    val publicUrl: String,
    val ownerEmail: String?,
    val protocols: List<String>,
    /** Simulation only: return sign-in codes in the API response. */
    val exposeOtp: Boolean,
    val plans: Plans,
    /** Website / Telegram checkout: "test" (built-in fake checkout) or a real provider id; null = selling off. */
    val paymentProvider: String? = null,
    /** From @BotFather. Unset = no Telegram bot. */
    val telegramBotToken: String? = null,
    /** The bot's mode, support chat, login widget… (TELEGRAM_* variables, issue #38). */
    val telegram: dev.jaganet.server.telegram.TelegramConfig = dev.jaganet.server.telegram.TelegramConfig(),
    /** Where the website's "Download for Android" points. Default: $publicUrl/download/android. */
    val androidAppUrl: String? = null,
    val iosAppUrl: String? = null,
    /** Files served at /download/… (jaganet.apk). */
    val downloadsDir: String? = null,
    /** Outgoing e-mail (sign-in codes, alerts). Null = not set up. */
    val smtp: Smtp? = null,
    val monitor: MonitorConfig = MonitorConfig(),
    /** BitTorrent counters of this machine's VPN devices, written by traffic.py; missing = no split. */
    val trafficClassesFile: String = "/run/boop/traffic.json",
    /** Where the free plan's speed limit for this machine's VPN is written for traffic.py; null = not written. */
    val speedLimitsFile: String? = null,
    /** Days of Pro given to every new (non-owner) account on sign-up; 0 = none. */
    val signupProDays: Int = 0,
    /** true = every user sees the referral program; false = only admins (owners). */
    val referralsPublic: Boolean = false,
) {
    companion object {
        fun load(env: Map<String, String> = System.getenv()): Config {
            val mode = env["JAGANET_MODE"]?.let { Mode.valueOf(it.uppercase()) } ?: Mode.PRODUCTION
            val secret = env["AUTH_SECRET"].orEmpty()
            if (mode == Mode.PRODUCTION) {
                require(secret.length >= 32) { "AUTH_SECRET must be at least 32 characters" }
                require(!env["DATABASE_URL"].isNullOrBlank()) { "DATABASE_URL is required" }
            }
            fun num(k: String, d: Long) = env[k]?.takeIf { it.isNotBlank() }?.toLong() ?: d
            return Config(
                mode = mode,
                port = num("PORT", 4000).toInt(),
                databaseUrl = env["DATABASE_URL"]?.takeIf { it.isNotBlank() },
                databaseUser = env["DATABASE_USER"],
                databasePassword = env["DATABASE_PASSWORD"],
                authSecret = secret.ifEmpty { "simulation-only-secret-do-not-use-in-production" },
                publicUrl = env["PUBLIC_URL"] ?: "http://localhost:4000",
                ownerEmail = env["OWNER_EMAIL"]?.lowercase()?.takeIf { it.isNotBlank() },
                protocols = (env["PROTOCOLS"] ?: "amneziawg,wireguard,vless").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                // Production: only with TEST_SHOW_SIGNIN_CODES=1, for a server that has no email set up yet.
                // Anyone can then sign in as anyone; turn it off before real users arrive.
                exposeOtp = if (mode == Mode.PRODUCTION) env["TEST_SHOW_SIGNIN_CODES"] == "1" else env["EXPOSE_OTP"] != "0",
                plans = Plans(
                    freeMonthlyBytes = num("FREE_MONTHLY_GB", 10) * 1_000_000_000,
                    freeDeviceLimit = num("FREE_DEVICE_LIMIT", 1).toInt(),
                    proDeviceLimit = num("PRO_DEVICE_LIMIT", 5).toInt(),
                    freeSpeedMbps = num("FREE_SPEED_MBPS", 0).toInt(),
                    freeDays = num("FREE_DAYS", 0).toInt(),
                    // Minor units: 29900 = 299 ₽, 499 = 4,99 €.
                    prices = mapOf(
                        "RUB" to Price(num("PRICE_RUB_MONTHLY", 29_900), num("PRICE_RUB_YEARLY", 249_000)),
                        "EUR" to Price(num("PRICE_EUR_MONTHLY", 499), num("PRICE_EUR_YEARLY", 3_999)),
                    ),
                ),
                signupProDays = num("SIGNUP_PRO_DAYS", 0).toInt().coerceAtLeast(0),
                referralsPublic = env["REFERRALS_PUBLIC"] == "1",
                paymentProvider = env["PAYMENT_PROVIDER"]?.takeIf { it.isNotBlank() } ?: "test".takeIf { mode != Mode.PRODUCTION },
                telegramBotToken = env["TELEGRAM_BOT_TOKEN"]?.takeIf { it.isNotBlank() },
                telegram = dev.jaganet.server.telegram.TelegramConfig.load(env),
                androidAppUrl = env["ANDROID_APP_URL"]?.takeIf { it.isNotBlank() },
                iosAppUrl = env["IOS_APP_URL"]?.takeIf { it.isNotBlank() },
                downloadsDir = env["DOWNLOADS_DIR"]?.takeIf { it.isNotBlank() },
                smtp = env["SMTP_HOST"]?.takeIf { it.isNotBlank() }?.let { host ->
                    val security = env["SMTP_SECURITY"]?.lowercase() ?: "starttls"
                    Smtp(
                        host = host,
                        port = num("SMTP_PORT", if (security == "ssl") 465 else 587).toInt(),
                        user = env["SMTP_USER"]?.takeIf { it.isNotBlank() },
                        password = env["SMTP_PASSWORD"],
                        from = env["SMTP_FROM"]?.takeIf { it.isNotBlank() } ?: env["SMTP_USER"] ?: "jaganet@localhost",
                        security = security,
                    )
                },
                monitor = MonitorConfig(
                    enabled = env["MONITOR"]?.let { it != "0" } ?: (mode != Mode.TEST),
                    alertEmails = (env["ALERT_EMAILS"] ?: env["OWNER_EMAIL"].orEmpty()).split(',').map { it.trim().lowercase() }
                        .filter { '@' in it && !it.endsWith("@jaganet.dev") },
                    alertTelegramChats = env["ALERT_TELEGRAM_CHAT_IDS"].orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() },
                    channelMbps = env["CHANNEL_MBPS"]?.takeIf { it.isNotBlank() }?.toLong(),
                    monthlyTrafficLimitBytes = env["HOST_TRAFFIC_LIMIT_GB"]?.takeIf { it.isNotBlank() }?.toLong()?.times(1_000_000_000),
                    wanInterface = env["WAN_INTERFACE"]?.takeIf { it.isNotBlank() },
                    pingTargets = (env["PING_TARGETS"] ?: "1.1.1.1,8.8.8.8").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    blocklistRknUrl = env["BLOCKLIST_RKN_URL"] ?: "https://antifilter.network/download/ipsum.lst",
                    blocklistCdnUrl = env["BLOCKLIST_CDN_URL"] ?: "https://raw.githubusercontent.com/123jjck/cdn-ip-ranges/refs/heads/main/all/all.csv",
                ),
                trafficClassesFile = env["TRAFFIC_CLASSES_FILE"]?.takeIf { it.isNotBlank() } ?: "/run/boop/traffic.json",
                speedLimitsFile = env["SPEED_LIMITS_FILE"]?.takeIf { it.isNotBlank() },
            )
        }
    }
}

data class Smtp(val host: String, val port: Int, val user: String?, val password: String?, val from: String, /** "starttls", "ssl" or "none" */ val security: String)

data class MonitorConfig(
    /** Checks every minute; on by default except in tests. */
    val enabled: Boolean = false,
    /** Who gets alert e-mails. Default: OWNER_EMAIL (unless it is the placeholder). */
    val alertEmails: List<String> = emptyList(),
    /** Telegram chats that get alerts (the bot's /myid shows a chat's id). */
    val alertTelegramChats: List<Long> = emptyList(),
    /** Internet channel capacity, Mbit/s, for the load percentage; else what the network card reports. */
    val channelMbps: Long? = null,
    /** The hosting plan's monthly traffic allowance. */
    val monthlyTrafficLimitBytes: Long? = null,
    /** Internet interface; default: the one with the default route. */
    val wanInterface: String? = null,
    val pingTargets: List<String> = listOf("1.1.1.1", "8.8.8.8"),
    /** Subnets on the Roskomnadzor block list (antifilter.network, as cheburcheck.ru uses); empty = off. */
    val blocklistRknUrl: String = "",
    /** Hosting and CDN ranges throttled in Russia (123jjck/cdn-ip-ranges, as cheburcheck.ru uses); empty = off. */
    val blocklistCdnUrl: String = "",
)
