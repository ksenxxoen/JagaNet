package dev.jaganet.server.services

import dev.jaganet.api.AdminTariff
import dev.jaganet.api.DurationUnit
import dev.jaganet.api.Tariff
import dev.jaganet.api.TariffReq
import dev.jaganet.api.TariffStatus
import dev.jaganet.api.i18n.Lang
import dev.jaganet.server.Ctx
import dev.jaganet.server.Price
import dev.jaganet.server.badRequest
import dev.jaganet.server.db.Row
import dev.jaganet.server.db.Sql
import dev.jaganet.server.notFound
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The terms of a purchase, frozen when the order is placed. The subscription copies them,
 * so editing or archiving the tariff later never changes what someone already bought.
 */
@Serializable
data class Terms(
    val tariffId: String?,
    val name: String,
    val durationValue: Int,
    val durationUnit: DurationUnit,
    val deviceLimit: Int,
    /** Per calendar month; null = unlimited. */
    val monthlyBytes: Long? = null,
) {
    fun end(start: Instant): Instant = when (durationUnit) {
        DurationUnit.DAYS -> start.plusSeconds(durationValue * 86_400L)
        DurationUnit.MONTHS -> start.atZone(ZoneOffset.UTC).plusMonths(durationValue.toLong()).toInstant()
    }
}

/** The tariff builder: the owner creates any number of tariffs; buyers see the active ones. */
class Tariffs(private val ctx: Ctx) {
    /**
     * First start: the two tariffs the server used to sell, with the prices the owner saved in
     * the admin panel before the tariff builder, else the environment's.
     */
    suspend fun seedIfEmpty() = ctx.db.tx { sql ->
        if (sql.one("SELECT count(*)::int AS n FROM tariffs")!!.int("n") > 0) return@tx
        val p = ctx.cfg.plans
        val saved = sql.one("SELECT value FROM settings WHERE key='plans'")?.json("value")?.get("prices") as? JsonObject
        fun price(cur: String) = (saved?.get(cur) as? JsonObject)?.let { o ->
            runCatching { Price(o["monthlyMinor"]!!.jsonPrimitive.long, o["yearlyMinor"]!!.jsonPrimitive.long) }.getOrNull()
        } ?: p.prices[cur]
        val rub = price("RUB") ?: return@tx
        val eur = price("EUR") ?: return@tx
        for ((i, t) in listOf(Triple("Pro на год", 12, rub.yearlyMinor to eur.yearlyMinor), Triple("Pro на месяц", 1, rub.monthlyMinor to eur.monthlyMinor)).withIndex()) {
            sql.exec(
                """INSERT INTO tariffs (name, duration_value, duration_unit, price_rub_minor, price_eur_minor, device_limit, sort)
                   VALUES (?,?,'months',?,?,?,?)""",
                t.first, t.second, t.third.first, t.third.second, p.proDeviceLimit, i + 1,
            )
        }
    }

    /** On sale, in the reader's currency. */
    suspend fun onSale(lang: Lang): List<Tariff> = ctx.db.run { sql ->
        val cur = ctx.live.plans.currencyFor(lang)
        sql.query("SELECT * FROM tariffs WHERE status='active' ORDER BY sort, created_at").map { it.toTariff(cur) }
    }

    /** An active tariff, for buying. */
    fun forSale(sql: Sql, id: String): Row? =
        if (!isUuid(id)) null else sql.one("SELECT * FROM tariffs WHERE id=?::uuid AND status='active'", id)

    suspend fun all(): List<AdminTariff> = ctx.db.run { sql ->
        val now = ctx.now()
        sql.query(
            """SELECT t.*,
                 (SELECT count(*)::int FROM subscriptions s WHERE s.tariff_id=t.id) AS sold,
                 (SELECT count(DISTINCT s.user_id)::int FROM subscriptions s
                   WHERE s.tariff_id=t.id AND s.status='active' AND s.started_at <= ? AND s.expires_at > ?) AS active_now
               FROM tariffs t ORDER BY (t.status='archived'), t.sort, t.created_at""",
            now, now,
        ).map { r ->
            AdminTariff(
                id = r.str("id"), name = r.str("name"), durationValue = r.int("duration_value"), durationUnit = unit(r.str("duration_unit")),
                priceRubMinor = r.long("price_rub_minor"), priceEurMinor = r.long("price_eur_minor"), deviceLimit = r.int("device_limit"),
                trafficGb = r.longOrNull("traffic_gb"), badge = r.strOrNull("badge"), sort = r.int("sort"),
                status = TariffStatus.valueOf(r.str("status").uppercase()), sold = r.int("sold"), activeNow = r.int("active_now"),
                createdAt = r.instant("created_at").toString(),
            )
        }
    }

    suspend fun create(req: TariffReq) {
        val t = check(req)
        ctx.db.run { sql ->
            sql.exec(
                """INSERT INTO tariffs (name, duration_value, duration_unit, price_rub_minor, price_eur_minor, device_limit, traffic_gb, badge, sort, status)
                   VALUES (?,?,?,?,?,?,?,?,?,?)""",
                t.name, t.durationValue, t.durationUnit.id, t.priceRubMinor, t.priceEurMinor, t.deviceLimit, t.trafficGb, t.badge, t.sort, t.status.id,
            )
        }
    }

    /** Applies to new purchases only. */
    suspend fun update(id: String, req: TariffReq) {
        val t = check(req)
        if (!isUuid(id)) throw notFound("Tariff not found")
        val n = ctx.db.run { sql ->
            sql.exec(
                """UPDATE tariffs SET name=?, duration_value=?, duration_unit=?, price_rub_minor=?, price_eur_minor=?, device_limit=?,
                          traffic_gb=?, badge=?, sort=?, status=?, updated_at=? WHERE id=?::uuid""",
                t.name, t.durationValue, t.durationUnit.id, t.priceRubMinor, t.priceEurMinor, t.deviceLimit, t.trafficGb, t.badge, t.sort, t.status.id,
                ctx.now(), id,
            )
        }
        if (n == 0) throw notFound("Tariff not found")
    }

    private fun check(r: TariffReq): TariffReq {
        val name = r.name.trim()
        if (name.isEmpty() || name.length > 60) throw badRequest("Name must be 1 to 60 characters")
        val maxDuration = if (r.durationUnit == DurationUnit.DAYS) 3650 else 120
        if (r.durationValue !in 1..maxDuration || r.deviceLimit !in 1..100 || (r.trafficGb != null && r.trafficGb !in 1L..100_000L) || r.sort !in -1000..1000) {
            throw badRequest("Some values are out of range")
        }
        if (r.priceRubMinor !in 1L..100_000_000L || r.priceEurMinor !in 1L..10_000_000L) throw badRequest("Prices must be above zero")
        return r.copy(name = name, badge = r.badge?.trim()?.takeIf { it.isNotEmpty() }?.take(30))
    }

    companion object {
        fun terms(r: Row) = Terms(
            r.str("id"), r.str("name"), r.int("duration_value"), unit(r.str("duration_unit")), r.int("device_limit"),
            r.longOrNull("traffic_gb")?.times(1_000_000_000),
        )

        fun price(r: Row, currency: String): Long = if (currency == "RUB") r.long("price_rub_minor") else r.long("price_eur_minor")

        private fun unit(s: String) = if (s == "days") DurationUnit.DAYS else DurationUnit.MONTHS
        private fun isUuid(s: String) = runCatching { UUID.fromString(s) }.isSuccess
    }

    private fun Row.toTariff(cur: String) = Tariff(
        id = str("id"), name = str("name"), durationValue = int("duration_value"), durationUnit = unit(str("duration_unit")),
        priceMinor = price(this, cur), currency = cur, deviceLimit = int("device_limit"),
        monthlyDataLimitBytes = longOrNull("traffic_gb")?.times(1_000_000_000), badge = strOrNull("badge"),
    )
}

val DurationUnit.id get() = if (this == DurationUnit.DAYS) "days" else "months"
val TariffStatus.id get() = name.lowercase()
