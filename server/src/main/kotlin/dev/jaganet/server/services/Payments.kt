package dev.jaganet.server.services

import dev.jaganet.api.ErrorCode
import dev.jaganet.api.Format
import dev.jaganet.api.OrderRes
import dev.jaganet.api.OrderStatus
import dev.jaganet.api.DurationUnit
import dev.jaganet.api.Protocols
import dev.jaganet.api.i18n.Lang
import dev.jaganet.server.AppError
import dev.jaganet.server.Ctx
import dev.jaganet.server.db.Jsonb
import dev.jaganet.server.db.Row
import dev.jaganet.server.notFound
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

enum class Channel(val id: String) { WEB("web"), TELEGRAM("telegram"), APP("app") }

data class Order(
    val id: String,
    val userId: String,
    /** The tariff's terms when the order was placed. */
    val terms: Terms,
    val channel: Channel,
    val amountMinor: Long,
    val currency: String,
    val status: OrderStatus,
    val provider: String = "test",
    /** The provider's payment id (Platega's transaction id). */
    val externalId: String? = null,
    /** The page the provider gave for this payment; null for providers that work it out ([PaymentProvider.checkoutUrl]). */
    val checkoutUrl: String? = null,
    /** The provider's own status of the payment, e.g. CONFIRMED or CHARGEBACKED. */
    val providerStatus: String? = null,
)

/**
 * A payment service (card processor, Telegram Stars, crypto…). Adding one = implement
 * this, register it in [Payments.providers], and on its "paid" webhook call [Payments.providerUpdate]
 * (or [Payments.markPaid] directly for a provider without statuses of its own).
 */
interface PaymentProvider {
    val id: String
    /** Where the buyer pays, for a provider whose page is worked out from the order (test). Null = the link [start] gave. */
    fun checkoutUrl(order: Order): String? = null
    /**
     * Called once, right after the order is saved: a provider that creates the payment on its side does it
     * here and returns its id and link. Null = nothing to create. Throws [ProviderError] when the provider failed.
     */
    suspend fun start(order: Order, buyer: Buyer): Started? = null
    /** Asks the provider about a payment the callback may have missed. Null = it doesn't know it (or can't tell). */
    suspend fun check(order: Order, externalId: String): ProviderUpdate? = null
}

/** Who pays, as the provider is told: a stable [email] (the account's, or `{telegram id}@telegram.org`) and our user id. */
data class Buyer(val userId: String, val email: String)

/** The payment the provider created: its id and the page where the buyer pays. */
data class Started(val externalId: String, val checkoutUrl: String)

/** What a provider says about a payment (a callback or a status check), after the sender was verified. */
data class ProviderUpdate(
    val externalId: String,
    /** The provider's status, upper case: CONFIRMED, CANCELED, CHARGEBACKED, PARTIALLY_REFUNDED, DISPUTED, PENDING… */
    val status: String,
    val amountMinor: Long?,
    val currency: String?,
    /** Our order id, as sent when the payment was made. */
    val payload: String?,
    val body: kotlinx.serialization.json.JsonObject? = null,
)

/** What [Payments.providerUpdate] did. */
enum class UpdateOutcome(val id: String) {
    PAID("paid"), ALREADY_PAID("already_paid"), CANCELLED("cancelled"), MISMATCH("mismatch"), RECORDED("recorded"), UNKNOWN_ORDER("unknown_order"),
}

/** No real money: a page on our own site with a "Pay" button. For trying the flow end to end. */
class TestPaymentProvider(private val publicUrl: String) : PaymentProvider {
    override val id = "test"
    override fun checkoutUrl(order: Order) = "${publicUrl.trimEnd('/')}/pay/test/${order.id}"
}

/** Told about every paid order, after the subscription and key exist. */
fun interface PaidListener {
    suspend fun onPaid(order: Order, key: dev.jaganet.api.AccessKey?, proUntil: Instant)
}

/**
 * Sales on the website, in the Telegram bot and in the app, all through our payment service.
 * After payment the buyer gets Pro on the tariff's terms plus a VPN key, so they can use any AmneziaWG app.
 */
class Payments(
    private val ctx: Ctx, private val billing: Billing, private val keys: Keys, private val tariffs: Tariffs,
    /** "Invite a friend" rewards (issue #40), given in the payment's transaction. */
    private val rewards: ReferralRewards? = null,
) {
    private val log = LoggerFactory.getLogger("payments")
    val listeners = CopyOnWriteArrayList<PaidListener>()

    val providers: Map<String, PaymentProvider> = listOfNotNull<PaymentProvider>(
        TestPaymentProvider(ctx.cfg.publicUrl),
        // Only with PLATEGA_MERCHANT_ID and PLATEGA_SECRET (issue #154).
        ctx.cfg.platega?.let { PlategaProvider(it, ctx.cfg.publicUrl) },
    ).associateBy { it.id }

    val platega: PlategaProvider? get() = providers[PlategaProvider.ID] as? PlategaProvider

    /** When each pending order was last asked about at its provider (the callback may not have arrived). */
    private val lastCheck = java.util.concurrent.ConcurrentHashMap<String, Instant>()

    val provider: PaymentProvider? get() = ctx.live.paymentProvider?.let { providers[it] }
    val isTest get() = provider?.id == "test"

    /** An order in the buyer's currency: rubles for Russian, euros for German and English. */
    suspend fun create(userId: String, tariffId: String, channel: Channel, lang: Lang = Lang.DEFAULT): OrderRes {
        val provider = provider ?: throw AppError(503, ErrorCode.SERVICE_UNAVAILABLE, "Payments are temporarily unavailable")
        val currency = ctx.live.plans.currencyFor(lang)
        val row = ctx.db.tx { sql ->
            val t = tariffs.forSale(sql, tariffId) ?: throw notFound("This plan is no longer on sale")
            sql.one(
                """INSERT INTO orders (user_id, product_id, channel, provider, amount_minor, currency, tariff_id, terms)
                   VALUES (?::uuid,?,?,?,?,?,?::uuid,?) RETURNING *""",
                userId, tariffId, channel.id, provider.id, Tariffs.price(t, currency), currency, tariffId,
                Jsonb(Protocols.encode(Tariffs.terms(t)).toString()),
            )!!
        }
        val order = row.toOrder()
        // A provider that makes the payment on its side (Platega): outside the transaction, it is an HTTP call.
        val started = try {
            provider.start(order, buyer(userId))
        } catch (e: Exception) {
            // The order can never be paid: cancel it, so it isn't left waiting. No access is given.
            log.error("${provider.id}: could not create the payment for order ${order.id}: ${e.message}")
            ctx.db.run {
                it.exec(
                    "UPDATE orders SET status='cancelled', provider_status='CREATE_FAILED', provider_status_at=? WHERE id=?::uuid AND status='pending'",
                    ctx.now(), order.id,
                )
            }
            throw AppError(503, ErrorCode.SERVICE_UNAVAILABLE, "Payments are temporarily unavailable")
        } ?: return res(order)
        val updated = ctx.db.run {
            it.one(
                "UPDATE orders SET external_id=?, checkout_url=? WHERE id=?::uuid RETURNING *",
                started.externalId, started.checkoutUrl, order.id,
            )!!
        }
        return res(updated.toOrder())
    }

    /** The stable id the provider knows the buyer by: the account's e-mail, or `{telegram id}@telegram.org` for Telegram-only accounts. */
    suspend fun buyer(userId: String): Buyer {
        val r = ctx.db.run { it.one("SELECT email, telegram_id FROM users WHERE id=?::uuid", userId) } ?: throw notFound("Account not found")
        val email = r.str("email")
        val tg = r.longOrNull("telegram_id")
        val id = if (dev.jaganet.server.telegram.TelegramAccounts.isPlaceholder(email) && tg != null) "$tg@telegram.org" else email
        return Buyer(userId, id)
    }

    suspend fun get(id: String): Order? = runCatching { java.util.UUID.fromString(id) }.getOrNull()?.let {
        ctx.db.run { sql -> sql.one("SELECT * FROM orders WHERE id=?::uuid", id) }?.toOrder()
    }

    suspend fun getFor(userId: String, id: String): OrderRes {
        val o = get(id)?.takeIf { it.userId == userId } ?: throw notFound("Order not found")
        return res(refresh(o))
    }

    /**
     * The fallback when the provider's callback didn't arrive: a pending order with a provider payment is
     * checked at the provider, at most every [CHECK_EVERY] per order (the account and the mini app keep asking).
     */
    suspend fun refresh(o: Order): Order {
        if (o.status != OrderStatus.PENDING) return o
        val tx = o.externalId ?: return o
        val provider = providers[o.provider] ?: return o
        val now = ctx.now()
        val last = lastCheck[o.id]
        if (last != null && last.plus(CHECK_EVERY).isAfter(now)) return o
        lastCheck[o.id] = now
        if (lastCheck.size > 10_000) lastCheck.entries.removeIf { it.value.plus(CHECK_EVERY).isBefore(now) }
        val u = runCatching { provider.check(o, tx) }.onFailure { log.warn("${o.provider}: status check of order ${o.id} failed: ${it.message}") }.getOrNull() ?: return o
        // Only a real change: a check that still says PENDING is not stored.
        if (u.status == "PENDING" || u.externalId != tx) return o
        providerUpdate(o.provider, u)
        return get(o.id) ?: o
    }

    fun res(o: Order) = OrderRes(
        o.id, o.terms.name, o.status, Format.money(o.amountMinor, o.currency),
        checkoutUrl = if (o.status == OrderStatus.PENDING) o.checkoutUrl ?: providers[ctx.live.paymentProvider]?.checkoutUrl(o) else null,
    )

    /**
     * What the provider said about one of its payments (a verified callback, or a status check). Idempotent by
     * the provider's payment id: a repeat changes nothing, and the order is paid at most once.
     * - CONFIRMED: the only status that gives access, and only when amount and currency match the order;
     *   also for an order cancelled earlier (callbacks may arrive out of order).
     * - CANCELED: a pending order is cancelled; a paid one stays paid.
     * - CHARGEBACKED, PARTIALLY_REFUNDED, DISPUTED (and anything else): stored and shown in the admin panel, access unchanged.
     */
    suspend fun providerUpdate(providerId: String, u: ProviderUpdate): UpdateOutcome {
        val now = ctx.now()
        val row = ctx.db.run { sql ->
            sql.one("SELECT * FROM orders WHERE provider=? AND external_id=?", providerId, u.externalId)
                ?: u.payload?.let { p -> runCatching { java.util.UUID.fromString(p) }.getOrNull() }?.let {
                    // The callback came before the payment id was saved: the order id we sent as payload.
                    sql.one("SELECT * FROM orders WHERE id=?::uuid AND provider=? AND (external_id IS NULL OR external_id=?)", it.toString(), providerId, u.externalId)
                }
        }
        val order = row?.toOrder()
        if (order == null) {
            log.warn("$providerId: ${u.status} for unknown payment ${u.externalId}")
            event(providerId, u, null, UpdateOutcome.UNKNOWN_ORDER)
            return UpdateOutcome.UNKNOWN_ORDER
        }
        val matches = u.amountMinor == order.amountMinor && u.currency?.uppercase() == order.currency.uppercase()
        val outcome = when (u.status) {
            "CONFIRMED" -> if (!matches) {
                log.error("$providerId: payment ${u.externalId} of order ${order.id} is ${u.amountMinor} ${u.currency}, the order is ${order.amountMinor} ${order.currency}; no access given")
                UpdateOutcome.MISMATCH
            } else if (markPaid(order.id, u.externalId, evenIfCancelled = true) != null) UpdateOutcome.PAID else UpdateOutcome.ALREADY_PAID
            "CANCELED", "CANCELLED" -> {
                val n = ctx.db.run { it.exec("UPDATE orders SET status='cancelled' WHERE id=?::uuid AND status='pending'", order.id) }
                if (n > 0) UpdateOutcome.CANCELLED else UpdateOutcome.RECORDED
            }
            else -> {
                if (u.status in ATTENTION) log.warn("$providerId: payment ${u.externalId} of order ${order.id} is ${u.status}")
                UpdateOutcome.RECORDED
            }
        }
        // The provider's status for the admin panel. A late CANCELED or PENDING does not hide that a paid order was paid.
        val shown = if (outcome == UpdateOutcome.MISMATCH) "AMOUNT_MISMATCH" else u.status
        ctx.db.run {
            it.exec(
                """UPDATE orders SET provider_status=?, provider_status_at=?, external_id=coalesce(external_id, ?)
                    WHERE id=?::uuid AND NOT (status='paid' AND ? IN ('CANCELED','CANCELLED','PENDING'))""",
                shown, now, u.externalId, order.id, u.status,
            )
        }
        event(providerId, u, order.id, outcome)
        return outcome
    }

    private suspend fun event(providerId: String, u: ProviderUpdate, orderId: String?, outcome: UpdateOutcome) = ctx.db.run {
        it.exec(
            """INSERT INTO payment_events (provider, external_id, order_id, status, amount_minor, currency, outcome, body, received_at)
               VALUES (?,?,?::uuid,?,?,?,?,?,?) ON CONFLICT DO NOTHING""",
            providerId, u.externalId, orderId, u.status, u.amountMinor, u.currency, outcome.id, u.body?.let { b -> Jsonb(b.toString()) }, ctx.now(),
        )
    }

    /**
     * The provider confirmed payment. Idempotent: a repeated webhook does nothing.
     * Extends Pro on the order's frozen terms (starting after any time left), makes sure the buyer has a VPN key,
     * then tells the listeners (the Telegram bot sends the key to the chat).
     */
    suspend fun markPaid(orderId: String, externalId: String? = null, evenIfCancelled: Boolean = false): Order? {
        val (order, proUntil) = ctx.db.tx { sql ->
            val row = sql.one(
                "UPDATE orders SET status='paid', paid_at=?, external_id=coalesce(?, external_id) WHERE id=?::uuid AND status IN ('pending', ?) RETURNING *",
                ctx.now(), externalId, orderId, if (evenIfCancelled) "cancelled" else "pending",
            ) ?: return@tx null
            val o = row.toOrder()
            val now = ctx.now()
            val last = sql.one("SELECT max(expires_at) AS e FROM subscriptions WHERE user_id=?::uuid AND status='active'", o.userId)?.instantOrNull("e")
            val start = maxOf(now, last ?: now)
            val until = o.terms.end(start)
            billing.recordPaidSubscription(sql, o.userId, o.terms, o.channel.id, "order:${o.id}", start, until)
            // The inviter's +14 days on this person's first payment; at most once (unique per invited person).
            rewards?.onPaid(sql, o.userId, o.id)
            o to until
        } ?: return null
        // A key problem (server full…) must not lose the payment: the buyer can make one later.
        val key = runCatching { keys.ensure(order.userId) }.onFailure { log.error("no key for order ${order.id}", it) }.getOrNull()
        for (l in listeners) runCatching { l.onPaid(order, key, proUntil) }.onFailure { log.error("paid listener failed", it) }
        rewards?.deliverSoon()
        return order
    }

    private fun Row.toOrder() = Order(
        str("id"), str("user_id"), jsonOrNull("terms")?.let { Protocols.decode<Terms>(it) } ?: legacyTerms(str("product_id")),
        Channel.entries.first { it.id == str("channel") },
        long("amount_minor"), str("currency"), OrderStatus.valueOf(str("status").uppercase()),
        provider = str("provider"), externalId = strOrNull("external_id"), checkoutUrl = strOrNull("checkout_url"),
        providerStatus = strOrNull("provider_status"),
    )

    companion object {
        val CHECK_EVERY: java.time.Duration = java.time.Duration.ofSeconds(15)
        /** Statuses after payment that the owner should look at (see docs/PAYMENTS-PLATEGA.md). */
        val ATTENTION = setOf("CHARGEBACKED", "PARTIALLY_REFUNDED", "DISPUTED")
    }

    /** Orders placed before the tariff builder. */
    private fun legacyTerms(product: String) = Terms(
        null, "Pro", if (product == "pro_yearly") 12 else 1, DurationUnit.MONTHS, ctx.live.plans.proDeviceLimit,
    )
}
