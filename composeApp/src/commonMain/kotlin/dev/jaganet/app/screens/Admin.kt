package dev.jaganet.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import dev.jaganet.api.Format
import dev.jaganet.app.state.AppState
import dev.jaganet.app.theme.C
import dev.jaganet.app.theme.R
import dev.jaganet.app.ui.Card
import dev.jaganet.app.ui.Gap
import dev.jaganet.app.ui.Ic
import dev.jaganet.app.ui.Icon
import dev.jaganet.app.ui.Loaded
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
    Screen(dark = true, onBack = { s.router.back() }) {
        T("OWNER ONLY", TS.Caption, FontWeight.SemiBold, C.alert, letterSpacing = androidx.compose.ui.unit.TextUnit(0.08f, androidx.compose.ui.unit.TextUnitType.Em))
        Title("Business", C.nightText)
        Loaded(data, dark = true) { Body(it) }
    }
}

@Composable
private fun H(text: String) = T(text, TS.Label, FontWeight.SemiBold, C.nightMuted, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))

@Composable
private fun Body(d: AdminOverviewRes) {
    val r = d.revenue
    H("Revenue")
    Card(dark = true, padding = 16.dp) {
        T("Monthly recurring revenue", TS.Label, color = C.nightMuted)
        T(Format.money(r.mrrMinor, r.currency), TS.Display, FontWeight.Medium, C.nightText, mono = true, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 14.dp)) {
            listOf("Paying" to "${r.paying}", "Free" to "${r.free}", "Conversion" to "${(r.conversion * 100).roundToInt()}%").forEach { (k, v) ->
                Column(Modifier.weight(1f)) {
                    T(k, TS.Caption, color = C.nightMuted)
                    T(v, TS.Stat, color = C.nightText, mono = true, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
    Gap(10.dp)
    listOf(
        listOf("New subs this week" to "+${r.newSubsThisWeek}", "Cancelled this month" to "${r.cancelledThisMonth}"),
        listOf("Yearly / monthly" to "${r.yearly} / ${r.monthly}", "From referrals" to "${r.fromReferrals}"),
    ).forEach { row ->
        Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (k, v) ->
                Card(Modifier.weight(1f), dark = true, padding = 14.dp) {
                    T(k, TS.Caption, color = C.nightMuted)
                    T(v, TS.Stat, color = C.nightText, mono = true, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    H("New paying users · last 7 days")
    Card(dark = true, padding = 16.dp) {
        val max = r.newPayingLast7Days.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            r.newPayingLast7Days.forEach { day ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    T(if (day.count > 0) "${day.count}" else "", TS.Tab, color = C.nightMuted, mono = true)
                    Box(Modifier.fillMaxWidth().height((80f * day.count / max).coerceAtLeast(2f).dp).background(C.chartGreen, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
                    T(weekday(day.day), TS.Tab, color = C.nightMuted, align = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }

    val sv = d.server
    H("Server capacity · ${sv.name}")
    Card(dark = true, padding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Meter("Connected now", "${sv.connectedNow} / ${sv.maxPeers}", sv.connectedNow.toFloat() / sv.maxPeers.coerceAtLeast(1))
            Meter("CPU", "${(sv.cpu * 100).roundToInt()}%", sv.cpu.toFloat())
            Meter("Memory", "${sv.memUsedBytes / 1_048_576} / ${sv.memTotalBytes / 1_048_576} MB", sv.memUsedBytes.toFloat() / sv.memTotalBytes)
            Meter(
                "Traffic this month",
                Format.bytes(sv.trafficThisMonthBytes) + " / " + (sv.trafficLimitBytes?.let(Format::bytes) ?: "∞"),
                sv.trafficLimitBytes?.let { sv.trafficThisMonthBytes.toFloat() / it } ?: 0f,
            )
            sv.warnings.forEach { w ->
                Row(
                    Modifier.fillMaxWidth().background(C.alertBg, RoundedCornerShape(R.button)).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Ic.Warning, C.alertText, 18.dp, 2f)
                    T(w, TS.Label, color = C.alertText)
                }
            }
            Row {
                Column(Modifier.weight(1f)) {
                    T("Uptime", TS.Caption, color = C.nightMuted)
                    T(Format.duration(sv.uptimeSeconds), TS.Small, color = C.nightText, mono = true)
                }
                Column(Modifier.weight(1f)) {
                    T("Server paid through", TS.Caption, color = C.nightMuted)
                    T(Format.date(sv.paidThrough), TS.Small, color = C.nightText)
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
    Gap(6.dp)
    Progress(fraction, if (fraction > 0.75f) C.alert else C.chartGreen, C.nightTrack)
}

private fun weekday(isoDate: String): String {
    val (y, m, d) = isoDate.split('-').map { it.toInt() }
    val a = (14 - m) / 12
    val yy = y + 4800 - a
    val mm = m + 12 * a - 3
    val jdn = d + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400 - 32045
    return listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[jdn % 7]
}
