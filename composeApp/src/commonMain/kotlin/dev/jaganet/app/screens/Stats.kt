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
import dev.jaganet.api.Format
import dev.jaganet.api.PlanId
import dev.jaganet.api.StatsPeriod
import dev.jaganet.api.StatsRes
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
private val DEVICE_TONES = listOf(C.green, Color(0xFF6FA592), Color(0xFFB9D3C8), C.faint, C.lineStrong)
private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

@Composable
fun StatsScreen(s: AppState) {
    var period by remember { mutableStateOf(StatsPeriod.WEEK) }
    val stats = load(period, s.dataVersion) { s.api.stats(period) }
    val me = load(s.dataVersion) { s.api.me() }
    Screen {
        Title("Statistics")
        Gap(14.dp)
        Segmented(listOf(StatsPeriod.DAY to "Day", StatsPeriod.WEEK to "Week", StatsPeriod.MONTH to "Month"), period) { period = it }
        Gap(14.dp)
        Loaded(stats) { st -> StatsBody(st) }

        SectionLabel("Your plan")
        Loaded(me) { m ->
            Card(padding = 16.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        T(if (m.entitlement.plan == PlanId.PRO) "Pro" else "Free", TS.Body, FontWeight.SemiBold)
                        T("Active", TS.Caption, FontWeight.SemiBold, C.greenDark, modifier = Modifier.background(C.greenTint, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    KV("Data", m.entitlement.monthlyDataLimitBytes?.let { "${Format.bytes(m.usage.bytesUsed)} / ${Format.bytes(it)}" } ?: "Unlimited")
                    KV("Devices", "${m.usage.devicesUsed} / ${m.entitlement.deviceLimit}", mono = true)
                    if (m.entitlement.expiresAt != null) KV(if (m.entitlement.autoRenew) "Renews" else "Pro until", Format.date(m.entitlement.expiresAt))
                    Button(if (m.entitlement.plan == PlanId.PRO) "Manage subscription" else "Upgrade to Pro", { s.router.go(if (m.entitlement.plan == PlanId.PRO) Route.Account else Route.Plans) }, Modifier.fillMaxWidth(), ButtonKind.Secondary)
                }
            }
        }
    }
}

@Composable
private fun StatsBody(st: StatsRes) {
    val total = st.totalRxBytes + st.totalTxBytes
    Card(padding = 16.dp) {
        T("Traffic · ${when (st.period) { StatsPeriod.DAY -> "Today"; StatsPeriod.WEEK -> "This week"; StatsPeriod.MONTH -> "Last 30 days" }}", TS.Label, color = C.muted)
        T(Format.bytes(total), TS.Display, FontWeight.Medium, mono = true, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 6.dp)) {
            Legend(C.green, "Down", Format.bytes(st.totalRxBytes))
            Legend(C.chartGreen, "Up", Format.bytes(st.totalTxBytes))
        }
        Gap(14.dp)
        BarChart(st)
    }
    Gap(10.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile("Time protected", Format.duration(st.protectedSeconds), Modifier.weight(1f))
        Tile("Sessions", st.sessions.toString(), Modifier.weight(1f))
    }
    Gap(8.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile("Avg. throughput", Format.mbps(st.avgDownBps) + if (st.avgDownBps != null) " Mbps" else "", Modifier.weight(1f))
        Tile("Peak speed", Format.mbps(st.peakDownBps) + if (st.peakDownBps != null) " Mbps" else "", Modifier.weight(1f))
    }

    if (st.byDevice.isNotEmpty()) {
        SectionLabel("By device")
        val sum = st.byDevice.sumOf { it.bytes }.coerceAtLeast(1)
        Card(padding = 16.dp) {
            Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
                st.byDevice.forEachIndexed { i, d ->
                    Box(Modifier.weight(d.bytes.toFloat().coerceAtLeast(1f)).fillMaxHeight().background(DEVICE_TONES[i % DEVICE_TONES.size]))
                }
            }
            Gap(12.dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                st.byDevice.forEachIndexed { i, d ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(DEVICE_TONES[i % DEVICE_TONES.size]))
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
private fun Tile(label: String, value: String, modifier: Modifier) = Card(modifier, padding = 14.dp) {
    T(label, TS.Caption, color = C.muted)
    T(value, TS.Stat, FontWeight.Medium, mono = true, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
}

/** Stacked bars: download (dark) under upload (light). */
@Composable
private fun BarChart(st: StatsRes) {
    val max = st.buckets.maxOfOrNull { it.rxBytes + it.txBytes }?.coerceAtLeast(1) ?: 1
    val gap = if (st.period == StatsPeriod.MONTH) 2.dp else 6.dp
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = "Traffic chart, ${Format.bytes(st.totalRxBytes + st.totalTxBytes)} total" },
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.Bottom,
    ) {
        st.buckets.forEachIndexed { i, b ->
            val h = 140f * (b.rxBytes + b.txBytes) / max
            val upH = h * b.txBytes / (b.rxBytes + b.txBytes).coerceAtLeast(1)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(upH.dp).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(C.chartGreen))
                Box(Modifier.fillMaxWidth().height((h - upH).coerceAtLeast(if (h > 0) 0f else 2f).dp).background(C.green))
                val label = when (st.period) {
                    StatsPeriod.DAY -> b.start.substring(11, 13)
                    StatsPeriod.WEEK -> WEEKDAYS[dayOfWeek(b.start)]
                    StatsPeriod.MONTH -> if (i % 5 == 0) b.start.substring(8, 10).trimStart('0') else ""
                }
                T(label, TS.Caption, color = C.muted, align = TextAlign.Center, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
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
