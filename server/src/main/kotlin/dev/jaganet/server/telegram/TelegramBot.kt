package dev.jaganet.server.telegram

import dev.jaganet.api.AccessKey
import dev.jaganet.api.Format
import dev.jaganet.api.PlanId
import dev.jaganet.api.ReferralPeriod
import dev.jaganet.api.Tariff
import dev.jaganet.api.i18n.I18n
import dev.jaganet.api.i18n.Lang
import dev.jaganet.server.AppError
import dev.jaganet.server.http.Services
import dev.jaganet.server.http.keyName
import dev.jaganet.server.http.translate
import dev.jaganet.server.services.Channel
import dev.jaganet.server.services.Keys
import dev.jaganet.server.services.Order
import dev.jaganet.server.services.PaidListener
import dev.jaganet.server.services.VlessLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** One button: opens a URL, or sends callback [data] back to the bot. */
data class Btn(val text: String, val data: String? = null, val url: String? = null)

/** Who we are talking to: their account, chat and language. */
class Who(val userId: String, val chat: Long, val lang: Lang, val tgId: Long = chat) {
    fun t(en: String, vararg args: Pair<String, Any?>) = I18n.tr(lang, en, *args)
    fun tp(n: Number, forms: String, vararg args: Pair<String, Any?>) = I18n.plural(lang, n.toLong(), forms, *args)
}

/**
 * The Telegram bot (issue #38): everything without the website. Sign-up on /start, buying and
 * renewing through the site's payment service, the subscription link and QR code, devices,
 * reminders before the subscription ends, support chat, sign-in on the website.
 *
 * Transport (long polling or webhook) lives in TelegramRuntime.kt; this class only answers updates,
 * so tests drive it with a fake [TelegramApi]. Every Telegram call may fail (Telegram unreachable):
 * failures stay inside the bot and never reach the panel's own requests. [scope] runs work that
 * other parts of the panel trigger (a payment) in the background; null = inline (tests).
 */
class TelegramBot(private val s: Services, private val api: TelegramApi, private val scope: CoroutineScope? = null) {
    private val log = LoggerFactory.getLogger("telegram")
    private val ctx = s.ctx
    val accounts = TelegramAccounts(ctx, s.auth, s.tunnels)
    @Volatile var username: String? = null
        internal set
    /** Last time Telegram answered (monitoring checks it). */
    @Volatile var lastPollOk: Instant? = null
        internal set

    /** Telegram users who pressed Support: their next messages go to the support chat, until this time. */
    private val supportMode = ConcurrentHashMap<Long, Instant>()

    init {
        s.payments.listeners.add(PaidListener { order, key, until -> background { onPaid(order, key, until) } })
    }

    private suspend fun background(block: suspend () -> Unit) {
        val sc = scope
        if (sc == null) {
            runCatching { block() }.onFailure { log.warn("telegram: ${it.message}") }
            return
        }
        sc.launch { runCatching { block() }.onFailure { log.warn("telegram: ${it.message}") } }
    }

    /** getMe and the command menu. Throws when Telegram can't be reached (the runtime retries). */
    suspend fun prepare() {
        username = api.call("getMe", JsonObject(emptyMap())).jsonObject["username"]?.jsonPrimitive?.content
        lastPollOk = ctx.now()
        // Command menu: Russian for everyone, German and English for Telegram set to those languages.
        for (lang in Lang.entries) runCatching {
            api.call("setMyCommands", buildJsonObject {
                if (lang != Lang.DEFAULT) put("language_code", lang.code)
                putJsonArray("commands") {
                    for ((c, d) in COMMANDS) addJsonObject { put("command", c); put("description", I18n.tr(lang, d)) }
                }
            })
        }
    }

    /** The support chat: TELEGRAM_SUPPORT_CHAT_ID, else the first alert chat. */
    private fun supportChat(): Long? = ctx.cfg.telegram.supportChatId ?: ctx.live.alertTelegramChats.firstOrNull()

    suspend fun handle(update: JsonObject) {
        lastPollOk = ctx.now()
        update["message"]?.jsonObject?.let { onMessage(it) }
        update["callback_query"]?.jsonObject?.let { onCallback(it) }
    }

    /* ---------------- messages ---------------- */

    private suspend fun onMessage(m: JsonObject) {
        val from = m["from"]?.jsonObject ?: return
        if (from["is_bot"]?.jsonPrimitive?.content == "true") return
        val chatObj = m["chat"]!!.jsonObject
        val chat = chatObj["id"]!!.jsonPrimitive.long
        val private = (chatObj["type"]?.jsonPrimitive?.content ?: "private") == "private"
        val text = (m["text"] ?: m["caption"])?.jsonPrimitive?.content?.trim().orEmpty()
        val cmd = text.substringBefore(' ').substringBefore('@').lowercase().takeIf { it.startsWith("/") }
        val arg = text.substringAfter(' ', "").trim()

        // The support chat: a reply to a forwarded message goes back to that user.
        if (chat == supportChat() && m["reply_to_message"] != null && adminReply(chat, m)) return
        if (cmd == "/myid") return sendText(chat, I18n.tr(Lang.DEFAULT, "This chat's id is {id}. Put it in ALERT_TELEGRAM_CHAT_IDS to get server alerts here.", "id" to chat))
        // Groups (the support chat, alert chats) are not customers.
        if (!private) return

        val tg = tgUser(from)
        if (cmd == "/start" && arg.startsWith(LINK_PREFIX)) return linkAction(tg, chat, arg.removePrefix(LINK_PREFIX))
        val ref = if (cmd == "/start") StartPayload.referral(arg) else null
        // A referral link opened in Telegram (t.me/<bot>?start=ref_<code>) counts as a click.
        if (ref != null) s.referrals.click(ref, "tg:${tg.id}", "telegram", null)
        val who = who(tg, chat, ref)
        // Blocked by the admin (issue #39): only support is left, as on the site (every request gets 403).
        if (isBlocked(who) && !(cmd == "/support" || (cmd == null && (supportMode[tg.id] ?: Instant.MIN) > ctx.now()))) return blockedNotice(who)

        // Plain messages go to support after the Support button (for a while); "/support text" at once.
        if (cmd == null || (cmd == "/support" && arg.isNotEmpty())) {
            val until = supportMode[tg.id]
            if (cmd != null || (until != null && until > ctx.now())) return forwardToSupport(who, tg, m)
            return hint(who)
        }
        supportMode.remove(tg.id)
        when (cmd) {
            "/start" -> when (arg.lowercase()) {
                StartPayload.LOGIN -> loginAction(who)
                StartPayload.KEYS -> keysAction(who)
                StartPayload.PAY -> payAction(who)
                StartPayload.SUPPORT -> supportAction(who)
                else -> menu(who)
            }
            "/help" -> helpAction(who)
            "/keys", "/key" -> keysAction(who)
            "/pay", "/buy" -> payAction(who)
            "/devices" -> devicesAction(who)
            "/support" -> supportAction(who)
            "/status" -> statusAction(who)
            "/app" -> appAction(who)
            "/login" -> loginAction(who)
            "/invite" -> inviteAction(who)
            "/language" -> languageAction(who)
            "/notifications" -> notifyAction(who)
            else -> menu(who)
        }
    }

    private suspend fun onCallback(q: JsonObject) {
        runCatching { api.call("answerCallbackQuery", buildJsonObject { put("callback_query_id", q["id"]!!.jsonPrimitive.content) }) }
        val from = q["from"]!!.jsonObject
        val chat = q["message"]?.jsonObject?.get("chat")?.jsonObject?.get("id")?.jsonPrimitive?.long ?: from["id"]!!.jsonPrimitive.long
        val tg = tgUser(from)
        var who = who(tg, chat)
        val data = q["data"]?.jsonPrimitive?.content.orEmpty()
        if (isBlocked(who) && data != "support") return blockedNotice(who)
        if (data != "support") supportMode.remove(tg.id)
        when {
            data == "keys" || data == "key" -> keysAction(who)
            data == "pay" -> payAction(who)
            data == "devices" -> devicesAction(who)
            data == "notify" -> notifyAction(who)
            data == "notify:on" || data == "notify:off" -> {
                ctx.db.run { it.exec("UPDATE users SET tg_notify=? WHERE id=?::uuid", data == "notify:on", who.userId) }
                notifyAction(who)
            }
            data == "support" -> supportAction(who)
            data == "help" -> helpAction(who)
            data == "app" -> appAction(who)
            data == "status" -> statusAction(who)
            data == "invite" -> inviteAction(who)
            data == "login" -> loginAction(who)
            data == "language" -> languageAction(who)
            data.startsWith("lang:") -> {
                val lang = Lang.of(data.removePrefix("lang:")) ?: Lang.DEFAULT
                ctx.db.run { it.exec("UPDATE users SET lang=? WHERE id=?::uuid", lang.code, who.userId) }
                who = Who(who.userId, chat, lang, tg.id)
                menu(who)
            }
            data.startsWith("buy:") -> buyAction(who, data.removePrefix("buy:"))
            data.startsWith("del:") -> deleteAsk(who, data.removePrefix("del:"))
            data.startsWith("delok:") -> deleteDevice(who, data.removePrefix("delok:"))
            else -> menu(who)
        }
    }

    /* ---------------- menu ---------------- */

    private suspend fun menu(u: Who, head: String? = null) {
        val until = proUntil(u.userId)
        val text = buildString {
            head?.let { appendLine(it); appendLine() }
            appendLine(u.t("Boop VPN is fast and secure. It encrypts all your traffic."))
            appendLine()
            append(if (until != null) "✅ " + u.t("Subscription active until {date}.", "date" to Format.date(until.toString(), u.lang)) else u.t("No active subscription."))
        }
        send(u, text, mainButtons(u, until != null))
    }

    private suspend fun mainButtons(u: Who, active: Boolean): List<List<Btn>> = buildList {
        add(listOf(Btn("🔑 " + u.t("My keys and connection"), "keys")))
        add(listOf(Btn("💳 " + (if (active) u.t("Extend") else u.t("Buy a subscription")), "pay"), Btn("📱 " + u.t("Devices"), "devices")))
        add(listOf(Btn("🔔 " + u.t("Notifications"), "notify"), Btn("💬 " + u.t("Support"), "support")))
        add(listOf(Btn("🌐 " + u.t("Sign in on the website"), "login"), Btn("❓ " + u.t("Help"), "help")))
        val lang = Btn(u.t("Language") + " " + u.lang.nativeName, "language")
        // The referral program is for admins only (unless REFERRALS_PUBLIC=1).
        add(if (referralsFor(u.userId)) listOf(Btn(u.t("Invite friends"), "invite"), lang) else listOf(lang))
    }

    private fun backButtons(u: Who) = listOf(listOf(Btn("⬅️ " + u.t("Menu"), "menu")))

    private suspend fun isBlocked(u: Who): Boolean =
        ctx.db.run { it.one("SELECT blocked_at FROM users WHERE id=?::uuid", u.userId) }?.get("blocked_at") != null

    private suspend fun blockedNotice(u: Who) = send(
        u, u.t("This account is blocked. To find out why, write to support."),
        listOf(listOf(Btn("💬 " + u.t("Support"), "support"))),
    )

    private suspend fun hint(u: Who) = send(
        u, u.t("Choose what to do in the menu. To write to support, tap Support."),
        listOf(listOf(Btn("💬 " + u.t("Support"), "support"), Btn("⬅️ " + u.t("Menu"), "menu"))),
    )

    private suspend fun helpAction(u: Who) = send(
        u,
        buildString {
            appendLine(u.t("Commands"))
            appendLine("/start  " + u.t("Plans and menu"))
            appendLine("/keys  " + u.t("My keys and connection"))
            appendLine("/pay  " + u.t("Buy or extend the subscription"))
            appendLine("/devices  " + u.t("Devices"))
            appendLine("/support  " + u.t("Write to support"))
            appendLine("/login  " + u.t("Sign in on the website"))
            appendLine()
            append(u.t("How to connect is in My keys. Questions? Write to support, we answer here."))
        },
        backButtons(u),
    )

    private suspend fun languageAction(u: Who) =
        send(u, u.t("Choose a language"), listOf(Lang.entries.map { Btn(it.nativeName + (if (it == u.lang) " (" + u.t("current") + ")" else ""), "lang:${it.code}") }))

    /* ---------------- buying ---------------- */

    private suspend fun payAction(u: Who) {
        if (s.payments.provider == null) return send(u, translate(u.lang, "Payments are temporarily unavailable"), backButtons(u))
        val tariffs = s.tariffs.onSale(u.lang)
        if (tariffs.isEmpty()) return send(u, u.t("No plans on sale right now."), backButtons(u))
        val until = proUntil(u.userId)
        val text = buildString {
            appendLine(u.t("Choose a plan"))
            for (t in tariffs) { appendLine(); append("${t.name}, ${period(t, u.lang)}, ${Format.terms(t.deviceLimit, t.monthlyDataLimitBytes, u.lang)}") }
            if (until != null) { appendLine(); appendLine(); append(u.t("Your subscription is active until {date}. A new payment adds to it.", "date" to Format.date(until.toString(), u.lang))) }
        }
        send(u, text, buyButtons(u, tariffs) + backButtons(u))
    }

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
        send(u, text, listOf(listOf(Btn(u.t("Pay {price}", "price" to price), url = url))) + backButtons(u))
    }

    /** A paid order: the date, and for orders from the bot the keys right away. Runs in the background. */
    private suspend fun onPaid(order: Order, key: AccessKey?, until: Instant) {
        val row = ctx.db.run { it.one("SELECT telegram_id, lang, tg_notify FROM users WHERE id=?::uuid", order.userId) } ?: return
        val chat = row.longOrNull("telegram_id") ?: return
        val u = Who(order.userId, chat, Lang.of(row.strOrNull("lang")) ?: Lang.DEFAULT)
        val head = u.t("Payment received. Pro is active until {date}.", "date" to Format.date(until.toString(), u.lang))
        try {
            when {
                order.channel == Channel.TELEGRAM -> keysAction(u, head, key)
                row.bool("tg_notify") -> send(u, "✅ $head", listOf(listOf(Btn("🔑 " + u.t("My keys and connection"), "keys"))))
            }
        } catch (e: TelegramException) {
            if (e.chatGone) markBlocked(u.userId)
            throw e
        }
    }

    /* ---------------- keys ---------------- */

    /**
     * The subscription link (/k/<token>/vless) with its QR code and the key page (/k/<token>), with
     * short app instructions. No server or protocol names. Without VLESS nodes: the AmneziaWG key as before.
     */
    private suspend fun keysAction(u: Who, head: String? = null, paidKey: AccessKey? = null) {
        runCatching { s.vlessKeys.ensureFor(u.userId) }.onFailure { log.warn("VLESS key for ${u.userId}: ${it.message}") }
        val v = s.vlessKeys.links(u.userId)
        if (v != null && v.links.isNotEmpty()) return sendSubscription(u, v, head)
        val active = proUntil(u.userId) != null
        val existing = paidKey ?: s.keys.list(u.userId).firstOrNull()
        if (existing == null && !active) {
            val text = if (v != null) u.t("Your key is off until the subscription is renewed.") else u.t("You don't have an active subscription yet. Choose a plan.")
            return send(u, listOfNotNull(head, text).joinToString("\n\n"), buyButtons(u) + backButtons(u))
        }
        val key = existing ?: try {
            s.keys.create(u.userId)
        } catch (e: AppError) {
            return send(u, u.t("Couldn't make a key.") + "\n" + translate(u.lang, e.message ?: "", e.args), backButtons(u))
        }
        sendKey(u, key, head ?: u.t("Your VPN key"))
    }

    private suspend fun sendSubscription(u: Who, v: VlessLinks, head: String?) {
        val base = "${ctx.cfg.publicUrl.trimEnd('/')}/k/${v.token}"
        val sub = "$base/vless"
        api.call(
            "sendPhoto",
            buildJsonObject { put("chat_id", u.chat); put("caption", listOfNotNull(head, u.t("QR code of your subscription. Scan it in the app.")).joinToString("\n\n")) },
            Upload("photo", "boop-subscription.png", "image/png", Keys.qrPng(sub)),
        )
        val text = buildString {
            appendLine("🔑 " + u.t("Your subscription link"))
            appendLine(sub)
            appendLine()
            appendLine(u.t("Key page with QR code and copy buttons"))
            appendLine("$base?lang=${u.lang.code}")
            appendLine()
            appendLine(u.t("How to connect"))
            appendLine(u.t("1. Install Happ or v2RayTun (buttons below). Hiddify works too."))
            appendLine(u.t("2. Copy the subscription link and add it in the app with + and Import from clipboard, or scan the QR code."))
            appendLine(u.t("3. Connect."))
            appendLine()
            append(u.t("Keep the link private. Anyone who has it uses your subscription."))
        }
        val buttons = buildList {
            add(listOf(Btn("Happ", url = HAPP_URL), Btn("v2RayTun", url = V2RAYTUN_URL)))
            add(listOf(Btn("Hiddify", url = HIDDIFY_URL)))
            add(listOf(Btn(u.t("Open key page"), url = withLang(base, u.lang))))
            add(listOf(Btn("📱 " + u.t("Devices"), "devices"), Btn("⬅️ " + u.t("Menu"), "menu")))
        }
        send(u, text, buttons)
    }

    /** AmneziaWG key: QR code photo with setup steps, then the .conf file. */
    private suspend fun sendKey(u: Who, key: AccessKey, head: String) {
        val cfg = s.keys.config(u.userId, key.id) ?: return send(u, u.t("That key was deleted. Tap /key for a new one."))
        val caption = buildString {
            appendLine(head)
            appendLine()
            appendLine(u.t("How to connect"))
            appendLine(u.t("1. Install AmneziaVPN (button below) or our Boop app."))
            appendLine(u.t("2. Scan this QR code in the app from another screen or import the {file} file below.", "file" to Keys.CONFIG_FILE))
            appendLine(u.t("3. Connect."))
            appendLine()
            append(u.t("Keep the key private. Anyone who has it uses your subscription."))
        }
        val buttons = buildList {
            add(listOf(Btn(u.t("Open key page"), url = withLang(key.pageUrl, u.lang))))
            s.site.otherApps.forEach { (n, url) -> add(listOf(Btn(n, url = url))) }
            s.site.androidAppUrl()?.let { add(listOf(Btn(u.t("Boop for Android"), url = it))) }
            add(listOf(Btn("📱 " + u.t("Devices"), "devices"), Btn("⬅️ " + u.t("Menu"), "menu")))
        }
        api.call("sendPhoto", buildJsonObject { put("chat_id", u.chat); put("caption", caption); put("reply_markup", keyboard(buttons)) },
            Upload("photo", "jaganet-key.png", "image/png", Keys.qrPng(cfg.text)))
        api.call("sendDocument", buildJsonObject { put("chat_id", u.chat) },
            Upload("document", Keys.CONFIG_FILE, "application/octet-stream", cfg.text.toByteArray()))
    }

    /* ---------------- devices ---------------- */

    private data class Dev(val id: String, val name: String, val seen: Instant?)

    /** Devices that count against the plan (with a tunnel). The subscription key (#33) is outside the limit. */
    private suspend fun devicesOf(userId: String): Pair<List<Dev>, Int> = ctx.db.run { sql ->
        val list = sql.query(
            """SELECT d.id, d.name, t.last_seen_at FROM devices d JOIN tunnels t ON t.device_id = d.id
                WHERE d.user_id=?::uuid AND d.removed_at IS NULL AND d.kind = 'device' ORDER BY d.created_at""",
            userId,
        ).map { Dev(it.str("id"), it.str("name"), it.instantOrNull("last_seen_at")) }
        list to s.ent.entitlement(sql, userId).deviceLimit
    }

    private suspend fun devicesAction(u: Who, head: String? = null) {
        val (list, limit) = devicesOf(u.userId)
        val text = buildString {
            head?.let { appendLine(it); appendLine() }
            appendLine("📱 " + u.t("Devices {used} of {limit}", "used" to list.size, "limit" to limit))
            if (list.isEmpty()) appendLine(u.t("No devices connected yet."))
            list.forEachIndexed { i, d ->
                val online = d.seen != null && Duration.between(d.seen, ctx.now()) < Duration.ofMinutes(3)
                val status = if (online) u.t("online") else d.seen?.let { u.t("last seen {date}", "date" to Format.date(it.toString(), u.lang)) } ?: u.t("not connected yet")
                appendLine("${i + 1}. ${keyName(d.name, u.lang)}, $status")
            }
            appendLine()
            append(u.t("The subscription link from My keys is not counted. Delete a device to free a place."))
        }
        val buttons = list.map { d -> listOf(Btn("🗑 " + keyName(d.name, u.lang).take(40), "del:${d.id}")) } + backButtons(u)
        send(u, text, buttons)
    }

    private suspend fun deleteAsk(u: Who, id: String) {
        val d = devicesOf(u.userId).first.firstOrNull { it.id == id } ?: return devicesAction(u, u.t("Device not found"))
        send(
            u, u.t("Delete {name}? It stops connecting.", "name" to keyName(d.name, u.lang)),
            listOf(listOf(Btn(u.t("Delete"), "delok:${d.id}"), Btn(u.t("Cancel"), "devices"))),
        )
    }

    private suspend fun deleteDevice(u: Who, id: String) {
        if (devicesOf(u.userId).first.none { it.id == id }) return devicesAction(u, u.t("Device not found"))
        val head = try {
            s.tunnels.removeFor(u.userId, id)
            "✅ " + u.t("Device deleted.")
        } catch (e: AppError) {
            translate(u.lang, e.message ?: "", e.args)
        }
        devicesAction(u, head)
    }

    /* ---------------- notifications ---------------- */

    private suspend fun notifyAction(u: Who) {
        val on = ctx.db.run { it.one("SELECT tg_notify FROM users WHERE id=?::uuid", u.userId) }?.bool("tg_notify") ?: true
        val text = if (on) u.t("Notifications are on. We remind you 3 days and 1 day before the subscription ends, when it ends, and confirm payments.")
        else u.t("Notifications are off. You won't get reminders before the subscription ends.")
        val toggle = if (on) Btn("🔕 " + u.t("Turn off"), "notify:off") else Btn("🔔 " + u.t("Turn on"), "notify:on")
        send(u, text, listOf(listOf(toggle)) + backButtons(u))
    }

    /**
     * Reminders: 3 days and 1 day before the subscription ends and when it ends (once per end date,
     * so a renewal starts over). Users who turned them off or blocked the bot are skipped; the owner
     * (no end date) too. Ends older than 2 days are not announced (no flood after a long outage).
     * Returns how many were sent.
     */
    suspend fun remind(): Int {
        val now = ctx.now()
        val due = ctx.db.run { sql ->
            sql.query(
                """SELECT u.id, u.telegram_id, u.lang, max(s.expires_at) AS e FROM users u JOIN subscriptions s ON s.user_id = u.id AND s.status = 'active'
                    WHERE u.telegram_id IS NOT NULL AND u.tg_notify AND u.tg_blocked_at IS NULL AND u.role <> 'owner'
                    GROUP BY u.id HAVING max(s.expires_at) > ? AND max(s.expires_at) <= ?""",
                now.minus(Duration.ofDays(2)), now.plus(Duration.ofDays(3)),
            )
        }
        var sent = 0
        for (r in due) {
            val end = r.instant("e")
            val left = Duration.between(now, end)
            val kind = when {
                left <= Duration.ZERO -> "ended"
                left <= Duration.ofDays(1) -> "1d"
                else -> "3d"
            }
            val fresh = ctx.db.run {
                it.one("INSERT INTO telegram_notices (user_id, kind, expires_at, sent_at) VALUES (?::uuid,?,?,?) ON CONFLICT DO NOTHING RETURNING 1 AS one", r.str("id"), kind, end, now)
            } != null
            if (!fresh) continue
            val u = Who(r.str("id"), r.long("telegram_id"), Lang.of(r.strOrNull("lang")) ?: Lang.DEFAULT)
            try {
                val text = if (kind == "ended") "⛔ " + u.t("Your subscription has ended. Renew it to keep using the VPN.")
                else {
                    val days = ((left.toHours() + 23) / 24).coerceAtLeast(1)
                    "⏳ " + u.t("Your subscription ends on {date}.", "date" to Format.date(end.toString(), u.lang)) + " " + u.tp(days, "{n} day left|{n} days left")
                }
                send(u, text, listOf(listOf(Btn("💳 " + u.t("Extend"), "pay")), listOf(Btn("🔕 " + u.t("Turn off notifications"), "notify:off"))))
                sent++
            } catch (e: TelegramException) {
                if (e.chatGone) markBlocked(u.userId)
                // Telegram unreachable: forget it was sent, the next round tries again.
                else ctx.db.run { it.exec("DELETE FROM telegram_notices WHERE user_id=?::uuid AND kind=? AND expires_at=?", u.userId, kind, end) }
                if (!e.chatGone) break
            }
        }
        return sent
    }

    private suspend fun markBlocked(userId: String) = ctx.db.run { it.exec("UPDATE users SET tg_blocked_at=? WHERE id=?::uuid", ctx.now(), userId) }

    /* ---------------- support ---------------- */

    private suspend fun supportAction(u: Who) {
        if (supportChat() == null) return send(u, u.t("Support chat isn't set up yet. Please try later."), backButtons(u))
        supportMode[u.tgId] = ctx.now().plus(SUPPORT_WINDOW)
        send(u, "💬 " + u.t("Write your question in one message. You can attach a screenshot. We answer here."), listOf(listOf(Btn(u.t("Cancel"), "menu"))))
    }

    /** The user's message to the support chat: a header with who it is, then the message itself. */
    private suspend fun forwardToSupport(u: Who, tg: TgUser, m: JsonObject) {
        val chat = supportChat() ?: return send(u, u.t("Support chat isn't set up yet. Please try later."), backButtons(u))
        supportMode[tg.id] = ctx.now().plus(SUPPORT_WINDOW)
        val email = ctx.db.run { it.one("SELECT email FROM users WHERE id=?::uuid", u.userId) }?.str("email").orEmpty()
        val until = proUntil(u.userId)
        val admin = Who(u.userId, chat, Lang.DEFAULT)
        val header = buildString {
            appendLine("💬 " + admin.t("Support request"))
            appendLine(listOfNotNull(tg.firstName, tg.username?.let { "@$it" }, "id ${tg.id}").joinToString(", "))
            if (!TelegramAccounts.isPlaceholder(email)) appendLine(email)
            appendLine(until?.let { admin.t("Subscription active until {date}.", "date" to Format.date(it.toString(), Lang.DEFAULT)) } ?: admin.t("No active subscription."))
            append(admin.t("Reply to this message to answer the user."))
        }
        try {
            val headId = messageId(api.call("sendMessage", buildJsonObject { put("chat_id", chat); put("text", header) }))
            val fwdId = messageId(api.call("forwardMessage", buildJsonObject {
                put("chat_id", chat); put("from_chat_id", u.chat); put("message_id", m["message_id"]!!.jsonPrimitive.long)
            }))
            ctx.db.run { sql ->
                for (id in listOfNotNull(headId, fwdId)) sql.exec(
                    "INSERT INTO telegram_support_messages (admin_chat, admin_message_id, user_id, user_chat, created_at) VALUES (?,?,?::uuid,?,?) ON CONFLICT DO NOTHING",
                    chat, id, u.userId, u.chat, ctx.now(),
                )
            }
        } catch (e: TelegramException) {
            log.warn("support forward failed: ${e.message}")
            return send(u, u.t("Couldn't send the message to support. Please try again later."), backButtons(u))
        }
        send(u, "✅ " + u.t("Sent to support. The answer comes here."), backButtons(u))
    }

    /** An admin replied in the support chat: text goes with a heading, anything else is copied. */
    private suspend fun adminReply(chat: Long, m: JsonObject): Boolean {
        val replyTo = m["reply_to_message"]!!.jsonObject["message_id"]?.jsonPrimitive?.longOrNull ?: return false
        val row = ctx.db.run {
            it.one(
                "SELECT s.user_chat, u.lang FROM telegram_support_messages s LEFT JOIN users u ON u.id = s.user_id WHERE s.admin_chat=? AND s.admin_message_id=?",
                chat, replyTo,
            )
        } ?: return false
        val userChat = row.long("user_chat")
        val u = Who("", userChat, Lang.of(row.strOrNull("lang")) ?: Lang.DEFAULT)
        val admin = Who("", chat, Lang.DEFAULT)
        val ok = runCatching {
            val text = m["text"]?.jsonPrimitive?.content
            val buttons = listOf(listOf(Btn("💬 " + u.t("Reply"), "support"), Btn("⬅️ " + u.t("Menu"), "menu")))
            if (text != null) send(u, "💬 " + u.t("Support answer") + "\n\n" + text, buttons)
            else api.call("copyMessage", buildJsonObject { put("chat_id", userChat); put("from_chat_id", chat); put("message_id", m["message_id"]!!.jsonPrimitive.long) })
        }
        val note = if (ok.isSuccess) "✅ " + admin.t("Sent to the user.") else admin.t("Couldn't deliver the answer. The user may have blocked the bot.")
        runCatching {
            api.call("sendMessage", buildJsonObject {
                put("chat_id", chat); put("text", note)
                putJsonObject("reply_parameters") { put("message_id", m["message_id"]!!.jsonPrimitive.long); put("allow_sending_without_reply", true) }
            })
        }
        return true
    }

    /* ---------------- sign-in and linking ---------------- */

    /** A one-time link that signs this Telegram account in on the website (no e-mail needed). */
    private suspend fun loginAction(u: Who) {
        val link = s.auth.createLoginLink(u.userId, LOGIN_LINK_TTL).replace("/#/", "/?lang=${u.lang.code}#/")
        send(u, u.t("Open this link to sign in on the website. It works once, within 10 minutes."), listOf(listOf(Btn(u.t("Sign in on the website"), url = link))) + backButtons(u))
    }

    /** /start link_<code> from the website's account page. */
    private suspend fun linkAction(tg: TgUser, chat: Long, code: String) {
        val (result, email) = accounts.link(code, tg)
        val acc = accounts.findOrCreate(tg)
        val u = Who(acc.userId, chat, acc.lang ?: Lang.DEFAULT, tg.id)
        val text = when (result) {
            LinkResult.LINKED -> "✅ " + u.t("Telegram is linked to {email}. Sign in with either of them.", "email" to email)
            LinkResult.ALREADY -> u.t("Telegram is already linked to {email}.", "email" to email)
            LinkResult.INVALID -> u.t("This link is used or has expired. Get a new one in your account on the website.")
            LinkResult.ACCOUNT_HAS_OTHER -> u.t("Another Telegram is linked to {email}. Unlink it in your account on the website first.", "email" to email)
            LinkResult.TELEGRAM_ON_OTHER -> u.t("This Telegram is linked to another account. Unlink it there first.")
            LinkResult.TELEGRAM_HAS_PURCHASES -> u.t("This Telegram has its own account with a subscription. Write to support and we will move it.")
        }
        menu(u, text)
    }

    /* ---------------- older screens ---------------- */

    private suspend fun appAction(u: Who) {
        val pair = s.auth.createPairingCodeFor(u.userId)
        val links = buildList {
            s.site.androidAppUrl()?.let { add(listOf(Btn(u.t("Boop for Android"), url = it))) }
            s.site.iosAppUrl()?.let { add(listOf(Btn(u.t("Boop for iPhone"), url = it))) }
        }
        send(
            u,
            buildString {
                appendLine(u.t("Install the Boop app, tap Sign in with a device code and enter this code"))
                appendLine()
                appendLine(pair.code)
                appendLine()
                append(u.t("The code works for 10 minutes. The app uses this same subscription."))
            },
            links + backButtons(u),
        )
    }

    /** The user's main referral link and a 30-day summary; details are on the website. */
    private suspend fun inviteAction(u: Who) {
        if (!referralsFor(u.userId)) return menu(u)
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
        send(u, text, listOf(listOf(Btn(u.t("Open statistics"), url = "${ctx.cfg.publicUrl.trimEnd('/')}/?lang=${u.lang.code}#/referrals"))) + backButtons(u))
    }

    private suspend fun statusAction(u: Who) {
        val pro = proUntil(u.userId)
        if (pro == null) return send(u, u.t("No active subscription."), buyButtons(u) + backButtons(u))
        val (devices, limit) = devicesOf(u.userId)
        send(
            u,
            u.t("Subscription active until {date}.", "date" to Format.date(pro.toString(), u.lang)) + "\n" + u.t("Devices {used} of {limit}", "used" to devices.size, "limit" to limit),
            listOf(listOf(Btn("🔑 " + u.t("My keys and connection"), "keys")), listOf(Btn("💳 " + u.t("Extend"), "pay"), Btn("⬅️ " + u.t("Menu"), "menu"))),
        )
    }

    /* ---------------- helpers ---------------- */

    private fun period(t: Tariff, lang: Lang) = Format.period(t.durationValue, t.durationUnit, lang)

    private suspend fun buyButtons(u: Who, tariffs: List<Tariff>? = null): List<List<Btn>> = (tariffs ?: s.tariffs.onSale(u.lang)).map { t ->
        listOf(Btn(u.t("{product} for {price}", "product" to t.name, "price" to Format.money(t.priceMinor, t.currency, u.lang)), data = "buy:${t.id}"))
    }

    /** Whether this user sees the referral program (admins only, unless REFERRALS_PUBLIC=1). */
    private suspend fun referralsFor(userId: String): Boolean = ctx.db.run { sql ->
        ctx.cfg.referralsPublic || sql.one("SELECT 1 FROM users WHERE id=?::uuid AND role='owner'", userId) != null
    }

    /** End of the paid (or gift) subscription, far future for the owner, null on the free plan. */
    private suspend fun proUntil(userId: String): Instant? = ctx.db.run { sql ->
        s.ent.entitlement(sql, userId).takeIf { it.plan == PlanId.PRO }?.let { e -> e.expiresAt?.let(Instant::parse) ?: Instant.parse("2999-12-31T00:00:00Z") }
    }

    private fun tgUser(from: JsonObject) = TgUser(
        from["id"]!!.jsonPrimitive.long,
        from["username"]?.jsonPrimitive?.content,
        from["first_name"]?.jsonPrimitive?.content,
    )

    /** The account for this Telegram user (made on first contact) and their language. */
    private suspend fun who(tg: TgUser, chat: Long, ref: String? = null): Who {
        val acc = accounts.findOrCreate(tg, ref)
        return Who(acc.userId, chat, acc.lang ?: Lang.DEFAULT, tg.id)
    }

    private fun withLang(url: String, lang: Lang) = url + (if ('?' in url) "&" else "?") + "lang=" + lang.code

    private fun messageId(result: JsonElement): Long? = (result as? JsonObject)?.get("message_id")?.jsonPrimitive?.longOrNull

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

    companion object {
        const val LINK_PREFIX = "link_"
        val LOGIN_LINK_TTL: Duration = Duration.ofMinutes(10)
        val SUPPORT_WINDOW: Duration = Duration.ofMinutes(30)
        const val HAPP_URL = "https://www.happ.su/main/"
        const val V2RAYTUN_URL = "https://v2raytun.com/"
        const val HIDDIFY_URL = "https://hiddify.com/"

        val COMMANDS = listOf(
            "start" to "Plans and menu",
            "keys" to "My keys and connection",
            "pay" to "Buy or extend the subscription",
            "devices" to "Devices",
            "support" to "Write to support",
            "help" to "Help",
            "login" to "Sign in on the website",
        )
    }
}

/** What follows /start (t.me/<bot>?start=<payload>). */
object StartPayload {
    const val LOGIN = "login"
    const val KEYS = "keys"
    const val PAY = "pay"
    const val SUPPORT = "support"
    const val REF_PREFIX = "ref_"
    private val CODE = Regex("^[A-Za-z0-9-]{3,32}$")

    /**
     * The referral code in a /start payload: `ref_<code>` (issue #38), or a bare code as in links
     * made before it. Screens (login, keys…) and link codes are not referral codes.
     */
    fun referral(payload: String): String? {
        val p = payload.trim()
        if (p.isEmpty() || p.lowercase() in setOf(LOGIN, KEYS, PAY, SUPPORT) || p.startsWith(TelegramBot.LINK_PREFIX)) return null
        val code = if (p.startsWith(REF_PREFIX, ignoreCase = true)) p.substring(REF_PREFIX.length) else p
        return code.takeIf { CODE.matches(it) }
    }
}
