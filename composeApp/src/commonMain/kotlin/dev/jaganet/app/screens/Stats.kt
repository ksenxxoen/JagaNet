package dev.jaganet.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.jaganet.api.PlanId
import dev.jaganet.api.StatsPeriod
import dev.jaganet.api.StatsRes
import dev.jaganet.app.i18n.fBytes
import dev.jaganet.app.i18n.fDate
import dev.jaganet.app.i18n.fDuration
import dev.jaganet.app.i18n.fMbps
import dev.jaganet.app.i18n.t
import dev.jaganet.app.state.AppState
import dev.jaganet.app.state.Route
import dev.jaganet.app.theme.C
import dev.jaganet.app.ui.Button
import dev.jaganet.app.ui.ButtonKind
import dev.jaganet.app.ui.Card
import dev.jaganet.app.ui.Gap
import dev.jaganet.app.ui.KV
import dev.jaganet.app.ui.Load
import dev.jaganet.app.ui.Loaded
import dev.jaganet.app.ui.Screen
import dev.jaganet.app.ui.SectionLabel
import dev.jaganet.app.ui.Segmented
import dev.jaganet.app.ui.T
import dev.jaganet.app.ui.TS
import dev.jaganet.app.ui.Title
import dev.jaganet.app.ui.load

/** Device colours for the "By device" bar; differ in lightness, not hue alone. */
private val DEVICE_TONES = listOf(C.chart1, C.chart2, Color(0xFFCBE2F9), C.faint, C.lineStrong)

/** Short weekday name, 0 = Monday. */
private fun weekdayName(i: Int): String = when (i) {
    0 -> t("Mon"); 1 -> t("Tue"); 2 -> t("Wed"); 3 -> t("Thu"); 4 -> t("Fri"); 5 -> t("Sat"); else -> t("Sun")
}

@Composable
fun StatsScreen(s: AppState) {
    var period by remember { mutableStateOf(StatsPeriod.WEEK) }
    val stats = load(period, s.dataVersion) { s.api.stats(period) }
    val me = load(s.dataVersion) { s.api.me() }
    Screen {
        Title(t("Statistics"))
        Gap(16.dp)
        Segmented(listOf(StatsPeriod.DAY to t("Day"), StatsPeriod.WEEK to t("Week"), StatsPeriod.MONTH to t("Month")), period) { period = it }
        Gap(16.dp)
        Loaded(stats) { st -> StatsBody(st) }

        SectionLabel(t("Your plan"))
        Loaded(me) { m ->
            Card(padding = 16.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        T(if (m.entitlement.plan == PlanId.PRO) m.entitlement.tariffName ?: t("Pro") else t("Free"), TS.Body, FontWeight.SemiBold)
                        dev.jaganet.app.ui.Badge(t("Active"), C.greenTint, C.greenDark)
                    }
                    KV(t("Data"), m.entitlement.monthlyDataLimitBytes?.let { "${fBytes(m.usage.bytesUsed)} / ${fBytes(it)}" } ?: t("Unlimited"))
                    KV(t("Devices"), "${m.usage.devicesUsed} / ${m.entitlement.deviceLimit}", mono = true)
                    if (m.entitlement.expiresAt != null) KV(if (m.entitlement.autoRenew) t("Renews") else t("Pro until"), fDate(m.entitlement.expiresAt))
                    Button(if (m.entitlement.plan == PlanId.PRO) t("Manage subscription") else t("Upgrade to Pro"), { s.router.go(if (m.entitlement.plan == PlanId.PRO) Route.Account else Route.Plans) }, Modifier.fillMaxWidth(), ButtonKind.Secondary)
                }
            }
        }
    }
}

@Composable
private fun StatsBody(st: StatsRes) {
    val total = st.totalRxBytes + st.totalTxBytes
    Card(padding = 16.dp) {
        T(when (st.period) { StatsPeriod.DAY -> t("Traffic today"); StatsPeriod.WEEK -> t("Traffic this week"); StatsPeriod.MONTH -> t("Traffic in the last 30 days") }, TS.Label, color = C.muted)
        T(fBytes(total), TS.Display, FontWeight.SemiBold, mono = true, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
            Legend(C.chart1, t("Downloaded"), fBytes(st.totalRxBytes))
            Legend(C.chart2, t("Uploaded"), fBytes(st.totalTxBytes))
        }
        Gap(16.dp)
        BarChart(st)
    }
    Gap(8.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(t("Time protected"), fDuration(st.protectedSeconds), Modifier.weight(1f))
        Tile(t("Sessions"), st.sessions.toString(), Modifier.weight(1f))
    }
    Gap(8.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(t("Avg. speed"), st.avgDownBps?.let { t("{v} Mbps", "v" to fMbps(it)) } ?: "-", Modifier.weight(1f))
        Tile(t("Peak speed"), st.peakDownBps?.let { t("{v} Mbps", "v" to fMbps(it)) } ?: "-", Modifier.weight(1f))
    }

    if (st.byDevice.isNotEmpty()) {
        SectionLabel(t("By device"))
        val sum = st.byDevice.sumOf { it.bytes }.coerceAtLeast(1)
        Card(padding = 16.dp) {
            Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(2.dp))) {
                st.byDevice.forEachIndexed { i, d ->
                    Box(Modifier.weight(d.bytes.toFloat().coerceAtLeast(1f)).fillMaxHeight().background(DEVICE_TONES[i % DEVICE_TONES.size]))
                }
            }
            Gap(12.dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                st.byDevice.forEachIndexed { i, d ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(DEVICE_TONES[i % DEVICE_TONES.size]))
                            T(d.name, TS.Small)
                        }
                        T("${d.bytes * 100 / sum}%", TS.Small, mono = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String, value: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
    T("$label ", TS.Label, color = C.muted)
    T(value, TS.Label, FontWeight.SemiBold)
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier) = Card(modifier, padding = 16.dp) {
    T(label, TS.Label, color = C.muted, maxLines = 1)
    T(value, TS.Stat, FontWeight.SemiBold, mono = true, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
}

/** Stacked bars: download (dark) under upload (light). */
@Composable
private fun BarChart(st: StatsRes) {
    val max = st.buckets.maxOfOrNull { it.rxBytes + it.txBytes }?.coerceAtLeast(1) ?: 1
    val gap = if (st.period == StatsPeriod.MONTH) 2.dp else 6.dp
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = t("Traffic chart, {total} in total", "total" to fBytes(st.totalRxBytes + st.totalTxBytes)) },
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.Bottom,
    ) {
        st.buckets.forEachIndexed { i, b ->
            val h = 140f * (b.rxBytes + b.txBytes) / max
            val upH = h * b.txBytes / (b.rxBytes + b.txBytes).coerceAtLeast(1)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(upH.dp).background(C.chart2))
                Box(Modifier.fillMaxWidth().height((h - upH).coerceAtLeast(if (h > 0) 0f else 2f).dp).background(C.chart1))
                val label = when (st.period) {
                    StatsPeriod.DAY -> b.start.substring(11, 13)
                    StatsPeriod.WEEK -> weekdayName(dayOfWeek(b.start))
                    StatsPeriod.MONTH -> if (i % 5 == 0) b.start.substring(8, 10).trimStart('0') else ""
                }
                T(label, TS.Caption, color = C.muted, align = TextAlign.Center, modifier = Modifier.padding(top = 8.dp), maxLines = 1)
            }
        }
    }
}

/** 0 = Monday, from an ISO date (Zeller-free: days since 1970-01-01 was a Thursday). */
private fun dayOfWeek(iso: String): Int {
    val (y, m, d) = iso.take(10).split('-').map { it.toInt() }
    val a = (14 - m) / 12
    val yy = y + 4800 - a
    val mm = m + 12 * a - 3
    val jdn = d + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400 - 32045
    return jdn % 7
}
