package dev.jaganet.server.services

import dev.jaganet.api.ErrorCode
import dev.jaganet.api.FreePlan
import dev.jaganet.api.Format
import dev.jaganet.api.PlansRes
import dev.jaganet.api.ProPlan
import dev.jaganet.api.Product
import dev.jaganet.api.ProductId
import dev.jaganet.api.i18n.Lang
import dev.jaganet.server.AppError
import dev.jaganet.server.Ctx
import dev.jaganet.server.Mode
import dev.jaganet.server.db.Sql
import java.time.Duration
import java.time.Instant

class Billing(private val ctx: Ctx) {
    /** Plans with prices in the currency of [lang] (rubles for Russian, euros otherwise). */
    fun plans(lang: Lang = Lang.DEFAULT): PlansRes {
        val p = ctx.cfg.plans
        val cur = p.currencyFor(lang)
        val yearly = p.price(ProductId.PRO_YEARLY, cur)
        val monthly = p.price(ProductId.PRO_MONTHLY, cur)
        fun price(minor: Long?, per: String) = (minor?.let { Format.money(it, cur, lang) } ?: "-") + "/$per"
        return PlansRes(
            products = listOf(
                Product(ProductId.PRO_YEARLY, "Pro yearly", "year", price(yearly, "yr"), "jaganet.pro.yearly", "pro_yearly", yearly, cur),
                Product(ProductId.PRO_MONTHLY, "Pro monthly", "month", price(monthly, "mo"), "jaganet.pro.monthly", "pro_monthly", monthly, cur),
            ),
            free = FreePlan(p.freeMonthlyBytes, p.freeDeviceLimit),
            pro = ProPlan(p.proDeviceLimit),
        )
    }

    /**
     * Record a verified paid purchase. Every store integration ends here, so plan
     * rules (renewals, referral rewards) live in one place.
     */
    fun recordPaidSubscription(sql: Sql, userId: String, productId: ProductId, source: String, externalId: String, startedAt: Instant, expiresAt: Instant) {
        val isFirstPaid = sql.one("SELECT count(*)::int AS n FROM subscriptions WHERE user_id=?::uuid AND source <> 'referral'", userId)!!.int("n") == 0
        sql.exec(
            """INSERT INTO subscriptions (user_id, product_id, source, external_id, started_at, expires_at, is_renewal)
               VALUES (?::uuid,?,?,?,?,?,?)
               ON CONFLICT (source, external_id) DO UPDATE SET expires_at=EXCLUDED.expires_at, status='active'""",
            userId, productId.name.lowercase(), source, externalId, startedAt, expiresAt, !isFirstPaid,
        )
        if (isFirstPaid) grantReferralRewards(sql, userId)
    }

    /** When a referred user pays for the first time, both sides get free Pro days. */
    private fun grantReferralRewards(sql: Sql, userId: String) {
        val referrer = sql.one("SELECT referred_by FROM users WHERE id=?::uuid", userId)?.strOrNull("referred_by") ?: return
        for ((beneficiary, from) in listOf(referrer to userId, userId to referrer)) {
            val last = sql.one("SELECT max(expires_at) AS e FROM subscriptions WHERE user_id=?::uuid AND status='active'", beneficiary)?.instantOrNull("e")
            val start = maxOf(ctx.now(), last ?: Instant.EPOCH)
            sql.exec(
                """INSERT INTO subscriptions (user_id, product_id, source, external_id, started_at, expires_at, auto_renew)
                   VALUES (?::uuid,'referral','referral',?,?,?,false) ON CONFLICT (source, external_id) DO NOTHING""",
                beneficiary, "ref:$from->$beneficiary", start, start.plus(Duration.ofDays(ctx.cfg.plans.referralRewardDays.toLong())),
            )
        }
    }

    /** Simulation only: pretend the store charged the user. */
    suspend fun devPurchase(p: Principal, productId: ProductId) {
        if (ctx.cfg.mode == Mode.PRODUCTION) throw AppError(404, ErrorCode.NOT_FOUND, "Not found")
        val now = ctx.now()
        ctx.db.tx { sql ->
            recordPaidSubscription(sql, p.user.id, productId, "dev", "dev-${p.user.id}-${now.toEpochMilli()}", now, now.plus(Duration.ofDays(productId.periodDays.toLong())))
        }
    }

    /*
     * TODO(store integration) — deliberately trusting nothing until implemented:
     *  - Apple: verify the JWS signedTransaction against Apple's root CA (App Store Server
     *    Library for Java), then recordPaidSubscription(); handle App Store Server Notifications V2.
     *  - Google: purchases.subscriptionsv2.get with a service account, acknowledge, then
     *    recordPaidSubscription(); handle Real-time Developer Notifications (Pub/Sub push).
     */
    fun notImplementedStore(): Nothing = throw AppError(501, ErrorCode.NOT_IMPLEMENTED, "Store verification is not set up yet")
}
