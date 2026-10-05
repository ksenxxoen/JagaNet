package dev.jaganet.server.services

import dev.jaganet.api.ReferralRes
import dev.jaganet.server.Ctx
import java.net.URLEncoder

class Referrals(private val ctx: Ctx) {
    suspend fun get(p: Principal): ReferralRes = ctx.db.run { sql ->
        val code = sql.one("SELECT referral_code FROM users WHERE id=?::uuid", p.user.id)!!.str("referral_code")
        val r = sql.one(
            """SELECT count(*)::int AS invited,
                      count(*) FILTER (WHERE EXISTS (SELECT 1 FROM subscriptions s WHERE s.user_id=u.id AND s.source <> 'referral'))::int AS subscribed
                 FROM users u WHERE u.referred_by=?::uuid""",
            p.user.id,
        )!!
        val days = sql.one(
            "SELECT coalesce(sum(extract(epoch FROM expires_at - started_at) / 86400), 0)::int AS d FROM subscriptions WHERE user_id=?::uuid AND source='referral'",
            p.user.id,
        )!!.int("d")
        ReferralRes(code, r.int("invited"), r.int("subscribed"), ctx.cfg.plans.referralRewardDays, days, "${ctx.cfg.publicUrl}/r/${URLEncoder.encode(code, "UTF-8")}")
    }
}
