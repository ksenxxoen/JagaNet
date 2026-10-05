package dev.jaganet.server.services

import dev.jaganet.api.BillingSource
import dev.jaganet.api.Entitlement
import dev.jaganet.api.MeRes
import dev.jaganet.api.Payment
import dev.jaganet.api.PaymentStatus
import dev.jaganet.api.PlanId
import dev.jaganet.api.ProductId
import dev.jaganet.api.Usage
import dev.jaganet.server.Ctx
import dev.jaganet.server.db.Sql
import java.time.Instant
import java.time.ZoneOffset

fun monthStart(t: Instant): Instant = t.atZone(ZoneOffset.UTC).withDayOfMonth(1).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC)

fun sourceOf(s: String) = BillingSource.valueOf(s.uppercase())
fun productOf(s: String) = ProductId.entries.firstOrNull { it.name.equals(s, ignoreCase = true) }

class Entitlements(private val ctx: Ctx) {
    fun entitlement(sql: Sql, userId: String): Entitlement {
        val p = ctx.cfg.plans
        // Paid subscriptions first; referral time stacks after paid time.
        val sub = sql.one(
            """SELECT * FROM subscriptions
                WHERE user_id=?::uuid AND status='active' AND started_at <= ? AND expires_at > ?
                ORDER BY (source='referral'), expires_at DESC LIMIT 1""",
            userId, ctx.now(), ctx.now(),
        ) ?: return Entitlement(PlanId.FREE, deviceLimit = p.freeDeviceLimit, monthlyDataLimitBytes = p.freeMonthlyBytes)
        val last = sql.one("SELECT max(expires_at) AS e FROM subscriptions WHERE user_id=?::uuid AND status='active'", userId)?.instantOrNull("e")
        val source = sourceOf(sub.str("source"))
        return Entitlement(
            plan = PlanId.PRO,
            productId = if (source == BillingSource.REFERRAL) null else productOf(sub.str("product_id")),
            source = source,
            expiresAt = (last ?: sub.instant("expires_at")).toString(),
            autoRenew = sub.bool("auto_renew"),
            deviceLimit = p.proDeviceLimit,
            monthlyDataLimitBytes = null,
        )
    }

    fun monthlyUsageBytes(sql: Sql, userId: String): Long = sql.one(
        """SELECT coalesce(sum(t.rx_bytes + t.tx_bytes), 0)::bigint AS total
             FROM traffic_samples t JOIN devices d ON d.id = t.device_id
            WHERE d.user_id=?::uuid AND t.bucket >= ?""",
        userId, monthStart(ctx.now()),
    )!!.long("total")

    fun activeTunnelCount(sql: Sql, userId: String, exceptDeviceId: String? = null): Int = sql.one(
        """SELECT count(*)::int AS n FROM tunnels t JOIN devices d ON d.id=t.device_id
            WHERE d.user_id=?::uuid AND d.removed_at IS NULL AND (?::uuid IS NULL OR d.id <> ?::uuid)""",
        userId, exceptDeviceId, exceptDeviceId,
    )!!.int("n")

    suspend fun me(p: Principal): MeRes = ctx.db.run { sql ->
        MeRes(
            user = p.user,
            entitlement = entitlement(sql, p.user.id),
            usage = Usage(monthStart(ctx.now()).toString(), monthlyUsageBytes(sql, p.user.id), activeTunnelCount(sql, p.user.id)),
        )
    }

    suspend fun payments(p: Principal): List<Payment> = ctx.db.run { sql ->
        sql.query("SELECT * FROM subscriptions WHERE user_id=?::uuid ORDER BY started_at DESC LIMIT 50", p.user.id).map { r ->
            val status = PaymentStatus.valueOf(r.str("status").uppercase())
            Payment(
                id = r.str("id"),
                productId = r.str("product_id"),
                source = sourceOf(r.str("source")),
                startedAt = r.instant("started_at").toString(),
                expiresAt = r.instant("expires_at").toString(),
                status = if (status == PaymentStatus.ACTIVE && r.instant("expires_at") <= ctx.now()) PaymentStatus.EXPIRED else status,
            )
        }
    }
}
