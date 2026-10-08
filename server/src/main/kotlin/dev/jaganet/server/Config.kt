package dev.jaganet.server

import dev.jaganet.api.ProductId
import dev.jaganet.api.i18n.Lang

enum class Mode { PRODUCTION, SIMULATION, TEST }

data class Plans(
    val freeMonthlyBytes: Long,
    val freeDeviceLimit: Int,
    val proDeviceLimit: Int,
    val referralRewardDays: Int,
    /** List prices per currency ("RUB", "EUR"). In-app purchases are priced by the stores. */
    val prices: Map<String, Price>,
) {
    /** Russian pays in rubles, German and English in euros. */
    fun currencyFor(lang: Lang): String = if (lang == Lang.RU) "RUB" else "EUR"

    fun price(product: ProductId, currency: String): Long? = prices[currency]?.let {
        when (product) { ProductId.PRO_MONTHLY -> it.monthlyMinor; ProductId.PRO_YEARLY -> it.yearlyMinor }
    }
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
    /** Where the website's "Download for Android" points. Default: $publicUrl/download/android. */
    val androidAppUrl: String? = null,
    val iosAppUrl: String? = null,
    /** Files served at /download/… (jaganet.apk). */
    val downloadsDir: String? = null,
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
                protocols = (env["PROTOCOLS"] ?: "amneziawg,wireguard").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                // Production: only with TEST_SHOW_SIGNIN_CODES=1, for a server that has no email set up yet.
                // Anyone can then sign in as anyone; turn it off before real users arrive.
                exposeOtp = if (mode == Mode.PRODUCTION) env["TEST_SHOW_SIGNIN_CODES"] == "1" else env["EXPOSE_OTP"] != "0",
                plans = Plans(
                    freeMonthlyBytes = num("FREE_MONTHLY_GB", 10) * 1_000_000_000,
                    freeDeviceLimit = num("FREE_DEVICE_LIMIT", 1).toInt(),
                    proDeviceLimit = num("PRO_DEVICE_LIMIT", 5).toInt(),
                    referralRewardDays = num("REFERRAL_REWARD_DAYS", 30).toInt(),
                    // Minor units: 29900 = 299 ₽, 499 = 4,99 €.
                    prices = mapOf(
                        "RUB" to Price(num("PRICE_RUB_MONTHLY", 29_900), num("PRICE_RUB_YEARLY", 249_000)),
                        "EUR" to Price(num("PRICE_EUR_MONTHLY", 499), num("PRICE_EUR_YEARLY", 3_999)),
                    ),
                ),
                paymentProvider = env["PAYMENT_PROVIDER"]?.takeIf { it.isNotBlank() } ?: "test".takeIf { mode != Mode.PRODUCTION },
                telegramBotToken = env["TELEGRAM_BOT_TOKEN"]?.takeIf { it.isNotBlank() },
                androidAppUrl = env["ANDROID_APP_URL"]?.takeIf { it.isNotBlank() },
                iosAppUrl = env["IOS_APP_URL"]?.takeIf { it.isNotBlank() },
                downloadsDir = env["DOWNLOADS_DIR"]?.takeIf { it.isNotBlank() },
            )
        }
    }
}
