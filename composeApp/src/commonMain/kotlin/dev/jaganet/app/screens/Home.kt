package dev.jaganet.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jaganet.api.ErrorCode
import dev.jaganet.api.Format
import dev.jaganet.api.MeRes
import dev.jaganet.api.PlanId
import dev.jaganet.app.i18n.fBytes
import dev.jaganet.app.i18n.fDate
import dev.jaganet.app.i18n.fMbps
import dev.jaganet.app.i18n.t
import dev.jaganet.app.state.AppState
import dev.jaganet.app.state.Route
import dev.jaganet.app.theme.C
import dev.jaganet.app.theme.R
import dev.jaganet.app.theme.Space
import dev.jaganet.app.tunnel.ProtocolInfo
import dev.jaganet.app.tunnel.TunnelStatus
import dev.jaganet.app.ui.Button
import dev.jaganet.app.ui.ButtonKind
import dev.jaganet.app.ui.Card
import dev.jaganet.app.ui.Gap
import dev.jaganet.app.ui.Ic
import dev.jaganet.app.ui.Icon
import dev.jaganet.app.ui.IconTile
import dev.jaganet.app.ui.KV
import dev.jaganet.app.ui.Load
import dev.jaganet.app.ui.Pill
import dev.jaganet.app.ui.Progress
import dev.jaganet.app.ui.Screen
import dev.jaganet.app.ui.T
import dev.jaganet.app.ui.TS
import dev.jaganet.app.ui.load
import kotlinx.coroutines.delay
import kotlin.time.Clock

@Composable
fun nowMs(): Long {
    val t by produceState(Clock.System.now().toEpochMilliseconds()) {
        while (true) { delay(1000); value = Clock.System.now().toEpochMilliseconds() }
    }
    return t
}

@Composable
fun HomeScreen(s: AppState) {
    val st by s.tunnel.engine.status.collectAsState()
    val me = load(s.dataVersion) { s.api.me() }
    val m = (me as? Load.Ok)?.value
    val pro = m?.entitlement?.plan == PlanId.PRO
    val connected = st.status == TunnelStatus.CONNECTED
    val busy = st.status == TunnelStatus.CONNECTING || st.status == TunnelStatus.DISCONNECTING

    Screen {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Brand()
            if (m != null) {
                if (pro) Pill(t("Pro"), C.greenTint, C.greenDark) { s.router.go(Route.Account) }
                else Pill(t("Get Pro"), C.primary, Color.White) { s.router.go(Route.Plans) }
            }
        }

        Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StatusLine(st.status, st.since)
            PowerButton(connected, busy) { if (connected) s.tunnel.disconnect() else if (!busy) s.tunnel.connect() }
            T(
                when (st.status) {
                    TunnelStatus.CONNECTED -> t("Tap to disconnect")
                    TunnelStatus.CONNECTING -> t("Connecting…")
                    TunnelStatus.DISCONNECTING -> t("Disconnecting…")
                    else -> t("Tap to connect")
                },
                TS.Small, color = C.muted,
            )
        }

        Blocked(s)

        Gap(24.dp)
        if (connected) Connected(s, st.rxBytes + st.txBytes) else LocationCard(s)

        if (m != null && !connected) {
            Gap(Space.sm)
            if (pro) ProCard(m) else FreeCard(s, m)
        }
        if (s.tunnel.engine.simulated) {
            Gap(Space.lg)
            T(t("Simulated tunnel. No real traffic is routed."), TS.Caption, color = C.muted, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun StatusLine(status: TunnelStatus, since: Long?) {
    val now = nowMs()
    val (dot, text, label) = when (status) {
        TunnelStatus.CONNECTED -> Triple(C.green, C.green, t("Protected"))
        TunnelStatus.CONNECTING -> Triple(C.faint, C.muted, t("Connecting"))
        TunnelStatus.ERROR -> Triple(C.warn, C.warnText, t("Couldn’t connect"))
        else -> Triple(C.warn, C.warnText, t("Not protected"))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        T(label, TS.Body, FontWeight.SemiBold, text)
        if (status == TunnelStatus.CONNECTED && since != null) {
            T(Format.clock((now - since) / 1000), TS.Body, FontWeight.Medium, text, mono = true)
        }
    }
}

@Composable
private fun PowerButton(on: Boolean, busy: Boolean, onClick: () -> Unit) {
    // One size in every state; only the fill changes.
    Box(
        Modifier.size(184.dp).clip(CircleShape)
            .background(if (on) C.green else C.surface)
            .border(1.dp, if (on) C.green else C.lineStrong, CircleShape)
            .clickable(enabled = !busy, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (on) t("Disconnect") else t("Connect") },
        contentAlignment = Alignment.Center,
    ) { Icon(Ic.Power, if (on) Color.White else if (busy) C.faint else C.ink, 56.dp) }
}

@Composable
private fun Blocked(s: AppState) {
    val code = s.tunnel.blockedBy
    val msg = s.tunnel.failure ?: return
    val (text, action) = when (code) {
        ErrorCode.DATA_LIMIT -> t("You’ve used this month’s free data.") to t("Get unlimited")
        ErrorCode.DEVICE_LIMIT -> t("Your plan’s device limit is reached.") to t("See plans")
        else -> msg to null
    }
    Gap(Space.lg)
    Row(
        Modifier.fillMaxWidth().background(C.warnTint, RoundedCornerShape(R.button)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Ic.Warning, C.warnText, 18.dp, 2f)
        T(text, TS.Label, FontWeight.Medium, C.warnText, modifier = Modifier.weight(1f))
        if (action != null) Pill(action, C.primary, Color.White) { s.router.go(Route.Plans) }
    }
}

@Composable
private fun LocationCard(s: AppState) {
    val servers = load(Unit) { s.api.servers().servers }
    val server = (servers as? Load.Ok)?.value?.let { list -> list.firstOrNull { it.id == s.settings.state.value.serverId } ?: list.firstOrNull() }
    val proto = s.settings.state.value.protocol ?: server?.protocols?.firstOrNull { it in s.tunnel.engine.protocols }
    Card(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(Ic.Globe)
            Column(Modifier.weight(1f)) {
                T(t("Server location"), TS.Caption, color = C.muted)
                T(server?.let { "${it.city}, ${it.countryCode}" } ?: "…", TS.Body, FontWeight.SemiBold)
            }
            Box(Modifier.clip(RoundedCornerShape(R.button)).clickable(role = Role.Button) { s.router.go(Route.Protocol) }.padding(8.dp)) {
                T(proto?.let(ProtocolInfo::label) ?: "", TS.Small, color = C.primary)
            }
        }
    }
}

@Composable
private fun Connected(s: AppState, sessionBytes: Long) {
    val cfg = s.tunnel.config
    // Two equal tiles; everything else is a line in the card below.
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        SpeedTile(t("Download"), Ic.ArrowDown, fMbps(s.tunnel.downBps), t("Mbps"), Modifier.weight(1f))
        SpeedTile(t("Upload"), Ic.ArrowUp, fMbps(s.tunnel.upBps), t("Mbps"), Modifier.weight(1f))
    }
    Gap(Space.sm)
    Card(padding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            KV(t("Location"), cfg?.location ?: "-")
            KV(t("Protocol"), cfg?.protocol?.let(ProtocolInfo::label) ?: "-")
            KV(t("Tunnel address"), cfg?.address?.substringBefore('/') ?: "-", mono = true)
            KV(t("DNS"), cfg?.dns?.joinToString() ?: "-", mono = true)
            KV(t("This session"), fBytes(sessionBytes), mono = true)
        }
    }
}

@Composable
private fun SpeedTile(label: String, icon: Ic, value: String, unit: String, modifier: Modifier) {
    Column(modifier.fillMaxHeight().background(C.surface, RoundedCornerShape(R.tile)).border(1.dp, C.line, RoundedCornerShape(R.tile)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, C.muted, 14.dp, 2f)
            T(label, TS.Label, color = C.muted)
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            T(value, TS.Stat, FontWeight.SemiBold, mono = true, maxLines = 1)
            T(unit, TS.Label, color = C.muted, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

@Composable
private fun FreeCard(s: AppState, m: MeRes) {
    val limit = m.entitlement.monthlyDataLimitBytes ?: 1
    val left = (limit - m.usage.bytesUsed).coerceAtLeast(0)
    Card(padding = 16.dp) {
        T(t("Free plan"), TS.Small, FontWeight.SemiBold)
        T(t("{amount} left this month", "amount" to fBytes(left)), TS.Label, color = C.muted, modifier = Modifier.padding(top = 4.dp))
        Gap(8.dp)
        val used = m.usage.bytesUsed.toFloat() / limit
        Progress(used, if (used > 0.8f) C.warn else C.primary)
        Gap(16.dp)
        Button(t("See plans"), { s.router.go(Route.Plans) }, Modifier.fillMaxWidth(), ButtonKind.Primary)
    }
}

@Composable
private fun ProCard(m: MeRes) = Card(padding = 16.dp) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        T(m.entitlement.tariffName ?: t("Pro"), TS.Small, FontWeight.SemiBold)
        T(if (m.entitlement.autoRenew) t("Renews {date}", "date" to fDate(m.entitlement.expiresAt)) else t("Until {date}", "date" to fDate(m.entitlement.expiresAt)), TS.Small, color = C.muted)
    }
}
