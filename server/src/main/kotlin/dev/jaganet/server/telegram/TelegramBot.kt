package dev.jaganet.server.telegram

import dev.jaganet.api.AccessKey
import dev.jaganet.api.Format
import dev.jaganet.api.PlanId
import dev.jaganet.api.ProductId
import dev.jaganet.server.AppError
import dev.jaganet.server.http.Services
import dev.jaganet.server.http.productName
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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.time.Instant

/** One button: opens a URL, or sends callback [data] back to the bot. */
data class Btn(val text: String, val data: String? = null, val url: String? = null)

/**
 * The Telegram bot: buy Pro, get the VPN key (QR code + .conf file + link), get the app.
 * Long polling, so it needs no public webhook. A Telegram user gets their own account
 * (placeholder email tg<id>@telegram.invalid); "Get the app" signs the app into it
 * with a one-time device code.
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
        runCatching {
            api.call("setMyCommands", buildJsonObject {
                putJsonArray("commands") {
                    for ((c, d) in listOf("start" to "Plans and menu", "key" to "My VPN key", "app" to "Get the app", "status" to "My subscription")) {
                        addJsonObject { put("command", c); put("description", d) }
                    }
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
            val userId = userFor(from, referral = text.substringAfter(' ', "").takeIf { cmd == "/start" && it.isNotBlank() })
            when (cmd) {
                "/key" -> keyAction(chat, userId)
                "/app" -> appAction(chat, userId)
                "/status" -> statusAction(chat, userId)
                else -> menu(chat, userId)
            }
        }
        update["callback_query"]?.jsonObject?.let { q ->
            runCatching { api.call("answerCallbackQuery", buildJsonObject { put("callback_query_id", q["id"]!!.jsonPrimitive.content) }) }
            val from = q["from"]!!.jsonObject
            val chat = q["message"]?.jsonObject?.get("chat")?.jsonObject?.get("id")?.jsonPrimitive?.long ?: from["id"]!!.jsonPrimitive.long
            val userId = userFor(from)
            when (val data = q["data"]?.jsonPrimitive?.content.orEmpty()) {
                "key" -> keyAction(chat, userId)
                "app" -> appAction(chat, userId)
                "status" -> statusAction(chat, userId)
                "menu" -> menu(chat, userId)
                else -> if (data.startsWith("buy:")) buyAction(chat, userId, data.removePrefix("buy:")) else menu(chat, userId)
            }
        }
    }

    /* ---------------- actions ---------------- */

    private suspend fun menu(chat: Long, userId: String) {
        val plans = s.billing.plans()
        val pro = isPro(userId)
        val text = buildString {
            appendLine("JagaNet VPN — fast, private, and works where other VPNs are blocked (AmneziaWG).")
            appendLine()
            appendLine("Pro: unlimited data, up to ${plans.pro.deviceLimit} devices, works in the JagaNet app and in AmneziaVPN / AmneziaWG.")
            if (pro != null) { appendLine(); append("✅ Your Pro is active until ${Format.date(pro.toString())}.") }
        }
        val buy = plans.products.filter { price(it.id) != null }.map { Btn("${productName(it.id)} — ${it.displayPrice.substringBefore('/')}", data = "buy:${it.id.name.lowercase()}") }
        send(chat, text, buy.map { listOf(it) } + listOf(listOf(Btn("🔑 My VPN key", "key"), Btn("📱 Get the app", "app"))))
    }

    private suspend fun buyAction(chat: Long, userId: String, product: String) {
        val id = ProductId.entries.firstOrNull { it.name.equals(product, true) } ?: return menu(chat, userId)
        val order = try {
            s.payments.create(userId, id, Channel.TELEGRAM)
        } catch (e: AppError) {
            return send(chat, "Sorry, buying isn't available right now: ${e.message}")
        }
        val test = if (s.payments.isTest) "\n\n(Test mode: no real money is taken.)" else ""
        send(chat, "${productName(id)} — ${order.amount}.\nTap the button to pay. Your VPN key arrives here right after.$test",
            listOf(listOf(Btn("Pay ${order.amount}", url = order.checkoutUrl))))
    }

    private suspend fun keyAction(chat: Long, userId: String) {
        val existing = s.keys.list(userId).firstOrNull()
        if (existing == null && isPro(userId) == null) {
            return send(chat, "You don't have an active subscription yet. Choose a plan:", buyButtons())
        }
        val key = existing ?: try {
            s.keys.create(userId)
        } catch (e: AppError) {
            return send(chat, "Couldn't make a key: ${e.message}")
        }
        sendKey(chat, userId, key, "🔑 Your VPN key")
    }

    private suspend fun appAction(chat: Long, userId: String) {
        val pair = s.auth.createPairingCodeFor(userId)
        val links = buildList {
            s.site.androidAppUrl()?.let { add(listOf(Btn("JagaNet for Android", url = it))) }
            s.site.iosAppUrl()?.let { add(listOf(Btn("JagaNet for iPhone", url = it))) }
            s.site.otherApps.forEach { (n, u) -> add(listOf(Btn(n, url = u))) }
        }
        send(
            chat,
            buildString {
                appendLine("📱 The JagaNet app: install it, tap \"Sign in with a device code\" and enter")
                appendLine()
                appendLine("${pair.code}")
                appendLine()
                appendLine("(valid for 10 minutes). It uses this same subscription.")
                appendLine()
                append("Prefer another app? AmneziaVPN and AmneziaWG work with your key from /key.")
            },
            links,
        )
    }

    private suspend fun statusAction(chat: Long, userId: String) {
        val pro = isPro(userId)
        val keys = s.keys.list(userId).size
        send(
            chat,
            if (pro != null) "✅ Pro until ${Format.date(pro.toString())}.\nVPN keys: $keys." else "No active subscription.",
            if (pro != null) listOf(listOf(Btn("🔑 My VPN key", "key"), Btn("Extend", "menu"))) else buyButtons(),
        )
    }

    private suspend fun onPaid(order: Order, key: AccessKey?, until: Instant) {
        val chat = ctx.db.run { it.one("SELECT telegram_id FROM users WHERE id=?::uuid", order.userId) }?.longOrNull("telegram_id") ?: return
        val head = "✅ Payment received. Pro is active until ${Format.date(until.toString())}."
        if (key == null) return send(chat, "$head\n\nTap /key to get your VPN key.")
        sendKey(chat, order.userId, key, head)
    }

    /** QR code photo with setup steps, then the .conf file. */
    private suspend fun sendKey(chat: Long, userId: String, key: AccessKey, head: String) {
        val cfg = s.keys.config(userId, key.id) ?: return send(chat, "That key was deleted. Tap /key for a new one.")
        val caption = buildString {
            appendLine(head)
            appendLine()
            appendLine("How to connect:")
            appendLine("1. Install AmneziaVPN or AmneziaWG (buttons below), or our JagaNet app.")
            appendLine("2. In the app tap + → Scan QR code (scan this picture from another screen), or import the ${Keys.CONFIG_FILE} file below.")
            appendLine("3. Connect.")
            appendLine()
            append("Keep the key private: anyone who has it uses your subscription.")
        }
        val buttons = buildList {
            add(listOf(Btn("Open key page", url = key.pageUrl)))
            s.site.otherApps.forEach { (n, u) -> add(listOf(Btn(n, url = u))) }
            s.site.androidAppUrl()?.let { add(listOf(Btn("JagaNet for Android", url = it))) }
        }
        api.call("sendPhoto", buildJsonObject { put("chat_id", chat); put("caption", caption); put("reply_markup", keyboard(buttons)) },
            Upload("photo", "jaganet-key.png", "image/png", Keys.qrPng(cfg.text)))
        api.call("sendDocument", buildJsonObject { put("chat_id", chat) },
            Upload("document", Keys.CONFIG_FILE, "application/octet-stream", cfg.text.toByteArray()))
    }

    /* ---------------- helpers ---------------- */

    private fun price(id: ProductId) = when (id) { ProductId.PRO_MONTHLY -> ctx.cfg.plans.priceMonthlyMinor; ProductId.PRO_YEARLY -> ctx.cfg.plans.priceYearlyMinor }

    private fun buyButtons(): List<List<Btn>> = s.billing.plans().products.filter { price(it.id) != null }
        .map { listOf(Btn("${productName(it.id)} — ${it.displayPrice.substringBefore('/')}", data = "buy:${it.id.name.lowercase()}")) }

    /** Pro expiry, or null on the free plan. */
    private suspend fun isPro(userId: String): Instant? = ctx.db.run { sql ->
        s.ent.entitlement(sql, userId).takeIf { it.plan == PlanId.PRO }?.expiresAt?.let(Instant::parse)
    }

    /** The account for this Telegram user, made on first contact. */
    private suspend fun userFor(from: JsonObject, referral: String? = null): String {
        val tgId = from["id"]!!.jsonPrimitive.long
        return ctx.db.tx { sql ->
            sql.one("SELECT id FROM users WHERE telegram_id=?", tgId)?.str("id") ?: run {
                val email = "tg$tgId@telegram.invalid"
                val referrer = referral?.uppercase()?.let { sql.one("SELECT id FROM users WHERE referral_code=?", it) }?.str("id")
                sql.one(
                    """INSERT INTO users (email, referral_code, referred_by, telegram_id) VALUES (?,?,?::uuid,?)
                       ON CONFLICT (email) DO UPDATE SET telegram_id=EXCLUDED.telegram_id RETURNING id""",
                    email, Crypto.referralCode("tg@x"), referrer, tgId,
                )!!.str("id")
            }
        }
    }

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

    private suspend fun send(chat: Long, text: String, buttons: List<List<Btn>> = emptyList()) {
        api.call("sendMessage", buildJsonObject {
            put("chat_id", chat); put("text", text)
            put("link_preview_options", buildJsonObject { put("is_disabled", true) })
            if (buttons.isNotEmpty()) put("reply_markup", keyboard(buttons))
        })
    }
}
