package dev.jaganet.server

enum class Mode { PRODUCTION, SIMULATION, TEST }

data class Plans(
    val freeMonthlyBytes: Long,
    val freeDeviceLimit: Int,
    val proDeviceLimit: Int,
    val referralRewardDays: Int,
    val currency: String,
    /** List prices in minor units, only for the owner's MRR figure. Store prices are authoritative. */
    val priceMonthlyMinor: Long?,
    val priceYearlyMinor: Long?,
)

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
                protocols = (env["PROTOCOLS"] ?: "wireguard").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                exposeOtp = mode != Mode.PRODUCTION && env["EXPOSE_OTP"] != "0",
                plans = Plans(
                    freeMonthlyBytes = num("FREE_MONTHLY_GB", 10) * 1_000_000_000,
                    freeDeviceLimit = num("FREE_DEVICE_LIMIT", 1).toInt(),
                    proDeviceLimit = num("PRO_DEVICE_LIMIT", 5).toInt(),
                    referralRewardDays = num("REFERRAL_REWARD_DAYS", 30).toInt(),
                    currency = env["CURRENCY"] ?: "USD",
                    priceMonthlyMinor = env["PRICE_MONTHLY_MINOR"]?.takeIf { it.isNotBlank() }?.toLong(),
                    priceYearlyMinor = env["PRICE_YEARLY_MINOR"]?.takeIf { it.isNotBlank() }?.toLong(),
                ),
            )
        }
    }
}
