package dev.jaganet.server.http

import dev.jaganet.api.CreateOrderReq
import dev.jaganet.api.CreateReferralLinkReq
import dev.jaganet.api.Protocols
import dev.jaganet.api.ReferralPeriod
import dev.jaganet.api.RenameReferralLinkReq
import dev.jaganet.api.Role
import dev.jaganet.server.forbidden
import io.ktor.server.plugins.origin
import io.ktor.server.routing.patch
import dev.jaganet.api.Format
import dev.jaganet.api.KeysRes
import dev.jaganet.api.OkRes
import dev.jaganet.api.OrderStatus
import dev.jaganet.api.ProductId
import dev.jaganet.api.SiteInfo
import dev.jaganet.api.i18n.I18n
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import dev.jaganet.api.i18n.Lang
import dev.jaganet.server.notFound
import dev.jaganet.server.services.Channel
import dev.jaganet.server.services.KeyConfig
import dev.jaganet.server.services.Keys
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** /v1 endpoints the website uses for buying and VPN keys. */
fun Route.salesApi(s: Services) {
    get("/site") {
        call.respond(
            SiteInfo(
                androidAppUrl = s.site.androidAppUrl(),
                iosAppUrl = s.site.iosAppUrl(),
                telegramBotUrl = s.bot?.username?.let { "https://t.me/$it" },
                testPayments = s.payments.isTest,
                paymentsEnabled = s.payments.provider != null,
            ),
        )
    }
    // The website's texts in one language (keys are the English texts).
    get("/i18n/{lang}") {
        val lang = Lang.of(call.parameters["lang"]) ?: Lang.DEFAULT
        call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
        call.respond(JsonObject(I18n.table(lang).mapValues { JsonPrimitive(it.value) }))
    }
    get("/keys") {
        val lang = call.apiLang()
        call.respond(KeysRes(s.keys.list(call.principal(s).user.id).map { it.copy(name = keyName(it.name, lang)) }))
    }
    post("/keys") { call.respond(s.keys.create(call.principal(s).user.id).let { it.copy(name = keyName(it.name, call.apiLang())) }) }
    delete("/keys/{id}") {
        s.keys.remove(call.principal(s).user.id, call.parameters["id"]!!)
        call.respond(OkRes())
    }
    post("/orders") {
        val p = call.principal(s)
        call.respond(s.payments.create(p.user.id, call.receive<CreateOrderReq>().productId, Channel.WEB, call.apiLang()))
    }
    get("/orders/{id}") { call.respond(s.payments.getFor(call.principal(s).user.id, call.parameters["id"]!!)) }
}

/** The website (static files in resources/web), key links and the test checkout. */
fun Route.website(s: Services) {
    staticResources("/", "web")
    referralRedirect(s)

    get("/download/android") {
        val apk = s.site.apkFile() ?: throw notFound("The Android app isn't uploaded yet")
        call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "JagaNet.apk").toString())
        call.respondFile(apk)
    }

    // Key links: the token is the secret. Never cache, never index, never leak it in a Referer.
    suspend fun ApplicationCall.key(): KeyConfig {
        response.header(HttpHeaders.CacheControl, "no-store")
        response.header("X-Robots-Tag", "noindex")
        response.header("Referrer-Policy", "no-referrer")
        return s.keys.byToken(parameters["token"]!!) ?: throw notFound("This key doesn't exist or was deleted")
    }
    get("/k/{token}") {
        val k = call.key()
        call.respondText(Pages(call.pageLang()).key(k, call.parameters["token"]!!, s), ContentType.Text.Html)
    }
    get("/k/{token}/${Keys.CONFIG_FILE}") {
        val k = call.key()
        call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, Keys.CONFIG_FILE).toString())
        call.respondText(k.text, ContentType.Application.OctetStream)
    }
    get("/k/{token}/qr.png") {
        val k = call.key()
        call.respondBytes(Keys.qrPng(k.text), ContentType.Image.PNG)
    }

    // Test checkout: stands in for the real payment service until one is chosen.
    get("/pay/test/{id}") {
        if (!s.payments.isTest) throw notFound()
        val o = s.payments.get(call.parameters["id"]!!) ?: throw notFound("Order not found")
        val lang = call.pageLang()
        call.respondText(Pages(lang).checkout(o.id, productName(o.productId, lang), Format.money(o.amountMinor, o.currency, lang), o.status == OrderStatus.PAID), ContentType.Text.Html)
    }
    post("/pay/test/{id}") {
        if (!s.payments.isTest) throw notFound()
        val id = call.parameters["id"]!!
        s.payments.markPaid(id, "test-${System.currentTimeMillis()}")
        val o = s.payments.get(id) ?: throw notFound("Order not found")
        when (o.channel) {
            Channel.WEB -> call.respondRedirect("/#/account?order=${o.id}")
            Channel.TELEGRAM -> call.respondText(Pages(call.pageLang()).paidInTelegram(s.bot?.username), ContentType.Text.Html)
        }
    }
}

fun productName(p: ProductId, lang: Lang) = I18n.tr(lang, when (p) { ProductId.PRO_MONTHLY -> "Pro for 1 month"; ProductId.PRO_YEARLY -> "Pro for 1 year" })

/** Keys are stored as "VPN key", "VPN key 2"…; shown in the reader's language. */
fun keyName(name: String, lang: Lang): String =
    Regex("^VPN key( \\d+)?$").matchEntire(name)?.let { I18n.tr(lang, "VPN key") + it.groupValues[1] } ?: name

fun esc(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

/** Pages rendered by the server, in one language. */
private class Pages(val lang: Lang) {
    fun t(en: String, vararg args: Pair<String, Any?>) = esc(I18n.tr(lang, en, *args))

    private fun page(title: String, body: String) = """<!doctype html>
<html lang="${lang.code}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>$title | JagaNet</title>
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@500&family=IBM+Plex+Sans:wght@400;500;600;700&display=swap" rel="stylesheet">
<link rel="icon" href="/favicon.svg" type="image/svg+xml"><link rel="stylesheet" href="/style.css"></head>
<body><header class="top"><a class="logo" href="/"><span class="dot"></span>JagaNet</a>
<nav class="nav langs">${Lang.entries.joinToString("") { l -> """<a class="${if (l == lang) "on" else ""}" href="?lang=${l.code}" hreflang="${l.code}">${l.code.uppercase()}</a>""" }}</nav></header>
<main class="narrow">$body</main></body></html>"""

    fun key(k: KeyConfig, token: String, s: Services): String {
        val base = "/k/" + esc(token)
        val ours = s.site.androidAppUrl()?.let { """<li><a href="${esc(it)}">${t("JagaNet for Android")}</a><br><span class="muted">${t("In our app you just sign in, no key needed")}</span></li>""" } ?: ""
        val others = s.site.otherApps.joinToString("") { (n, u) -> """<li><a href="${esc(u)}" rel="noreferrer">${esc(n)}</a></li>""" }
        return page(t("Your VPN key"), """
<h1>${t("Your VPN key")}</h1>
<p class="muted">${esc(k.location)}<br>${esc(keyName(k.name, lang))}</p>
<div class="card keycard">
  <img class="qr" src="$base/qr.png" alt="${t("QR code of your VPN key")}" width="280" height="280">
  <div class="keyactions">
    <a class="btn" href="$base/${Keys.CONFIG_FILE}" download>${t("Download {file}", "file" to Keys.CONFIG_FILE)}</a>
    <button class="btn secondary" id="copy" type="button">${t("Copy config text")}</button>
  </div>
</div>
<h2>${t("How to connect")}</h2>
<ol class="steps">
  <li><b>${t("Install an app")}</b><br>${t("For example one of these apps")}<ul>$ours$others</ul></li>
  <li><b>${t("Add the key")}</b><br>${t("In the app tap + and choose Scan QR code, then point the camera at the code above. Or choose Import from file and pick {file}.", "file" to Keys.CONFIG_FILE)}</li>
  <li><b>${t("Turn on the VPN")}</b><br>${t("That's all.")}</li>
</ol>
<p class="warn">${t("Keep this page private. Anyone with this link can use your subscription. If it leaks, delete the key in your account and make a new one.")}</p>
<textarea id="cfg" hidden>${esc(k.text)}</textarea>
<script>document.getElementById('copy').onclick=async e=>{await navigator.clipboard.writeText(document.getElementById('cfg').value);e.target.textContent=${jsString(I18n.tr(lang, "Copied"))}};</script>
""")
    }

    fun checkout(id: String, product: String, amount: String, paid: Boolean) = page(t("Payment"), """
<div class="card center">
  <span class="tag">${t("TEST PAYMENT")}</span>
  <h1>${esc(product)}</h1>
  <div class="price">${esc(amount)}</div>
  ${if (paid) """<p class="muted">${t("This order is already paid.")}</p><a class="btn" href="/#/account">${t("Go to my account")}</a>"""
    else """<p class="muted">${t("No money is taken here. This page stands in for the real payment service until it is connected.")}</p>
  <form method="post" action="/pay/test/${esc(id)}?lang=${lang.code}"><button class="btn" type="submit">${t("Pay {amount} (test)", "amount" to amount)}</button></form>"""}
</div>""")

    fun paidInTelegram(bot: String?) = page(t("Payment received"), """
<div class="card center"><div class="big">✓</div><h1>${t("Payment received")}</h1>
<p class="muted">${t("Your VPN key is waiting in the Telegram chat.")}</p>
${bot?.let { """<a class="btn" href="https://t.me/${esc(it)}">${t("Back to Telegram")}</a>""" } ?: ""}</div>""")

    private fun jsString(v: String) = "'" + v.replace("\\", "\\\\").replace("'", "\\'") + "'"
}

private fun period(q: String?): ReferralPeriod =
    ReferralPeriod.entries.firstOrNull { Protocols.json.encodeToString(ReferralPeriod.serializer(), it).trim('"') == q } ?: ReferralPeriod.D30

/** /v1 endpoints of the referral program. */
fun Route.referralApi(s: Services) {
    get("/referrals/stats") {
        val p = call.principal(s)
        call.respond(s.referrals.stats(p.user.id, period(call.request.queryParameters["period"])))
    }
    post("/referrals/links") {
        val p = call.principal(s)
        val b = call.receive<CreateReferralLinkReq>()
        call.respond(s.referrals.createLink(p.user.id, b.name, b.code))
    }
    patch("/referrals/links/{id}") {
        s.referrals.rename(call.principal(s).user.id, call.parameters["id"]!!, call.receive<RenameReferralLinkReq>().name)
        call.respond(OkRes())
    }
    delete("/referrals/links/{id}") {
        s.referrals.archive(call.principal(s).user.id, call.parameters["id"]!!)
        call.respond(OkRes())
    }
    get("/admin/referrals") {
        val p = call.principal(s)
        if (p.user.role != Role.OWNER) throw forbidden()
        call.respond(s.referrals.admin(period(call.request.queryParameters["period"])))
    }
}

/**
 * A referral link: count the visit, remember nothing about the person but a hashed key
 * (IP + browser) for unique visitors, then open the website with the code, which it keeps
 * until sign-up.
 */
fun Route.referralRedirect(s: Services) {
    get("/r/{code}") {
        val q = call.request.queryParameters
        val ip = call.request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim() ?: call.request.origin.remoteHost
        val ua = call.request.headers[HttpHeaders.UserAgent].orEmpty()
        val referer = call.request.headers[HttpHeaders.Referrer]?.let { runCatching { java.net.URI(it).host }.getOrNull() }
            ?.removePrefix("www.")?.takeIf { it != java.net.URI(s.ctx.cfg.publicUrl).host }
        val code = s.referrals.click(call.parameters["code"]!!, "$ip|$ua", "web", q["utm_source"]?.takeIf { it.isNotBlank() } ?: referer)
        val lang = Lang.of(q["lang"])?.let { "&lang=${it.code}" } ?: ""
        call.respondRedirect(if (code != null) "/?ref=${java.net.URLEncoder.encode(code, "UTF-8")}$lang" else "/")
    }
}
