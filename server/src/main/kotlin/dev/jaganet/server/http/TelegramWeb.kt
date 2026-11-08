package dev.jaganet.server.http

import dev.jaganet.api.DeviceInfo
import dev.jaganet.api.ErrorCode
import dev.jaganet.api.OkRes
import dev.jaganet.api.Platform
import dev.jaganet.api.i18n.I18n
import dev.jaganet.api.i18n.Lang
import dev.jaganet.server.AppError
import dev.jaganet.server.badRequest
import dev.jaganet.server.unauthorized
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.routing.put
import kotlinx.serialization.json.booleanOrNull
import dev.jaganet.server.telegram.TelegramAccounts
import dev.jaganet.server.telegram.TelegramBot
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Telegram accounts for the bot and the website (made per call: it keeps no state). */
val Services.telegramAccounts get() = TelegramAccounts(ctx, auth, tunnels)

/**
 * /v1 endpoints for Telegram on the website (issue #38):
 *  - GET  /auth/telegram/config  the bot's username, whether the Login Widget is on, the deep link to sign in
 *  - POST /auth/telegram         {auth: <Login Widget data>, referralCode?} → session
 *  - GET  /telegram              is Telegram linked to my account
 *  - POST /telegram/link         a one-time t.me link that links Telegram to my account
 *  - DELETE /telegram/link       unlink (e-mail accounts only)
 * and for the Telegram mini app (issue #87):
 *  - POST /auth/telegram/webapp  {initData} → session (12 hours)
 *  - PUT  /telegram/notify       {notify} notifications in Telegram on or off
 *  - POST /telegram/login-link   a one-time link that signs in on the website
 */
/** GET /v1/auth/telegram/config. Sent with explicit nulls, as before (`"bot": null` when the bot is off). */
@Serializable
data class TelegramConfigRes(val bot: String?, val widget: Boolean, val loginUrl: String?)

/** GET /v1/telegram. Sent with explicit nulls, as before. */
@Serializable
data class TelegramStatusRes(
    val linked: Boolean,
    val username: String?,
    val notify: Boolean,
    val telegramOnly: Boolean,
    val botUrl: String?,
    val bot: String?,
    val lang: String,
    val invites: Boolean,
)

/** POST /v1/telegram/login-link. */
@Serializable
data class TelegramLoginLinkRes(val url: String)

/** POST /v1/telegram/link: the t.me link and when its code expires (ISO-8601). */
@Serializable
data class TelegramLinkRes(val url: String, val expiresAt: String)

fun Route.telegramWebApi(s: Services) {
    fun bot(): String? = s.bot?.username
    get("/auth/telegram/config") {
        val bot = bot()
        call.respondKeepingNulls(TelegramConfigRes(bot, bot != null && s.ctx.cfg.telegram.loginWidget, bot?.let { "https://t.me/$it?start=login" }))
    }
    post("/auth/telegram") {
        val body = call.receive<JsonObject>()
        val auth = body["auth"] as? JsonObject ?: throw AppError(400, ErrorCode.BAD_REQUEST, "Telegram sign-in failed, try again")
        // Every field the widget sent goes into the signature check, as strings.
        val fields = auth.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
        val ref = (body["referralCode"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(32)
        val session = s.telegramAccounts.signInWithWidget(fields, DeviceInfo(I18n.tr(call.apiLang(), "Website"), Platform.OTHER), ref)
        call.rememberClient(s, session.user.id)
        call.respond(session)
    }
    // The mini app signs in with Telegram.WebApp.initData (issue #87). Never logged: it is a credential.
    rateLimit(AUTH_LIMIT) {
        post("/auth/telegram/webapp") {
            val raw = (call.receive<JsonObject>()["initData"] as? JsonPrimitive)?.contentOrNull ?: throw unauthorized()
            val session = s.telegramAccounts.signInWithWebApp(raw)
            call.rememberClient(s, session.user.id)
            call.respond(session)
        }
    }
    get("/telegram") {
        val p = call.principal(s)
        val r = s.ctx.db.run { it.one("SELECT telegram_id, tg_username, tg_notify, lang FROM users WHERE id=?::uuid", p.user.id) }
        val bot = bot()
        call.respondKeepingNulls(
            TelegramStatusRes(
                linked = r?.longOrNull("telegram_id") != null,
                username = r?.strOrNull("tg_username"),
                notify = r?.bool("tg_notify") ?: true,
                telegramOnly = TelegramAccounts.isPlaceholder(p.user.email),
                botUrl = bot?.let { "https://t.me/$it" },
                bot = bot,
                // For the mini app (issue #87): the account's language and whether "Invite a friend" is shown.
                lang = (Lang.of(r?.strOrNull("lang")) ?: Lang.DEFAULT).code,
                invites = s.invitesFor(p.user.role),
            ),
        )
    }
    // Notifications in Telegram on or off (the bot's "Notifications" button does the same).
    put("/telegram/notify") {
        val p = call.principal(s)
        val on = (call.receive<JsonObject>()["notify"] as? JsonPrimitive)?.booleanOrNull ?: throw badRequest("Bad request")
        s.ctx.db.run { it.exec("UPDATE users SET tg_notify=? WHERE id=?::uuid", on, p.user.id) }
        call.respond(OkRes())
    }
    // A one-time link that signs this account in on the website, as the bot's /login (10 minutes).
    post("/telegram/login-link") {
        val p = call.principal(s)
        val lang = call.apiLang()
        val url = s.auth.createLoginLink(p.user.id, TelegramBot.LOGIN_LINK_TTL).replace("/#/", "/?lang=${lang.code}#/")
        call.respond(TelegramLoginLinkRes(url))
    }
    post("/telegram/link") {
        val p = call.principal(s)
        val bot = bot() ?: throw AppError(503, ErrorCode.SERVICE_UNAVAILABLE, "The Telegram bot is off")
        val (code, expires) = s.telegramAccounts.createLinkCode(p.user.id)
        call.respond(TelegramLinkRes("https://t.me/$bot?start=${TelegramBot.LINK_PREFIX}$code", expires.toString()))
    }
    delete("/telegram/link") {
        s.telegramAccounts.unlink(call.principal(s).user.id)
        call.respond(OkRes())
    }
}
