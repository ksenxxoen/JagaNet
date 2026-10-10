package dev.jaganet.server.services

import dev.jaganet.api.FreePlan
import dev.jaganet.api.PlansRes
import dev.jaganet.api.i18n.Lang
import dev.jaganet.server.Ctx
import dev.jaganet.server.db.Sql
import java.time.Duration
import java.time.Instant

class Billing(private val ctx: Ctx, private val tariffs: Tariffs) {
    /** Tariffs on sale with prices in the currency of [lang] (rubles for Russian, euros otherwise). */
    suspend fun plans(lang: Lang = Lang.DEFAULT): PlansRes {
        val p = ctx.live.plans
        return PlansRes(tariffs.onSale(lang), FreePlan(p.freeMonthlyBytes, p.freeDeviceLimit), paymentsEnabled = ctx.live.paymentProvider != null)
    }

    /**
     * Record a paid purchase with its frozen [terms]. Every payment ends here, so plan
     * rules (renewals, referral rewards) live in one place.
     */
    fun recordPaidSubscription(sql: Sql, userId: String, terms: Terms, source: String, externalId: String, startedAt: Instant, expiresAt: Instant) {
        val isFirstPaid = sql.one("SELECT count(*)::int AS n FROM subscriptions WHERE user_id=?::uuid AND source <> 'referral'", userId)!!.int("n") == 0
        sql.exec(
            """INSERT INTO subscriptions (user_id, product_id, source, external_id, started_at, expires_at, is_renewal, auto_renew,
                                          tariff_id, tariff_name, device_limit, monthly_bytes)
               VALUES (?::uuid,?,?,?,?,?,?,false,?::uuid,?,?,?)
               ON CONFLICT (source, external_id) DO UPDATE SET expires_at=EXCLUDED.expires_at, status='active'""",
            userId, terms.tariffId ?: "legacy", source, externalId, startedAt, expiresAt, !isFirstPaid,
            terms.tariffId, terms.name, terms.deviceLimit, terms.monthlyBytes,
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
                beneficiary, "ref:$from->$beneficiary", start, start.plus(Duration.ofDays(ctx.live.plans.referralRewardDays.toLong())),
            )
        }
    }
}
