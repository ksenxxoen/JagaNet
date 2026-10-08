package dev.jaganet.server.telegram

import dev.jaganet.api.AccessKey
import dev.jaganet.api.Format
import dev.jaganet.api.PlanId
import dev.jaganet.api.ProductId
import dev.jaganet.api.i18n.I18n
import dev.jaganet.api.i18n.Lang
import dev.jaganet.server.AppError
import dev.jaganet.server.http.Services
import dev.jaganet.server.http.productName
import dev.jaganet.server.http.translate
import dev.jaganet.server.services.Channel
import dev.jaganet.server.services.Crypto
import dev.jaganet.server.services.Keys
import dev.jaganet.server.services.Order
import dev.jaganet.server.services.PaidListener
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
                }).jsonArray
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
            val who = who(from, chat, referral = text.substringAfter(' ', "").takeIf { cmd == "/start" && it.isNotBlank() })
            when (cmd) {
                "/key" -> keyAction(who)
                "/app" -> appAction(who)
                "/status" -> statusAction(who)
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
        val text = buildString {
            appendLine(u.t("JagaNet VPN is fast and secure. It encrypts all your traffic and never logs what you browse."))
            appendLine()
            appendLine(u.t("Pro gives unlimited data and up to {n} devices. It works in the JagaNet app and in other VPN apps.", "n" to s.billing.plans().pro.deviceLimit))
            if (pro != null) { appendLine(); append(u.t("✅ Your Pro is active until {date}.", "date" to Format.date(pro.toString(), u.lang))) }
        }
        send(u, text, buyButtons(u) + listOf(listOf(Btn(u.t("🔑 My VPN key"), "key"), Btn(u.t("📱 Get the app"), "app")), listOf(Btn("🌐 " + u.lang.nativeName, "language"))))
    }

    private suspend fun languageAction(u: Who) =
        send(u, u.t("Choose a language"), listOf(Lang.entries.map { Btn((if (it == u.lang) "✓ " else "") + it.nativeName, "lang:${it.code}") }))

    private suspend fun buyAction(u: Who, product: String) {
        val id = ProductId.entries.firstOrNull { it.name.equals(product, true) } ?: return menu(u)
        val order = try {
            s.payments.create(u.userId, id, Channel.TELEGRAM)
        } catch (e: AppError) {
            return send(u, u.t("Sorry, buying isn't available right now.") + "\n" + translate(u.lang, e.message ?: "", e.args))
        }
        val price = Format.money(price(id) ?: 0, ctx.cfg.plans.currency, u.lang)
        val text = buildString {
            appendLine(u.t("{product} for {price}", "product" to productName(id, u.lang), "price" to price))
            append(u.t("Tap the button to pay. Your VPN key arrives here right after."))
            if (s.payments.isTest) { appendLine(); appendLine(); append(u.t("Test mode, no real money is taken.")) }
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
                append(u.t("Prefer another app? AmneziaVPN and AmneziaWG work with your key from /key."))
            },
            links,
        )
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
            appendLine(u.t("1. Install AmneziaVPN or AmneziaWG (buttons below) or our JagaNet app."))
            appendLine(u.t("2. In the app tap + and choose Scan QR code (scan this picture from another screen) or import the {file} file below.", "file" to Keys.CONFIG_FILE))
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

    private fun price(id: ProductId) = when (id) { ProductId.PRO_MONTHLY -> ctx.cfg.plans.priceMonthlyMinor; ProductId.PRO_YEARLY -> ctx.cfg.plans.priceYearlyMinor }

    private fun buyButtons(u: Who): List<List<Btn>> = ProductId.entries.sortedByDescending { it.periodDays }.mapNotNull { id ->
        price(id)?.let { listOf(Btn(u.t("{product} for {price}", "product" to productName(id, u.lang), "price" to Format.money(it, ctx.cfg.plans.currency, u.lang)), data = "buy:${id.name.lowercase()}")) }
    }

    /** Pro expiry, or null on the free plan. */
    private suspend fun isPro(userId: String): Instant? = ctx.db.run { sql ->
        s.ent.entitlement(sql, userId).takeIf { it.plan == PlanId.PRO }?.expiresAt?.let(Instant::parse)
    }

    /** The account for this Telegram user (made on first contact) and their language. */
    private suspend fun who(from: JsonObject, chat: Long, referral: String? = null): Who {
        val tgId = from["id"]!!.jsonPrimitive.long
        val row = ctx.db.tx { sql ->
            sql.one("SELECT id, lang FROM users WHERE telegram_id=?", tgId) ?: run {
                val email = "tg$tgId@telegram.invalid"
                val referrer = referral?.uppercase()?.let { sql.one("SELECT id FROM users WHERE referral_code=?", it) }?.str("id")
                sql.one(
                    """INSERT INTO users (email, referral_code, referred_by, telegram_id) VALUES (?,?,?::uuid,?)
                       ON CONFLICT (email) DO UPDATE SET telegram_id=EXCLUDED.telegram_id RETURNING id, lang""",
                    email, Crypto.referralCode("tg@x"), referrer, tgId,
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
            "language" to "Language",
        )
    }
}
