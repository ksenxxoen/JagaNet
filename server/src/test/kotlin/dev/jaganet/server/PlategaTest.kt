package dev.jaganet.server

import dev.jaganet.api.ApiException
import dev.jaganet.api.CreateOrderReq
import dev.jaganet.api.OrderStatus
import dev.jaganet.api.PlanId
import dev.jaganet.server.services.Channel
import dev.jaganet.server.services.PlategaConfig
import dev.jaganet.server.services.PlategaHttp
import dev.jaganet.server.services.PlategaProvider
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.double
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Platega (issue #154) with a stand-in for Platega's API: no real money, no network. */
class PlategaTest {
    private val env = mapOf(
        "PAYMENT_PROVIDER" to "platega", "PLATEGA_MERCHANT_ID" to MERCHANT, "PLATEGA_SECRET" to SECRET, "PLATEGA_PAYMENT_METHOD" to "2",
        "PUBLIC_URL" to "https://boop.example",
    )

    /** What Platega was asked, and what it answers. */
    class FakePlatega {
        data class Req(val method: String, val url: String, val headers: Map<String, String>, val body: JsonObject?)
        val requests = mutableListOf<Req>()
        var nextTx = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
        var createCode = 200
        /** The answer of GET /transaction/{id}: status, amount, currency. */
        var status: Triple<String, Double, String>? = null
        val http = PlategaHttp { method, url, headers, body ->
            requests += Req(method, url, headers, body?.let { Json.parseToJsonElement(it).jsonObject })
            when {
                method == "POST" && createCode != 200 -> createCode to """{"message":"bad"}"""
                method == "POST" -> 200 to """{"paymentMethod":"SBPQR","transactionId":"$nextTx","redirect":"https://pay.platega.io?id=$nextTx","status":"PENDING","expiresIn":"00:15:00"}"""
                else -> status?.let { (st, amount, cur) ->
                    200 to """{"id":"${url.substringAfterLast('/')}","status":"$st","paymentDetails":{"amount":$amount,"currency":"$cur"},"payload":"x"}"""
                } ?: (404 to "")
            }
        }
    }

    private fun Harness.platega(): FakePlatega {
        val fake = FakePlatega()
        (services.payments.providers[PlategaProvider.ID] as PlategaProvider).http = fake.http
        return fake
    }

    private suspend fun Harness.callback(body: String, merchant: String? = MERCHANT, secret: String? = SECRET): HttpResponse =
        b.client.post(PlategaProvider.CALLBACK_PATH) {
            merchant?.let { header("X-MerchantId", it) }
            secret?.let { header("X-Secret", it) }
            header("User-Agent", "Platega Payment")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun confirmed(tx: String, amount: String = "300.00", currency: String = "RUB", status: String = "CONFIRMED", payload: String = "") =
        """{"id":"$tx","amount":$amount,"currency":"$currency","status":"$status","paymentMethod":2,"payload":"$payload"}"""

    private suspend fun Harness.paidCount(userId: String) =
        ctx.db.run { it.one("SELECT count(*)::int AS n FROM subscriptions WHERE user_id=?::uuid", userId)!!.int("n") }

    @Test fun `creating an order creates a Platega payment with the merchant headers and the buyer's e-mail`() = harness(env) {
        val fake = platega()
        val a = signIn("buyer@example.com")
        val order = a.api.createOrder(CreateOrderReq(tariff(MONTH)))
        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals("https://pay.platega.io?id=${fake.nextTx}", order.checkoutUrl)

        val req = fake.requests.single()
        assertEquals("POST", req.method)
        assertEquals("${PlategaConfig.DEFAULT_API}/transaction/process", req.url)
        assertEquals(MERCHANT, req.headers["X-MerchantId"])
        assertEquals(SECRET, req.headers["X-Secret"])
        val body = req.body!!
        assertNull(body["id"], "Platega gives the id")
        assertEquals(2, body["paymentMethod"]!!.jsonPrimitive.int)
        val details = body["paymentDetails"]!!.jsonObject
        assertEquals(300.0, details["amount"]!!.jsonPrimitive.double)
        assertEquals("RUB", details["currency"]!!.jsonPrimitive.content)
        assertEquals(order.id, body["payload"]!!.jsonPrimitive.content)
        assertEquals(order.id, body["orderId"]!!.jsonPrimitive.content)
        assertEquals("https://boop.example/pay/return/${order.id}", body["return"]!!.jsonPrimitive.content)
        assertEquals("https://boop.example/pay/return/${order.id}", body["failedUrl"]!!.jsonPrimitive.content)
        val meta = body["metadata"]!!.jsonObject
        assertEquals("buyer@example.com", meta["user_email"]!!.jsonPrimitive.content)
        assertEquals(a.session.user.id, meta["userId"]!!.jsonPrimitive.content)

        // The transaction id is stored with the order; the order keeps its link while pending.
        val row = ctx.db.run { it.one("SELECT provider, external_id, checkout_url FROM orders WHERE id=?::uuid", order.id)!! }
        assertEquals("platega", row.str("provider"))
        assertEquals(fake.nextTx, row.str("external_id"))
        assertEquals(order.checkoutUrl, a.api.order(order.id).checkoutUrl)
        // The same buyer is the same e-mail next time.
        fake.nextTx = UUID.randomUUID().toString()
        a.api.createOrder(CreateOrderReq(tariff(YEAR)))
        assertEquals("buyer@example.com", fake.requests.last().body!!["metadata"]!!.jsonObject["user_email"]!!.jsonPrimitive.content)
        // Never sent to clients: the secret is not in any answer.
        assertFalse(SECRET in b.client.get("/v1/site").bodyAsTextSafe())
    }

    @Test fun `without a payment method the buyer picks one on Platega's page`() = harness(env - "PLATEGA_PAYMENT_METHOD") {
        val fake = platega()
        val a = signIn("pick@example.com")
        a.api.createOrder(CreateOrderReq(tariff(MONTH)))
        val req = fake.requests.single()
        assertEquals("${PlategaConfig.DEFAULT_API}/v2/transaction/process", req.url)
        assertNull(req.body!!["paymentMethod"])
    }

    @Test fun `a Telegram buyer without e-mail is their numeric Telegram id at telegram org`() = harness(env) {
        val fake = platega()
        val tg = dev.jaganet.server.telegram.TelegramAccounts(ctx, services.auth, services.tunnels).findOrCreate(dev.jaganet.server.telegram.TgUser(5512345678, "ivan", "Ivan", null))
        services.payments.create(tg.userId, tariff(MONTH), Channel.TELEGRAM)
        fake.nextTx = UUID.randomUUID().toString()
        services.payments.create(tg.userId, tariff(YEAR), Channel.TELEGRAM)
        assertEquals(
            listOf("5512345678@telegram.org", "5512345678@telegram.org"),
            fake.requests.map { it.body!!["metadata"]!!.jsonObject["user_email"]!!.jsonPrimitive.content },
        )
    }

    @Test fun `a CONFIRMED callback gives access exactly once`() = harness(env) {
        val fake = platega()
        val a = signIn("paid@example.com")
        val order = a.api.createOrder(CreateOrderReq(tariff(MONTH)))
        assertEquals(PlanId.FREE, a.api.me().entitlement.plan)

        assertEquals(HttpStatusCode.OK, callback(confirmed(fake.nextTx, payload = order.id)).status)
        assertEquals(OrderStatus.PAID, a.api.order(order.id).status)
        assertNull(a.api.order(order.id).checkoutUrl)
        assertEquals(PlanId.PRO, a.api.me().entitlement.plan)
        val until = a.api.me().entitlement.expiresAt
        assertEquals(1, paidCount(a.session.user.id))
        assertEquals(1, a.api.keys().keys.size)

        // Delivered again (a retry, or CONFIRMED after a dispute was lifted): nothing more.
        assertEquals(HttpStatusCode.OK, callback(confirmed(fake.nextTx, payload = order.id)).status)
        assertEquals(HttpStatusCode.OK, callback(confirmed(fake.nextTx, payload = order.id)).status)
        assertEquals(1, paidCount(a.session.user.id))
        assertEquals(until, a.api.me().entitlement.expiresAt)

        // A late CANCELED does not undo the payment.
        assertEquals(HttpStatusCode.OK, callback(confirmed(fake.nextTx, status = "CANCELED")).status)
        assertEquals(OrderStatus.PAID, a.api.order(order.id).status)
        assertEquals("CONFIRMED", providerStatus(order.id))
    }

    @Test fun `a callback without the right headers is refused and gives nothing`() = harness(env) {
        val fake = platega()
        val a = signIn("forged@example.com")
        val order = a.api.createOrder(CreateOrderReq(tariff(MONTH)))
        val body = confirmed(fake.nextTx, payload = order.id)
        assertEquals(HttpStatusCode.Unauthorized, callback(body, merchant = null, secret = null).status)
        assertEquals(HttpStatusCode.Unauthorized, callback(body, secret = null).status)
        assertEquals(HttpStatusCode.Unauthorized, callback(body, secret = "wrong-secret").status)
        assertEquals(HttpStatusCode.Unauthorized, callback(body, merchant = UUID.randomUUID().toString()).status)
        assertEquals(OrderStatus.PENDING, a.api.order(order.id).status)
        assertEquals(PlanId.FREE, a.api.me().entitlement.plan)
        assertEquals(0, ctx.db.run { it.one("SELECT count(*)::int AS n FROM payment_events")!!.int("n") })
        // Not JSON / no id: a bad request.
        assertEquals(HttpStatusCode.BadRequest, callback("not json").status)
        assertEquals(HttpStatusCode.BadRequest, callback("""{"status":"CONFIRMED"}""").status)
    }

    @Test fun `a payment whose amount or currency differs from the order gives no access`() = harness(env) {
        val fake = platega()
        val a = signIn("cheap@example.com")
        val order = a.api.createOrder(CreateOrderReq(tariff(MONTH)))
        assertEquals(HttpStatusCode.OK, callback(confirmed(fake.nextTx, amount = "1.00")).status)
        assertEquals(HttpStatusCode.OK, callback(confirmed(fake.nextTx, currency = "USDT")).status)
        assertEquals(OrderStatus.PENDING, a.api.order(order.id).status)
        assertEquals(PlanId.FREE, a.api.me().entitlement.plan)
        assertEquals("AMOUNT_MISMATCH", providerStatus(order.id))
        // The admin panel shows it.
        val p = adminPayments().single { it.id == order.id }
        assertEquals("AMOUNT_MISMATCH", p.providerStatus)
        assertEquals("platega", p.provider)
    }

    @Test fun `CANCELED and provider errors give no access`() = harness(env) {
        val fake = platega()
        val a = signIn("cancel@example.com")
        val order = a.api.createOrder(CreateOrderReq(tariff(MONTH)))
        assertEquals(HttpStatusCode.OK, callback(confirmed(fake.nextTx, status = "CANCELED")).status)
        assertEquals(OrderStatus.CANCELLED, a.api.order(order.id).status)
        assertEquals(PlanId.FREE, a.api.me().entitlement.plan)

        // Platega refuses to create the payment: the buyer is told payments are unavailable, the order is cancelled.
        fake.createCode = 401
        val e = runCatching { a.api.createOrder(CreateOrderReq(tariff(MONTH))) }.exceptionOrNull() as ApiException
        assertEquals(dev.jaganet.api.ErrorCode.SERVICE_UNAVAILABLE, e.code)
        val failed = ctx.db.run { it.one("SELECT status, provider_status FROM orders WHERE user_id=?::uuid ORDER BY created_at DESC, id LIMIT 1", a.session.user.id)!! }
        assertEquals("cancelled", failed.str("status"))
        assertEquals("CREATE_FAILED", failed.str("provider_status"))
        assertEquals(PlanId.FREE, a.api.me().entitlement.plan)

        // A callback for a payment we never made: an error, so Platega delivers it again later.
        assertEquals(HttpStatusCode.NotFound, callback(confirmed(UUID.randomUUID().toString())).status)
    }

    @Test fun `CONFIRMED after an out-of-order CANCELED still gives access`() = harness(env) {
        val fake = platega()
        val a = signIn("order@example.com")
        val order = a.api.createOrder(CreateOrderReq(tariff(MONTH)))
        callback(confirmed(fake.nextTx, status = "CANCELED"))
        callback(confirmed(fake.nextTx))
        assertEquals(OrderStatus.PAID, a.api.order(order.id).status)
        assertEquals(PlanId.PRO, a.api.me().entitlement.plan)
    }

    @Test fun `chargebacks refunds and disputes are kept and shown without changing access`() = harness(env) {
        val fake = platega()
        val a = signIn("refund@example.com")
        val order = a.api.createOrder(CreateOrderReq(tariff(MONTH)))
        callback(confirmed(fake.nextTx))
        for (st in listOf("DISPUTED", "PARTIALLY_REFUNDED", "CHARGEBACKED")) {
            assertEquals(HttpStatusCode.OK, callback(confirmed(fake.nextTx, status = st)).status)
            assertEquals(st, providerStatus(order.id))
        }
        assertEquals(OrderStatus.PAID, a.api.order(order.id).status)
        assertEquals(PlanId.PRO, a.api.me().entitlement.plan)
        val events = ctx.db.run { it.query("SELECT status, outcome FROM payment_events ORDER BY id").map { r -> r.str("status") + ":" + r.str("outcome") } }
        assertEquals(listOf("CONFIRMED:paid", "DISPUTED:recorded", "PARTIALLY_REFUNDED:recorded", "CHARGEBACKED:recorded"), events)
        assertEquals("CHARGEBACKED", adminPayments().single { it.id == order.id }.providerStatus)
    }

    @Test fun `when the callback never comes the order is checked at Platega`() = harness(env) {
        val fake = platega()
        val a = signIn("poll@example.com")
        val order = a.api.createOrder(CreateOrderReq(tariff(MONTH)))
        // Still pending at Platega: nothing changes.
        fake.status = Triple("PENDING", 300.0, "RUB")
        assertEquals(OrderStatus.PENDING, a.api.order(order.id).status)
        assertEquals("GET", fake.requests.last().method)
        assertEquals("${PlategaConfig.DEFAULT_API}/transaction/${fake.nextTx}", fake.requests.last().url)
        assertEquals(SECRET, fake.requests.last().headers["X-Secret"])
        // Asked again only after a pause.
        fake.status = Triple("CONFIRMED", 300.0, "RUB")
        assertEquals(OrderStatus.PENDING, a.api.order(order.id).status)
        clock = clock.plusSeconds(20)
        assertEquals(OrderStatus.PAID, a.api.order(order.id).status)
        assertEquals(PlanId.PRO, a.api.me().entitlement.plan)
        // The late callback changes nothing.
        callback(confirmed(fake.nextTx))
        assertEquals(1, paidCount(a.session.user.id))
    }

    @Test fun `the return page goes back to the account`() = harness(env) {
        val fake = platega()
        val a = signIn("back@example.com")
        val order = a.api.createOrder(CreateOrderReq(tariff(MONTH)))
        val r = b.createClient { followRedirects = false }.get("/pay/return/${order.id}")
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/#/account?order=${order.id}", r.headers["Location"])
        assertNotNull(fake.requests.single())
    }

    @Test fun `without the merchant id and secret Platega stays off`() = harness(env - "PLATEGA_SECRET") {
        assertNull(services.payments.providers[PlategaProvider.ID])
        assertNull(services.payments.provider)
        assertEquals(HttpStatusCode.NotFound, callback(confirmed(UUID.randomUUID().toString())).status)
        val a = signIn("off@example.com")
        assertFalse(a.api.plans().paymentsEnabled)
    }

    @Test fun `the config hides the secret`() {
        val c = PlategaConfig.load(env)!!
        assertFalse(SECRET in c.toString())
        assertFalse(SECRET in Config.load(env + ("JAGANET_MODE" to "test")).toString())
        assertTrue(PlategaConfig.load(env - "PLATEGA_MERCHANT_ID") == null)
    }

    private suspend fun Harness.providerStatus(orderId: String) =
        ctx.db.run { it.one("SELECT provider_status FROM orders WHERE id=?::uuid", orderId)!!.strOrNull("provider_status") }

    private suspend fun Harness.adminPayments() = services.control.payments(services.control.filter(null, null, null, null)).payments

    private suspend fun HttpResponse.bodyAsTextSafe() = bodyAsText()

    companion object {
        const val MERCHANT = "1a021d91-9b26-4762-b303-5d4aac74e921"
        const val SECRET = "s3cr3t-platega-key-for-tests"
    }
}
