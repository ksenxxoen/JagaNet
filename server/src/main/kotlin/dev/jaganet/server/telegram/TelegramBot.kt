package dev.jaganet.server.telegram

import dev.jaganet.api.AccessKey
import dev.jaganet.api.Format
import dev.jaganet.api.PlanId
import dev.jaganet.api.Tariff
import dev.jaganet.api.ReferralPeriod
import dev.jaganet.api.i18n.I18n
import dev.jaganet.api.i18n.Lang
import dev.jaganet.server.AppError
import dev.jaganet.server.http.Services
import dev.jaganet.server.http.translate
import dev.jaganet.server.services.Channel
import dev.jaganet.server.services.Crypto
import dev.jaganet.server.services.Keys
import dev.jaganet.server.services.Order
import dev.jaganet.server.services.PaidListener
import dev.jaganet.server.services.Referrals
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.time.Instant

/** One button: opens a URL, or sends callback [data] back to the bot. */
data class Btn(val text: String, val data: String? = null, val url: String? = null)

/** Who we are talking to: their account, chat and language. */
class Who(val userId: String, val chat: Long, val lang: Lang) {
    fun t(en: String, vararg args: Pair<String, Any?>) = I18n.tr(lang, en, *args)
    fun tp(n: Number, forms: String) = I18n.plural(lang, n.toLong(), forms)
}

/**
 * The Telegram bot: buy Pro, get the VPN key (QR code + .conf file + link), get the app.
 * Long polling, so it needs no public webhook. A Telegram user gets their own account
 * (placeholder email tg<id>@telegram.invalid); "Get the app" signs the app into it
 * with a one-time device code. Russian by default; /language switches.
 */
class TelegramBot(private val s: Services, private val api: TelegramApi) {
    private val log = LoggerFactory.getLogger("telegram")
    private val ctx = s.ctx
    @Volatile var username: String? = null
        private set
    /** Last time Telegram answered a poll (monitoring checks it). */
    @Volatile var lastPollOk: Instant? = null
        private set

    init {
        s.payments.listeners.add(PaidListener { order, key, until -> if (order.channel == Channel.TELEGRAM) onPaid(order, key, until) })
    }

    /** Runs until the coroutine is cancelled. */
    suspend fun run() {
        while (username == null) {
            runCatching { username = api.call("getMe", JsonObject(emptyMap())).jsonObject["username"]?.jsonPrimitive?.content }
                .onFailure { log.warn("Telegram getMe failed (wrong TELEGRAM_BOT_TOKEN?): ${it.message}"); delay(30_000) }
        }
        // Command menu: Russian for everyone, German and English for Telegram set to those languages.
        for (lang in Lang.entries) runCatching {
            api.call("setMyCommands", buildJsonObject {
                if (lang != Lang.DEFAULT) put("language_code", lang.code)
                putJsonArray("commands") {
                    for ((c, d) in COMMANDS) addJsonObject { put("command", c); put("description", I18n.tr(lang, d)) }
                }
            })
        }
        log.info("Telegram bot @$username is running")
        var offset = 0L
        while (true) {
            val updates = try {
                api.call("getUpdates", buildJsonObject {
                    put("offset", offset); put("timeout", 50)
                    putJsonArray("allowed_updates") { add("message"); add("callback_query") }
                }).jsonArray.also { lastPollOk = ctx.now() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("getUpdates: ${e.message}"); delay(5_000); continue
            }
            for (u in updates) {
                offset = u.jsonObject["update_id"]!!.jsonPrimitive.long + 1
                runCatching { handle(u.jsonObject) }.onFailure { log.error("update failed", it) }
            }
        }
    }

    suspend fun handle(update: JsonObject) {
        update["message"]?.jsonObject?.let { m ->
            val from = m["from"]?.jsonObject ?: return
            val chat = m["chat"]!!.jsonObject["id"]!!.jsonPrimitive.long
            val text = m["text"]?.jsonPrimitive?.content?.trim().orEmpty()
            val cmd = text.substringBefore(' ').substringBefore('@').lowercase()
            val startCode = text.substringAfter(' ', "").trim().takeIf { cmd == "/start" && it.isNotBlank() }
            // A referral link opened in Telegram (t.me/<bot>?start=<code>) counts as a click.
            if (startCode != null) s.referrals.click(startCode, "tg:" + from["id"]!!.jsonPrimitive.long, "telegram", null)
            val who = who(from, chat, referral = startCode)
            when (cmd) {
                "/key" -> keyAction(who)
                "/app" -> appAction(who)
                "/status" -> statusAction(who)
                "/invite" -> inviteAction(who)
                "/login" -> loginAction(who)
                "/myid" -> send(who, who.t("This chat's id is {id}. Put it in ALERT_TELEGRAM_CHAT_IDS to get server alerts here.", "id" to chat))
                "/language" -> languageAction(who)
                else -> menu(who)
            }
        }
        update["callback_query"]?.jsonObject?.let { q ->
            runCatching { api.call("answerCallbackQuery", buildJsonObject { put("callback_query_id", q["id"]!!.jsonPrimitive.content) }) }
            val from = q["from"]!!.jsonObject
            val chat = q["message"]?.jsonObject?.get("chat")?.jsonObject?.get("id")?.jsonPrimitive?.long ?: from["id"]!!.jsonPrimitive.long
            var who = who(from, chat)
            val data = q["data"]?.jsonPrimitive?.content.orEmpty()
            when {
                data == "key" -> keyAction(who)
                data == "app" -> appAction(who)
                data == "status" -> statusAction(who)
                data == "invite" -> inviteAction(who)
                data == "login" -> loginAction(who)
                data == "language" -> languageAction(who)
                data.startsWith("lang:") -> {
                    val lang = Lang.of(data.removePrefix("lang:")) ?: Lang.DEFAULT
                    ctx.db.run { it.exec("UPDATE users SET lang=? WHERE id=?::uuid", lang.code, who.userId) }
                    who = Who(who.userId, chat, lang)
                    menu(who)
                }
                data.startsWith("buy:") -> buyAction(who, data.removePrefix("buy:"))
                else -> menu(who)
            }
        }
    }

    /* ---------------- actions ---------------- */

    private suspend fun menu(u: Who) {
        val pro = isPro(u.userId)
        val tariffs = s.tariffs.onSale(u.lang)
        val text = buildString {
            appendLine(u.t("JagaNet VPN is fast and secure. It encrypts all your traffic."))
            appendLine()
            append(u.t("It works in the JagaNet app and in other VPN apps."))
            for (t in tariffs) { appendLine(); appendLine(); append("${t.name}, ${period(t, u.lang)}, ${Format.terms(t.deviceLimit, t.monthlyDataLimitBytes, u.lang)}") }
            if (pro != null) { appendLine(); appendLine(); append(u.t("✅ Your Pro is active until {date}.", "date" to Format.date(pro.toString(), u.lang))) }
        }
        send(u, text, buyButtons(u, tariffs) + listOf(listOf(Btn(u.t("🔑 My VPN key"), "key"), Btn(u.t("📱 Get the app"), "app")), listOf(Btn(u.t("🎁 Invite friends"), "invite"), Btn("🌐 " + u.lang.nativeName, "language"))))
    }

    private suspend fun languageAction(u: Who) =
        send(u, u.t("Choose a language"), listOf(Lang.entries.map { Btn((if (it == u.lang) "✓ " else "") + it.nativeName, "lang:${it.code}") }))

    private suspend fun buyAction(u: Who, tariffId: String) {
        val tariff = s.tariffs.onSale(u.lang).firstOrNull { it.id == tariffId }
            ?: return send(u, translate(u.lang, "This plan is no longer on sale"), buyButtons(u))
        val order = try {
            s.payments.create(u.userId, tariff.id, Channel.TELEGRAM, u.lang)
        } catch (e: AppError) {
            return send(u, u.t("Sorry, buying isn't available right now.") + "\n" + translate(u.lang, e.message ?: "", e.args))
        }
        val price = Format.money(tariff.priceMinor, tariff.currency, u.lang)
        val text = buildString {
            appendLine(u.t("{product} for {price}", "product" to tariff.name, "price" to price))
            appendLine("${period(tariff, u.lang)}, ${Format.terms(tariff.deviceLimit, tariff.monthlyDataLimitBytes, u.lang)}")
            append(u.t("Tap the button to pay. Your VPN key arrives here right after."))
        }
        val url = order.checkoutUrl?.let { withLang(it, u.lang) }
        send(u, text, listOf(listOf(Btn(u.t("Pay {price}", "price" to price), url = url))))
    }

    private suspend fun keyAction(u: Who) {
        val existing = s.keys.list(u.userId).firstOrNull()
        if (existing == null && isPro(u.userId) == null) {
            return send(u, u.t("You don't have an active subscription yet. Choose a plan."), buyButtons(u))
        }
        val key = existing ?: try {
            s.keys.create(u.userId)
        } catch (e: AppError) {
            return send(u, u.t("Couldn't make a key.") + "\n" + translate(u.lang, e.message ?: "", e.args))
        }
        sendKey(u, key, u.t("🔑 Your VPN key"))
    }

    private suspend fun appAction(u: Who) {
        val pair = s.auth.createPairingCodeFor(u.userId)
        val links = buildList {
            s.site.androidAppUrl()?.let { add(listOf(Btn(u.t("JagaNet for Android"), url = it))) }
            s.site.iosAppUrl()?.let { add(listOf(Btn(u.t("JagaNet for iPhone"), url = it))) }
            s.site.otherApps.forEach { (n, url) -> add(listOf(Btn(n, url = url))) }
        }
        send(
            u,
            buildString {
                appendLine(u.t("📱 Install the JagaNet app, tap Sign in with a device code and enter this code"))
                appendLine()
                appendLine(pair.code)
                appendLine()
                appendLine(u.t("The code works for 10 minutes. The app uses this same subscription."))
                appendLine()
                append(u.t("Prefer another app? AmneziaVPN works with your key from /key."))
            },
            links,
        )
    }

    /** A one-time link that signs this Telegram account in on the website (no e-mail needed). */
    private suspend fun loginAction(u: Who) {
        val link = s.auth.createLoginLink(u.userId).replace("/#/", "/?lang=${u.lang.code}#/")
        send(u, u.t("Open this link to sign in on the website. It works once, within 15 minutes."), listOf(listOf(Btn(u.t("Sign in on the website"), url = link))))
    }

    /** The user's main referral link and a 30-day summary; details are on the website. */
    private suspend fun inviteAction(u: Who) {
        val st = s.referrals.stats(u.userId, ReferralPeriod.D30)
        val main = st.links.first { it.main }
        val f = st.totals
        val text = buildString {
            appendLine(u.t("Your link"))
            appendLine(main.webUrl)
            main.telegramUrl?.let { appendLine(u.t("Link to this bot")); appendLine(it) }
            appendLine()
            appendLine(u.t("Last 30 days"))
            appendLine(u.t("Clicks {n}", "n" to f.clicks))
            appendLine(u.t("Sign-ups {n}", "n" to f.signups))
            appendLine(u.t("Paid {n}", "n" to f.paidUsers))
            append(u.t("Detailed statistics and extra links for each campaign are in your account on the website."))
        }
        send(u, text, listOf(listOf(Btn(u.t("Open statistics"), url = "${ctx.cfg.publicUrl.trimEnd('/')}/?lang=${u.lang.code}#/referrals"))))
    }

    private suspend fun statusAction(u: Who) {
        val pro = isPro(u.userId)
        val keys = s.keys.list(u.userId).size
        if (pro == null) return send(u, u.t("No active subscription."), buyButtons(u))
        send(
            u,
            u.t("✅ Pro until {date}.", "date" to Format.date(pro.toString(), u.lang)) + "\n" + u.tp(keys, "{n} VPN key|{n} VPN keys"),
            listOf(listOf(Btn(u.t("🔑 My VPN key"), "key"), Btn(u.t("Extend"), "menu"))),
        )
    }

    private suspend fun onPaid(order: Order, key: AccessKey?, until: Instant) {
        val row = ctx.db.run { it.one("SELECT telegram_id, lang FROM users WHERE id=?::uuid", order.userId) } ?: return
        val chat = row.longOrNull("telegram_id") ?: return
        val u = Who(order.userId, chat, Lang.of(row.strOrNull("lang")) ?: Lang.DEFAULT)
        val head = u.t("✅ Payment received. Pro is active until {date}.", "date" to Format.date(until.toString(), u.lang))
        if (key == null) return send(u, head + "\n\n" + u.t("Tap /key to get your VPN key."))
        sendKey(u, key, head)
    }

    /** QR code photo with setup steps, then the .conf file. */
    private suspend fun sendKey(u: Who, key: AccessKey, head: String) {
        val cfg = s.keys.config(u.userId, key.id) ?: return send(u, u.t("That key was deleted. Tap /key for a new one."))
        val caption = buildString {
            appendLine(head)
            appendLine()
            appendLine(u.t("How to connect"))
            appendLine(u.t("1. Install AmneziaVPN (button below) or our JagaNet app."))
            appendLine(u.t("2. Scan this QR code in the app from another screen or import the {file} file below.", "file" to Keys.CONFIG_FILE))
            appendLine(u.t("3. Connect."))
            appendLine()
            append(u.t("Keep the key private. Anyone who has it uses your subscription."))
        }
        val buttons = buildList {
            add(listOf(Btn(u.t("Open key page"), url = withLang(key.pageUrl, u.lang))))
            s.site.otherApps.forEach { (n, url) -> add(listOf(Btn(n, url = url))) }
            s.site.androidAppUrl()?.let { add(listOf(Btn(u.t("JagaNet for Android"), url = it))) }
        }
        api.call("sendPhoto", buildJsonObject { put("chat_id", u.chat); put("caption", caption); put("reply_markup", keyboard(buttons)) },
            Upload("photo", "jaganet-key.png", "image/png", Keys.qrPng(cfg.text)))
        api.call("sendDocument", buildJsonObject { put("chat_id", u.chat) },
            Upload("document", Keys.CONFIG_FILE, "application/octet-stream", cfg.text.toByteArray()))
    }

    /* ---------------- helpers ---------------- */

    private fun period(t: Tariff, lang: Lang) = Format.period(t.durationValue, t.durationUnit, lang)

    private suspend fun buyButtons(u: Who, tariffs: List<Tariff>? = null): List<List<Btn>> = (tariffs ?: s.tariffs.onSale(u.lang)).map { t ->
        listOf(Btn(u.t("{product} for {price}", "product" to t.name, "price" to Format.money(t.priceMinor, t.currency, u.lang)), data = "buy:${t.id}"))
    }

    /** Pro expiry, or null on the free plan. */
    private suspend fun isPro(userId: String): Instant? = ctx.db.run { sql ->
        s.ent.entitlement(sql, userId).takeIf { it.plan == PlanId.PRO }?.let { e -> e.expiresAt?.let(Instant::parse) ?: Instant.parse("2999-12-31T00:00:00Z") }
    }

    /** The account for this Telegram user (made on first contact) and their language. */
    private suspend fun who(from: JsonObject, chat: Long, referral: String? = null): Who {
        val tgId = from["id"]!!.jsonPrimitive.long
        val row = ctx.db.tx { sql ->
            sql.one("SELECT id, lang FROM users WHERE telegram_id=?", tgId) ?: run {
                val email = "tg$tgId@telegram.invalid"
                val referrer = Referrals.resolve(sql, referral)
                sql.one(
                    """INSERT INTO users (email, referral_code, referred_by, referral_link_id, telegram_id) VALUES (?,?,?::uuid,?::uuid,?)
                       ON CONFLICT (email) DO UPDATE SET telegram_id=EXCLUDED.telegram_id RETURNING id, lang""",
                    email, Crypto.referralCode("tg@x"), referrer?.userId, referrer?.linkId, tgId,
                )!!
            }
        }
        return Who(row.str("id"), chat, Lang.of(row.strOrNull("lang")) ?: Lang.DEFAULT)
    }

    private fun withLang(url: String, lang: Lang) = url + (if ('?' in url) "&" else "?") + "lang=" + lang.code

    private fun keyboard(rows: List<List<Btn>>) = buildJsonObject {
        put("inline_keyboard", JsonArray(rows.map { row ->
            buildJsonArray {
                for (b in row) addJsonObject {
                    put("text", b.text)
                    if (b.url != null) put("url", b.url) else put("callback_data", b.data ?: "menu")
                }
            }
        }))
    }

    /** Plain message to a chat (server alerts). */
    suspend fun sendText(chat: Long, text: String) {
        api.call("sendMessage", buildJsonObject { put("chat_id", chat); put("text", text); put("link_preview_options", buildJsonObject { put("is_disabled", true) }) })
    }

    private suspend fun send(u: Who, text: String, buttons: List<List<Btn>> = emptyList()) {
        api.call("sendMessage", buildJsonObject {
            put("chat_id", u.chat); put("text", text)
            put("link_preview_options", buildJsonObject { put("is_disabled", true) })
            if (buttons.isNotEmpty()) put("reply_markup", keyboard(buttons))
        })
    }

    private companion object {
        val COMMANDS = listOf(
            "start" to "Plans and menu",
            "key" to "My VPN key",
            "app" to "Get the app",
            "status" to "My subscription",
            "invite" to "Invite friends",
            "login" to "Sign in on the website",
            "language" to "Language",
        )
    }
}
