package dev.jaganet.server.services

import dev.jaganet.api.AdminSettingsRes
import dev.jaganet.api.AlertSettings
import dev.jaganet.api.ModeSettings
import dev.jaganet.api.NetworkSettings
import dev.jaganet.api.PlanSettings
import dev.jaganet.api.PriceSettings
import dev.jaganet.api.Protocols
import dev.jaganet.api.SmtpSettings
import dev.jaganet.server.Ctx
import dev.jaganet.server.Plans
import dev.jaganet.server.Price
import dev.jaganet.server.Smtp
import dev.jaganet.server.badRequest
import dev.jaganet.server.db.Jsonb
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Settings the owner edits in the admin panel: plans and prices, outgoing e-mail, alert
 * recipients and the test modes. Stored in the settings table, applied at once (ctx.live).
 * The SMTP password is stored encrypted.
 */
class Settings(private val ctx: Ctx) {
    private val log = LoggerFactory.getLogger("settings")

    /** At start: database values win over the environment file. */
    suspend fun load() {
        val rows = ctx.db.run { sql -> sql.query("SELECT key, value FROM settings").associate { it.str("key") to it.json("value") } }
        rows["plans"]?.let { runCatching { apply(Protocols.decode<PlanSettings>(it)) }.onFailure { e -> log.error("bad plans setting", e) } }
        rows["smtp"]?.let { runCatching { ctx.live.smtp = Protocols.decode<StoredSmtp>(it).toSmtp() }.onFailure { e -> log.error("bad smtp setting", e) } }
        rows["alerts"]?.let { runCatching { Protocols.decode<AlertSettings>(it).let { a -> ctx.live.alertEmails = a.emails; ctx.live.alertTelegramChats = a.telegramChats } } }
        rows["modes"]?.let { runCatching { applyModes(Protocols.decode<ModeSettings>(it)) } }
        rows["network"]?.let { runCatching { applyNetwork(Protocols.decode<NetworkSettings>(it)) } }
        if (rows["smtp_off"] != null) ctx.live.smtp = null
    }

    fun get(botEnabled: Boolean): AdminSettingsRes {
        val p = ctx.live.plans
        return AdminSettingsRes(
            plans = PlanSettings(
                freeMonthlyGb = p.freeMonthlyBytes / 1_000_000_000, freeDeviceLimit = p.freeDeviceLimit, proDeviceLimit = p.proDeviceLimit,
                referralRewardDays = p.referralRewardDays, prices = p.prices.mapValues { (_, v) -> PriceSettings(v.monthlyMinor, v.yearlyMinor) },
            ),
            smtp = ctx.live.smtp?.let { SmtpSettings(it.host, it.port, it.user, null, it.from, it.security) },
            smtpHasPassword = ctx.live.smtp?.password?.isNotEmpty() == true,
            alerts = AlertSettings(ctx.live.alertEmails, ctx.live.alertTelegramChats),
            modes = ModeSettings(ctx.live.showSignInCodes, ctx.live.paymentProvider == "test"),
            network = NetworkSettings(ctx.live.channelMbps, ctx.live.monthlyTrafficLimitBytes?.div(1_000_000_000)),
            paymentsConnected = ctx.live.paymentProvider != null && ctx.live.paymentProvider != "test",
            botEnabled = botEnabled,
        )
    }

    suspend fun savePlans(s: PlanSettings) {
        if (s.freeMonthlyGb !in 0..10_000 || s.freeDeviceLimit !in 0..100 || s.proDeviceLimit !in 1..100 || s.referralRewardDays !in 0..365) {
            throw badRequest("Some values are out of range")
        }
        for (c in listOf("RUB", "EUR")) {
            val pr = s.prices[c] ?: throw badRequest("Prices in rubles and euros are required")
            if (pr.monthlyMinor <= 0 || pr.yearlyMinor <= 0) throw badRequest("Prices must be above zero")
        }
        apply(s)
        store("plans", Protocols.encode(s))
    }

    private fun apply(s: PlanSettings) {
        ctx.live.plans = ctx.live.plans.copy(
            freeMonthlyBytes = s.freeMonthlyGb * 1_000_000_000, freeDeviceLimit = s.freeDeviceLimit, proDeviceLimit = s.proDeviceLimit,
            referralRewardDays = s.referralRewardDays, prices = s.prices.mapValues { (_, v) -> Price(v.monthlyMinor, v.yearlyMinor) },
        )
    }

    /** host = "" turns e-mail off. A null password keeps the current one. */
    suspend fun saveSmtp(s: SmtpSettings) {
        if (s.host.isBlank()) {
            ctx.live.smtp = null
            ctx.db.run { it.exec("DELETE FROM settings WHERE key='smtp'") }
            store("smtp_off", JsonObject(emptyMap()))
            return
        }
        if (s.port !in 1..65535) throw badRequest("Port must be 1 to 65535")
        if ('@' !in s.from) throw badRequest("Invalid email")
        if (s.security !in setOf("starttls", "ssl", "none")) throw badRequest("Some values are out of range")
        val password = s.password ?: ctx.live.smtp?.password
        val smtp = Smtp(s.host.trim(), s.port, s.user?.trim()?.takeIf { it.isNotEmpty() }, password, s.from.trim(), s.security)
        ctx.live.smtp = smtp
        ctx.db.run { it.exec("DELETE FROM settings WHERE key='smtp_off'") }
        store("smtp", Protocols.encode(StoredSmtp(smtp.host, smtp.port, smtp.user, password?.let(::encrypt), smtp.from, smtp.security)))
    }

    suspend fun saveAlerts(a: AlertSettings) {
        val emails = a.emails.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (emails.any { !Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(it) }) throw badRequest("Invalid email")
        ctx.live.alertEmails = emails
        ctx.live.alertTelegramChats = a.telegramChats.distinct()
        store("alerts", Protocols.encode(AlertSettings(emails, ctx.live.alertTelegramChats)))
    }

    suspend fun saveModes(m: ModeSettings) {
        applyModes(m)
        store("modes", Protocols.encode(m))
    }

    suspend fun saveNetwork(n: NetworkSettings) {
        if ((n.channelMbps != null && n.channelMbps !in 1L..1_000_000L) || (n.monthlyTrafficGb != null && n.monthlyTrafficGb !in 1L..10_000_000L)) {
            throw badRequest("Some values are out of range")
        }
        applyNetwork(n)
        store("network", Protocols.encode(n))
    }

    private fun applyNetwork(n: NetworkSettings) {
        ctx.live.channelMbps = n.channelMbps
        ctx.live.monthlyTrafficLimitBytes = n.monthlyTrafficGb?.times(1_000_000_000)
    }

    private fun applyModes(m: ModeSettings) {
        ctx.live.showSignInCodes = m.showSignInCodes
        val real = ctx.live.paymentProvider?.takeIf { it != "test" }
        // Test payments only stand in while no real service is connected.
        ctx.live.paymentProvider = real ?: if (m.testPayments) "test" else null
    }

    private suspend fun store(key: String, value: JsonObject) = ctx.db.run {
        it.exec(
            "INSERT INTO settings (key, value, updated_at) VALUES (?,?,?) ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value, updated_at=EXCLUDED.updated_at",
            key, Jsonb(value.toString()), ctx.now(),
        )
    }

    @kotlinx.serialization.Serializable
    private data class StoredSmtp(val host: String, val port: Int, val user: String?, val passwordEnc: String?, val from: String, val security: String)

    private fun StoredSmtp.toSmtp() = Smtp(host, port, user, passwordEnc?.let(::decrypt), from, security)

    /* The SMTP password, encrypted with a key derived from AUTH_SECRET. */
    private val aesKey by lazy { SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("settings:${ctx.cfg.authSecret}".toByteArray()), "AES") }
    private fun encrypt(plain: String): String {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv)) }
        return Base64.getEncoder().encodeToString(iv + c.doFinal(plain.toByteArray()))
    }
    private fun decrypt(enc: String): String {
        val all = Base64.getDecoder().decode(enc)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, all, 0, 12)) }
        return String(c.doFinal(all, 12, all.size - 12))
    }
}
