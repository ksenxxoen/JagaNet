package dev.jaganet.server.services

import dev.jaganet.api.ErrorCode
import dev.jaganet.api.Format
import dev.jaganet.api.OrderRes
import dev.jaganet.api.OrderStatus
import dev.jaganet.api.ProductId
import dev.jaganet.server.AppError
import dev.jaganet.server.Ctx
import dev.jaganet.server.db.Row
import dev.jaganet.server.notFound
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

enum class Channel(val id: String) { WEB("web"), TELEGRAM("telegram") }

data class Order(
    val id: String,
    val userId: String,
    val productId: ProductId,
    val channel: Channel,
    val amountMinor: Long,
    val currency: String,
    val status: OrderStatus,
)

/**
 * A payment service (card processor, Telegram Stars, crypto…). Adding one = implement
 * this, register it in [Payments.providers], and on its "paid" webhook call [Payments.markPaid].
 */
interface PaymentProvider {
    val id: String
    /** Where the buyer pays. */
    fun checkoutUrl(order: Order): String
}

/** No real money: a page on our own site with a "Pay" button. For trying the flow end to end. */
class TestPaymentProvider(private val publicUrl: String) : PaymentProvider {
    override val id = "test"
    override fun checkoutUrl(order: Order) = "${publicUrl.trimEnd('/')}/pay/test/${order.id}"
}

/** Told about every paid website / Telegram order, after the subscription and key exist. */
fun interface PaidListener {
    suspend fun onPaid(order: Order, key: dev.jaganet.api.AccessKey?, proUntil: Instant)
}

/**
 * Website and Telegram sales. (In-app purchases go through the stores and Billing directly,
 * and activate the plan at once; nothing to deliver there.)
 * After payment the buyer gets Pro plus a VPN key, so they can use any AmneziaWG app.
 */
class Payments(private val ctx: Ctx, private val billing: Billing, private val keys: Keys) {
    private val log = LoggerFactory.getLogger("payments")
    val listeners = CopyOnWriteArrayList<PaidListener>()

    val providers: Map<String, PaymentProvider> = listOfNotNull<PaymentProvider>(
        TestPaymentProvider(ctx.cfg.publicUrl),
    ).associateBy { it.id }

    val provider: PaymentProvider? get() = ctx.cfg.paymentProvider?.let { providers[it] }
    val isTest get() = provider?.id == "test"

    private fun price(p: ProductId): Long? = when (p) {
        ProductId.PRO_MONTHLY -> ctx.cfg.plans.priceMonthlyMinor
        ProductId.PRO_YEARLY -> ctx.cfg.plans.priceYearlyMinor
    }

    suspend fun create(userId: String, productId: ProductId, channel: Channel): OrderRes {
        val provider = provider ?: throw AppError(501, ErrorCode.NOT_IMPLEMENTED, "Payments are not set up yet")
        val amount = price(productId) ?: throw AppError(501, ErrorCode.NOT_IMPLEMENTED, "No price set for this plan")
        val row = ctx.db.run { sql ->
            sql.one(
                """INSERT INTO orders (user_id, product_id, channel, provider, amount_minor, currency)
                   VALUES (?::uuid,?,?,?,?,?) RETURNING *""",
                userId, productId.name.lowercase(), channel.id, provider.id, amount, ctx.cfg.plans.currency,
            )!!
        }
        return res(row.toOrder())
    }

    suspend fun get(id: String): Order? = runCatching { java.util.UUID.fromString(id) }.getOrNull()?.let {
        ctx.db.run { sql -> sql.one("SELECT * FROM orders WHERE id=?::uuid", id) }?.toOrder()
    }

    suspend fun getFor(userId: String, id: String): OrderRes =
        get(id)?.takeIf { it.userId == userId }?.let(::res) ?: throw notFound("Order not found")

    fun res(o: Order) = OrderRes(
        o.id, o.productId, o.status, Format.money(o.amountMinor, o.currency),
        checkoutUrl = if (o.status == OrderStatus.PENDING) providers[ctx.cfg.paymentProvider]?.checkoutUrl(o) else null,
    )

    /**
     * The provider confirmed payment. Idempotent: a repeated webhook does nothing.
     * Extends Pro (stacking after any time left), makes sure the buyer has a VPN key,
     * then tells the listeners (the Telegram bot sends the key to the chat).
     */
    suspend fun markPaid(orderId: String, externalId: String? = null): Order? {
        val (order, proUntil) = ctx.db.tx { sql ->
            val row = sql.one(
                "UPDATE orders SET status='paid', paid_at=?, external_id=? WHERE id=?::uuid AND status='pending' RETURNING *",
                ctx.now(), externalId, orderId,
            ) ?: return@tx null
            val o = row.toOrder()
            val now = ctx.now()
            val last = sql.one("SELECT max(expires_at) AS e FROM subscriptions WHERE user_id=?::uuid AND status='active'", o.userId)?.instantOrNull("e")
            val start = maxOf(now, last ?: now)
            val until = start.plus(Duration.ofDays(o.productId.periodDays.toLong()))
            billing.recordPaidSubscription(sql, o.userId, o.productId, o.channel.id, "order:${o.id}", start, until)
            o to until
        } ?: return null
        // A key problem (server full…) must not lose the payment: the buyer can make one later.
        val key = runCatching { keys.ensure(order.userId) }.onFailure { log.error("no key for order ${order.id}", it) }.getOrNull()
        for (l in listeners) runCatching { l.onPaid(order, key, proUntil) }.onFailure { log.error("paid listener failed", it) }
        return order
    }

    private fun Row.toOrder() = Order(
        str("id"), str("user_id"), productOf(str("product_id"))!!, Channel.entries.first { it.id == str("channel") },
        long("amount_minor"), str("currency"), OrderStatus.valueOf(str("status").uppercase()),
    )
}
