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
import dev.jaganet.api.SiteInfo
import dev.jaganet.api.i18n.I18n
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import dev.jaganet.api.i18n.Lang
import dev.jaganet.server.notFound
import dev.jaganet.server.services.Channel
import dev.jaganet.server.services.KeyConfig
import dev.jaganet.server.services.Keys
import dev.jaganet.server.services.VlessKeys
import dev.jaganet.server.services.VlessLinks
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
                emailSignIn = s.auth.emailSignInAvailable(),
                testCodes = s.ctx.live.showSignInCodes,
                referralsPublic = s.ctx.cfg.referralsPublic,
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
        val p = call.principal(s)
        // The VLESS key only for callers that run VLESS (the website asks with vless): older apps see the list as before.
        val vless = if (call.supportsVless()) {
            runCatching { s.vlessKeys.ensureFor(p.user.id) }
            s.vlessKeys.key(p.user.id)
        } else null
        call.respond(KeysRes(s.keys.list(p.user.id).map { it.copy(name = keyName(it.name, lang)) }, vless))
    }
    post("/keys") { call.respond(s.keys.create(call.principal(s).user.id).let { it.copy(name = keyName(it.name, call.apiLang())) }) }
    get("/keys/{id}/tunnel") { call.respond(s.keys.tunnel(call.principal(s).user.id, call.parameters["id"]!!)) }
    delete("/keys/{id}") {
        s.keys.remove(call.principal(s).user.id, call.parameters["id"]!!)
        call.respond(OkRes())
    }
    post("/orders") {
        val p = call.principal(s)
        val req = call.receive<CreateOrderReq>()
        call.respond(s.payments.create(p.user.id, req.tariffId, if (req.channel == "app") Channel.APP else Channel.WEB, call.apiLang()))
    }
    get("/orders/{id}") { call.respond(s.payments.getFor(call.principal(s).user.id, call.parameters["id"]!!)) }
}

/** The website (static files in resources/web), key links and the test checkout. */
fun Route.website(s: Services) {
    staticResources("/", "web")
    referralRedirect(s)

    get("/download/android") {
        val apk = s.site.apkFile() ?: throw notFound("The Android app isn't uploaded yet")
        call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "Boop.apk").toString())
        call.respondFile(apk)
    }
    // Opt-in, same as the manifest: 404 until Boop.exe is placed in DOWNLOADS_DIR.
    get("/download/windows") {
        val exe = s.site.desktopExe() ?: throw notFound("The Windows app isn't uploaded yet")
        call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "Boop.exe").toString())
        call.respondFile(exe)
    }

    // Opt-in too: 404 until boop-<distro>.deb is placed in DOWNLOADS_DIR.
    get("/download/linux/{distro}") {
        val distro = call.parameters["distro"]!!.trim().lowercase()
        val deb = s.site.linuxDeb(distro) ?: throw notFound("The Linux app isn't uploaded yet")
        call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "boop_${distro}_amd64.deb").toString())
        call.respondFile(deb)
    }

    // Key links: the token is the secret. Never cache, never index, never leak it in a Referer.
    suspend fun ApplicationCall.key(): KeyConfig {
        response.header(HttpHeaders.CacheControl, "no-store")
        response.header("X-Robots-Tag", "noindex")
        response.header("Referrer-Policy", "no-referrer")
        return s.keys.byToken(parameters["token"]!!) ?: throw notFound("This key doesn't exist or was deleted")
    }
    fun ApplicationCall.secret() {
        response.header(HttpHeaders.CacheControl, "no-store")
        response.header("X-Robots-Tag", "noindex")
        response.header("Referrer-Policy", "no-referrer")
    }
    // The user's VLESS key: by its own token or by any of their AmneziaWG key links.
    suspend fun ApplicationCall.vless(): VlessLinks {
        secret()
        val user = s.vlessKeys.userByToken(parameters["token"]!!) ?: throw notFound("This key doesn't exist or was deleted")
        return s.vlessKeys.links(user) ?: throw notFound("This key doesn't exist or was deleted")
    }
    get("/k/{token}") {
        val token = call.parameters["token"]!!
        if (s.vlessKeys.isOwnToken(token)) {
            val v = call.vless()
            return@get call.respondText(Pages(call.pageLang()).vlessKey(v, s.ctx.cfg.publicUrl), ContentType.Text.Html)
        }
        val k = call.key()
        val other = s.vlessKeys.userByToken(token)?.let { s.vlessKeys.links(it) }
        call.respondText(Pages(call.pageLang()).key(k, token, s, other), ContentType.Text.Html)
    }
    /**
     * Subscription for Happ, v2RayTun, Hiddify, v2rayNG: base64 of the vless:// links, one per line.
     * Works with the VLESS key's token and with the user's AmneziaWG key links.
     */
    get("/k/{token}/vless") {
        val v = call.vless()
        if (v.links.isEmpty()) throw notFound("This key is off until access is renewed")
        val expire = v.expiresAt?.let { runCatching { java.time.Instant.parse(it).epochSecond }.getOrNull() } ?: 0
        call.response.header("profile-title", "base64:" + java.util.Base64.getEncoder().encodeToString("Boop VPN".toByteArray()))
        call.response.header("profile-update-interval", "12")
        call.response.header("subscription-userinfo", "upload=0; download=0; total=0; expire=$expire")
        call.respondText(VlessKeys.subscription(v.links), ContentType.Text.Plain)
    }
    // QR codes: 0.png, 1.png… one per link; sub.png for the subscription URL.
    get("/k/{token}/vless/{file}") {
        val v = call.vless()
        val file = call.parameters["file"]!!
        val text = if (file == "sub.png") "${s.ctx.cfg.publicUrl.trimEnd('/')}/k/${call.parameters["token"]!!}/vless"
        else file.removeSuffix(".png").takeIf { file.endsWith(".png") }?.toIntOrNull()?.let { v.links.getOrNull(it) }
            ?: throw notFound("This key is off until access is renewed")
        call.respondBytes(Keys.qrPng(text), ContentType.Image.PNG)
    }
    // The file's old name (before the rename to Boop VPN): links already sent keep working.
    get("/k/{token}/JagaNet.conf") {
        val k = call.key()
        call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, Keys.CONFIG_FILE).toString())
        call.respondText(k.text, ContentType.Application.OctetStream)
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
        call.respondText(Pages(lang).checkout(o.id, o.terms.name, Format.money(o.amountMinor, o.currency, lang), o.status == OrderStatus.PAID), ContentType.Text.Html)
    }
    post("/pay/test/{id}") {
        if (!s.payments.isTest) throw notFound()
        val id = call.parameters["id"]!!
        s.payments.markPaid(id, "test-${System.currentTimeMillis()}")
        val o = s.payments.get(id) ?: throw notFound("Order not found")
        when (o.channel) {
            Channel.WEB -> call.respondRedirect("/#/account?order=${o.id}")
            Channel.TELEGRAM -> call.respondText(Pages(call.pageLang()).paidInTelegram(s.bot?.username), ContentType.Text.Html)
            Channel.APP -> call.respondText(Pages(call.pageLang()).paidInApp(), ContentType.Text.Html)
        }
    }
}

/** Keys are stored as "VPN key", "VPN key 2"…; shown in the reader's language. */
fun keyName(name: String, lang: Lang): String =
    Regex("^VPN key( \\d+)?$").matchEntire(name)?.let { I18n.tr(lang, "VPN key") + it.groupValues[1] } ?: name

fun esc(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

/** Pages rendered by the server, in one language. */
private class Pages(val lang: Lang) {
    fun t(en: String, vararg args: Pair<String, Any?>) = esc(I18n.tr(lang, en, *args))

    private fun page(title: String, body: String) = """<!doctype html>
<html lang="${lang.code}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>$title | Boop</title>
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&display=swap" rel="stylesheet">
<link rel="icon" href="/favicon.svg" type="image/svg+xml"><link rel="stylesheet" href="/style.css"></head>
<body><header class="top"><div class="top-in"><a class="logo" href="/"><img src="/favicon.svg" alt="">Boop</a>
<nav class="nav"><span class="langs">${Lang.entries.joinToString("") { l -> """<a class="${if (l == lang) "on" else ""}" href="?lang=${l.code}" hreflang="${l.code}">${l.code.uppercase()}</a>""" }}</span></nav></div></header>
<main class="narrow">$body</main></body></html>"""

    /** The VLESS key's page: its links with copy buttons and QR codes, and the subscription. */
    fun vlessKey(v: VlessLinks, publicUrl: String): String {
        val base = "/k/" + esc(v.token)
        val sub = esc("${publicUrl.trimEnd('/')}/k/${v.token}/vless")
        val body = if (v.links.isEmpty()) {
            """<div class="card"><p class="notice bad">${t("This key is off until access is renewed")}</p></div>"""
        } else {
            val extra = v.links.drop(1).mapIndexed { i, l ->
                """<div class="card section"><h3>${t("Backup key")}</h3>
  <div class="formrow two"><input class="mono" id="vk${i + 1}" readonly value="${esc(l)}"><button class="btn" data-copy="vk${i + 1}" type="button">${t("Copy key")}</button></div></div>"""
            }.joinToString("")
            """<div class="card keycard">
  <img class="qr" src="$base/vless/0.png" alt="${t("QR code of your VPN key")}" width="280" height="280">
  <div class="keyactions"><input class="mono" id="vk0" readonly value="${esc(v.links[0])}"><button class="btn" data-copy="vk0" type="button">${t("Copy key")}</button></div>
</div>
$extra
<div class="card section"><h3>${t("Subscription link")}</h3>
  <p class="muted">${t("For Happ, v2RayTun, Hiddify and v2rayNG")}</p>
  <div class="formrow two"><input class="mono" id="vsub" readonly value="$sub"><button class="btn" data-copy="vsub" type="button">${t("Copy link")}</button></div>
  <img class="qr" src="$base/vless/sub.png" alt="${t("QR code of your VPN key")}" width="200" height="200">
</div>"""
        }
        return page(t("Your VPN key"), """
<div class="head"><div><h1>${t("Your VPN key")}</h1><p class="muted">${esc(v.name)}</p></div></div>
$body
<h2>${t("How to connect")}</h2>
<ol class="steps">
  <li><b>${t("Install an app")}</b><ul><li>Happ</li><li>v2RayTun</li><li>Hiddify</li><li>v2rayNG</li></ul></li>
  <li><b>${t("Add the key")}</b></li>
  <li><b>${t("Turn on the VPN")}</b></li>
</ol>
<p class="notice bad">${t("Don't share this link")}</p>
<script>for(const b of document.querySelectorAll('[data-copy]'))b.onclick=async e=>{await navigator.clipboard.writeText(document.getElementById(b.dataset.copy).value);e.target.textContent=${jsString(I18n.tr(lang, "Copied"))}};</script>
""")
    }

    fun key(k: KeyConfig, token: String, s: Services, vless: VlessLinks? = null): String {
        val base = "/k/" + esc(token)
        val ours = s.site.androidAppUrl()?.let { """<li><a href="${esc(it)}">${t("Boop for Android")}</a></li>""" } ?: ""
        val others = s.site.otherApps.joinToString("") { (n, u) -> """<li><a href="${esc(u)}" rel="noreferrer">${esc(n)}</a></li>""" }
        return page(t("Your VPN key"), """
<div class="head"><div><h1>${t("Your VPN key")}</h1><p class="muted">${esc(keyName(k.name, lang))}, ${esc(k.location)}</p></div></div>
<div class="card keycard">
  <img class="qr" src="$base/qr.png" alt="${t("QR code of your VPN key")}" width="280" height="280">
  <div class="keyactions">
    <a class="btn" href="$base/${Keys.CONFIG_FILE}" download>${t("Download {file}", "file" to Keys.CONFIG_FILE)}</a>
    <button class="btn secondary" id="copy" type="button">${t("Copy config text")}</button>
  </div>
</div>
<div class="card section">
  <h3>${t("Key for AmneziaVPN")}</h3>
  <div class="formrow two"><input class="mono" id="vpnkey" readonly value="${esc(Keys.vpnLink(k.text))}"><button class="btn" id="copykey" type="button">${t("Copy key")}</button></div>
</div>
${vless?.takeIf { it.links.isNotEmpty() }?.let { v -> """<div class="card section"><div class="card-h"><h3>${t("One more key")}</h3><a class="btn secondary" href="/k/${esc(v.token)}?lang=${lang.code}">${esc(v.name)}</a></div>
  <p class="muted">${t("For Happ, v2RayTun, Hiddify and v2rayNG")}</p></div>""" } ?: ""}
<h2>${t("How to connect")}</h2>
<ol class="steps">
  <li><b>${t("Install an app")}</b><ul>$ours$others</ul></li>
  <li><b>${t("Add the key")}</b></li>
  <li><b>${t("Turn on the VPN")}</b></li>
</ol>
<p class="notice bad">${t("Don't share this link")}</p>
<textarea id="cfg" hidden>${esc(k.text)}</textarea>
<script>const cp=(b,v)=>b.onclick=async e=>{await navigator.clipboard.writeText(document.getElementById(v).value);e.target.textContent=${jsString(I18n.tr(lang, "Copied"))}};
cp(document.getElementById('copy'),'cfg');cp(document.getElementById('copykey'),'vpnkey');</script>
""")
    }

    fun checkout(id: String, product: String, amount: String, paid: Boolean) = page(t("Payment"), """
<div class="card signbox center">
  <span class="tag">${t("Test payment")}</span>
  <h1>${esc(product)}</h1>
  <div class="price">${esc(amount)}</div>
  ${if (paid) """<p class="muted">${t("This order is already paid.")}</p><a class="btn" href="/#/account">${t("Go to my account")}</a>"""
    else """<form method="post" action="/pay/test/${esc(id)}?lang=${lang.code}"><button class="btn" type="submit">${t("Pay {amount} (test)", "amount" to amount)}</button></form>"""}
</div>""")

    fun paidInTelegram(bot: String?) = page(t("Payment received"), """
<div class="card signbox center"><h1>${t("Payment received")}</h1>
<p class="muted">${t("The key is in the Telegram chat")}</p>
${bot?.let { """<a class="btn" href="https://t.me/${esc(it)}">${t("Back to Telegram")}</a>""" } ?: ""}</div>""")

    fun paidInApp() = page(t("Payment received"), """
<div class="card signbox center"><h1>${t("Payment received")}</h1>
<p class="muted">${t("Pro is on")}</p></div>""")

    private fun jsString(v: String) = "'" + v.replace("\\", "\\\\").replace("'", "\\'") + "'"
}

private fun period(q: String?): ReferralPeriod =
    ReferralPeriod.entries.firstOrNull { Protocols.json.encodeToString(ReferralPeriod.serializer(), it).trim('"') == q } ?: ReferralPeriod.D30

/** The referral program is shown to admins (owners) only, unless REFERRALS_PUBLIC=1; for everyone else it does not exist. */
fun Services.referralsFor(role: Role) = role == Role.OWNER || ctx.cfg.referralsPublic

fun Services.requireReferrals(role: Role) {
    if (!referralsFor(role)) throw notFound("The referral program is not available")
}

/** /v1 endpoints of the referral program. */
fun Route.referralApi(s: Services) {
    get("/referrals/stats") {
        val p = call.principal(s).also { s.requireReferrals(it.user.role) }
        call.respond(s.referrals.stats(p.user.id, period(call.request.queryParameters["period"])))
    }
    post("/referrals/links") {
        val p = call.principal(s).also { s.requireReferrals(it.user.role) }
        val b = call.receive<CreateReferralLinkReq>()
        call.respond(s.referrals.createLink(p.user.id, b.name, b.code))
    }
    patch("/referrals/links/{id}") {
        s.referrals.rename(call.principal(s).also { s.requireReferrals(it.user.role) }.user.id, call.parameters["id"]!!, call.receive<RenameReferralLinkReq>().name)
        call.respond(OkRes())
    }
    delete("/referrals/links/{id}") {
        s.referrals.archive(call.principal(s).also { s.requireReferrals(it.user.role) }.user.id, call.parameters["id"]!!)
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
