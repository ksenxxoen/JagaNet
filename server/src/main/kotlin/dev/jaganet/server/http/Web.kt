package dev.jaganet.server.http

import dev.jaganet.api.CreateOrderReq
import dev.jaganet.api.Format
import dev.jaganet.api.KeysRes
import dev.jaganet.api.OkRes
import dev.jaganet.api.OrderStatus
import dev.jaganet.api.ProductId
import dev.jaganet.api.SiteInfo
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
    get("/keys") { call.respond(KeysRes(s.keys.list(call.principal(s).user.id))) }
    post("/keys") { call.respond(s.keys.create(call.principal(s).user.id)) }
    delete("/keys/{id}") {
        s.keys.remove(call.principal(s).user.id, call.parameters["id"]!!)
        call.respond(OkRes())
    }
    post("/orders") {
        val p = call.principal(s)
        call.respond(s.payments.create(p.user.id, call.receive<CreateOrderReq>().productId, Channel.WEB))
    }
    get("/orders/{id}") { call.respond(s.payments.getFor(call.principal(s).user.id, call.parameters["id"]!!)) }
}

/** The website (static files in resources/web), key links and the test checkout. */
fun Route.website(s: Services) {
    staticResources("/", "web")

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
        call.respondText(keyPage(k, call.parameters["token"]!!, s), ContentType.Text.Html)
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
        call.respondText(testCheckoutPage(o.id, productName(o.productId), Format.money(o.amountMinor, o.currency), o.status == OrderStatus.PAID), ContentType.Text.Html)
    }
    post("/pay/test/{id}") {
        if (!s.payments.isTest) throw notFound()
        val id = call.parameters["id"]!!
        s.payments.markPaid(id, "test-${System.currentTimeMillis()}")
        val o = s.payments.get(id) ?: throw notFound("Order not found")
        when (o.channel) {
            Channel.WEB -> call.respondRedirect("/#/account?order=${o.id}")
            Channel.TELEGRAM -> call.respondText(page("Paid", """
                <div class="card center"><div class="big">✓</div><h1>Payment received</h1>
                <p class="muted">Your VPN key is waiting in the Telegram chat.</p>
                ${s.bot?.username?.let { """<a class="btn" href="https://t.me/${esc(it)}">Back to Telegram</a>""" } ?: ""}</div>"""), ContentType.Text.Html)
        }
    }
}

fun productName(p: ProductId) = when (p) { ProductId.PRO_MONTHLY -> "Pro · 1 month"; ProductId.PRO_YEARLY -> "Pro · 1 year" }

fun esc(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

private fun page(title: String, body: String) = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>${esc(title)} · JagaNet</title>
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@500&family=IBM+Plex+Sans:wght@400;500;600;700&display=swap" rel="stylesheet">
<link rel="icon" href="/favicon.svg" type="image/svg+xml"><link rel="stylesheet" href="/style.css"></head>
<body><header class="top"><a class="logo" href="/"><span class="dot"></span>JagaNet</a></header>
<main class="narrow">$body</main></body></html>"""

private fun keyPage(k: KeyConfig, token: String, s: Services): String {
    val base = "/k/" + esc(token)
    val proto = if (k.protocol == "amneziawg") "AmneziaWG" else "WireGuard"
    val ours = s.site.androidAppUrl()?.let { """<li><a href="${esc(it)}">JagaNet for Android</a> — sign in instead and you don't need this key</li>""" } ?: ""
    val others = s.site.otherApps.joinToString("") { (n, u) -> """<li><a href="${esc(u)}" rel="noreferrer">${esc(n)}</a></li>""" }
    return page("Your VPN key", """
<h1>Your VPN key</h1>
<p class="muted">${esc(k.location)} · $proto · ${esc(k.name)}</p>
<div class="card keycard">
  <img class="qr" src="$base/qr.png" alt="QR code of your VPN key" width="280" height="280">
  <div class="keyactions">
    <a class="btn" href="$base/${Keys.CONFIG_FILE}" download>Download ${Keys.CONFIG_FILE}</a>
    <button class="btn secondary" id="copy" type="button">Copy config text</button>
  </div>
</div>
<h2>How to connect</h2>
<ol class="steps">
  <li><b>Install an app</b> that supports $proto:<ul>$ours$others</ul></li>
  <li><b>Add the key</b>: tap <i>+</i> and choose <i>Scan QR code</i> (point the camera at the code above from another screen), or <i>Import from file</i> and pick ${Keys.CONFIG_FILE}.</li>
  <li><b>Connect.</b> That's it.</li>
</ol>
<p class="warn">Keep this page private: anyone with this link can use your subscription. If it leaks, delete the key in your account and make a new one.</p>
<textarea id="cfg" hidden>${esc(k.text)}</textarea>
<script>document.getElementById('copy').onclick=async e=>{await navigator.clipboard.writeText(document.getElementById('cfg').value);e.target.textContent='Copied'};</script>
""")
}

private fun testCheckoutPage(id: String, product: String, amount: String, paid: Boolean) = page("Checkout", """
<div class="card center">
  <span class="tag">TEST CHECKOUT</span>
  <h1>${esc(product)}</h1>
  <div class="price">${esc(amount)}</div>
  ${if (paid) """<p class="muted">This order is already paid.</p><a class="btn" href="/#/account">Go to my account</a>"""
    else """<p class="muted">No money is taken here. This page stands in for the real payment service until it is connected.</p>
  <form method="post" action="/pay/test/${esc(id)}"><button class="btn" type="submit">Pay $amount (test)</button></form>"""}
</div>""")
