package dev.jaganet.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.jaganet.api.AdminOverviewRes
import dev.jaganet.api.AdminReferralsRes
import dev.jaganet.api.MoneyAmount
import dev.jaganet.api.ReferralPeriod
import dev.jaganet.app.i18n.fBytes
import dev.jaganet.app.i18n.fDate
import dev.jaganet.app.i18n.fDuration
import dev.jaganet.app.i18n.fMoney
import dev.jaganet.app.i18n.t
import dev.jaganet.app.state.AppState
import dev.jaganet.app.theme.C
import dev.jaganet.app.theme.R
import dev.jaganet.app.ui.Card
import dev.jaganet.app.ui.Gap
import dev.jaganet.app.ui.Ic
import dev.jaganet.app.ui.Icon
import dev.jaganet.app.ui.Load
import dev.jaganet.app.ui.Loaded
import dev.jaganet.app.ui.Loading
import dev.jaganet.app.ui.Progress
import dev.jaganet.app.ui.Screen
import dev.jaganet.app.ui.T
import dev.jaganet.app.ui.TS
import dev.jaganet.app.ui.Title
import dev.jaganet.app.ui.load
import kotlin.math.roundToInt

@Composable
fun AdminScreen(s: AppState) {
    val data = load(s.dataVersion) { s.api.adminOverview() }
    // Loaded on its own, so a failure here leaves the rest of the dashboard working.
    val refs = load(s.dataVersion) { s.api.adminReferrals(ReferralPeriod.D30) }
    Screen(onBack = { s.router.back() }) {
        Gap(8.dp)
        Title(t("Business"))
        T(t("Only the owner sees this."), TS.Small, color = C.muted, modifier = Modifier.padding(top = 4.dp))
        Loaded(data) { Body(it) }
        ReferralCard(refs)
    }
}

@Composable
private fun ReferralCard(l: Load<AdminReferralsRes>) {
    H(t("Referral program"))
    when (l) {
        is Load.Loading -> Loading()
        is Load.Failed -> T(l.message, TS.Label, color = C.alertText)
        is Load.Ok -> {
            val f = l.value.totals
            Card(padding = 16.dp) {
                T(t("Last 30 days"), TS.Label, color = C.nightMuted)
                listOf(
                    listOf(t("Clicks") to "${f.clicks}", t("Unique visitors") to "${f.visitors}", t("Sign-ups") to "${f.signups}"),
                    listOf(t("Paid") to "${f.paidUsers}", t("Purchases") to "${f.purchases}", t("Conversion") to pct(f.paidUsers, f.signups)),
                ).forEach { row ->
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (k, v) ->
                            Column(Modifier.weight(1f)) {
                                T(k, TS.Label, color = C.nightMuted, maxLines = 1)
                                T(v, TS.Stat, color = C.nightText, mono = true, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    }
                }
                Gap(16.dp)
                dev.jaganet.app.ui.Divider()
                Gap(12.dp)
                dev.jaganet.app.ui.KV(t("Revenue"), revenue(f.revenue), mono = true)
            }
            val top = l.value.topReferrers.take(5)
            H(t("Top referrers"))
            Card(padding = 16.dp) {
                if (top.isEmpty()) T(t("No referrals yet."), TS.Small, color = C.nightMuted)
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    top.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                T(r.email, TS.Small, color = C.nightText, maxLines = 1)
                                T(
                                    t("Sign-ups {signups}, paid {paid}", "signups" to r.funnel.signups, "paid" to r.funnel.paidUsers),
                                    TS.Caption, color = C.nightMuted, modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            T(revenue(r.funnel.revenue), TS.Small, color = C.nightText, mono = true, align = TextAlign.End, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

private fun revenue(list: List<MoneyAmount>): String = if (list.isEmpty()) "-" else list.joinToString(", ") { fMoney(it.minor, it.currency) }

private fun pct(part: Int, whole: Int): String = if (whole <= 0) "-" else "${(part * 100.0 / whole).roundToInt()}%"

@Composable
private fun H(text: String) = dev.jaganet.app.ui.SectionLabel(text)

@Composable
private fun Body(d: AdminOverviewRes) {
    val r = d.revenue
    H(t("Revenue"))
    Card(padding = 16.dp) {
        T(t("Monthly recurring revenue"), TS.Label, color = C.nightMuted)
        T(fMoney(r.mrrMinor, r.currency), TS.Display, FontWeight.SemiBold, C.nightText, mono = true, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 16.dp)) {
            listOf(t("Paying") to "${r.paying}", t("Free users") to "${r.free}", t("Conversion") to "${(r.conversion * 100).roundToInt()}%").forEach { (k, v) ->
                Column(Modifier.weight(1f)) {
                    T(k, TS.Caption, color = C.nightMuted)
                    T(v, TS.Stat, color = C.nightText, mono = true, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
    Gap(8.dp)
    listOf(
        listOf(t("New subscriptions this week") to "+${r.newSubsThisWeek}", t("Cancelled this month") to "${r.cancelledThisMonth}"),
        listOf(t("Yearly / monthly") to "${r.yearly} / ${r.monthly}", t("From invites") to "${r.fromReferrals}"),
    ).forEach { row ->
        // Tiles in a row share one height.
        Row(Modifier.height(IntrinsicSize.Min).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (k, v) ->
                Card(Modifier.weight(1f).fillMaxHeight(), padding = 16.dp) {
                    T(k, TS.Label, color = C.nightMuted, modifier = Modifier.weight(1f))
                    T(v, TS.Stat, FontWeight.SemiBold, C.nightText, mono = true, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    H(t("New paying users in the last 7 days"))
    Card(padding = 16.dp) {
        val max = r.newPayingLast7Days.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            r.newPayingLast7Days.forEach { day ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    T(if (day.count > 0) "${day.count}" else "", TS.Tab, color = C.nightMuted, mono = true)
                    Box(Modifier.fillMaxWidth().height((80f * day.count / max).coerceAtLeast(2f).dp).background(C.chart1))
                    T(weekday(day.day), TS.Tab, color = C.nightMuted, align = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }

    val sv = d.server
    H(t("Server capacity, {name}", "name" to sv.name))
    Card(padding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Meter(t("Connected now"), "${sv.connectedNow} / ${sv.maxPeers}", sv.connectedNow.toFloat() / sv.maxPeers.coerceAtLeast(1))
            Meter(t("CPU"), "${(sv.cpu * 100).roundToInt()}%", sv.cpu.toFloat())
            Meter(t("Memory"), t("{used} / {total} MB", "used" to sv.memUsedBytes / 1_048_576, "total" to sv.memTotalBytes / 1_048_576), sv.memUsedBytes.toFloat() / sv.memTotalBytes)
            Meter(
                t("Traffic this month"),
                fBytes(sv.trafficThisMonthBytes) + " / " + (sv.trafficLimitBytes?.let { fBytes(it) } ?: "∞"),
                sv.trafficLimitBytes?.let { sv.trafficThisMonthBytes.toFloat() / it } ?: 0f,
            )
            sv.warnings.forEach { w ->
                Row(
                    Modifier.fillMaxWidth().background(C.alertBg, RoundedCornerShape(R.button)).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Ic.Warning, C.alertText, 18.dp, 2f)
                    T(w, TS.Label, color = C.alertText)
                }
            }
            Row {
                Column(Modifier.weight(1f)) {
                    T(t("Uptime"), TS.Caption, color = C.nightMuted)
                    T(fDuration(sv.uptimeSeconds), TS.Small, color = C.nightText, mono = true)
                }
                Column(Modifier.weight(1f)) {
                    T(t("Server paid through"), TS.Caption, color = C.nightMuted)
                    T(fDate(sv.paidThrough), TS.Small, color = C.nightText)
                }
            }
        }
    }
}

@Composable
private fun Meter(label: String, value: String, fraction: Float) = Column {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        T(label, TS.Small, color = C.nightText)
        T(value, TS.Small, color = C.nightText, mono = true)
    }
    Gap(8.dp)
    Progress(fraction, if (fraction > 0.75f) C.warn else C.primary)
}

private fun weekday(isoDate: String): String {
    val (y, m, d) = isoDate.split('-').map { it.toInt() }
    val a = (14 - m) / 12
    val yy = y + 4800 - a
    val mm = m + 12 * a - 3
    val jdn = d + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400 - 32045
    return when (jdn % 7) {
        0 -> t("Mon"); 1 -> t("Tue"); 2 -> t("Wed"); 3 -> t("Thu"); 4 -> t("Fri"); 5 -> t("Sat"); else -> t("Sun")
    }
}
