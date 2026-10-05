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
import dev.jaganet.app.state.AppState
import dev.jaganet.app.state.Route
import dev.jaganet.app.theme.C
import dev.jaganet.app.theme.R
import dev.jaganet.app.theme.Space
import dev.jaganet.app.tunnel.ProtocolInfo
import dev.jaganet.app.tunnel.TunnelStatus
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
                if (pro) Pill("Pro", C.greenTint, C.greenDark) { s.router.go(Route.Account) }
                else Pill("Get Pro", C.ink, Color.White) { s.router.go(Route.Plans) }
            }
        }

        Column(Modifier.fillMaxWidth().padding(top = 44.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            StatusLine(st.status, st.since)
            PowerButton(connected, busy) { if (connected) s.tunnel.disconnect() else if (!busy) s.tunnel.connect() }
            T(
                when (st.status) {
                    TunnelStatus.CONNECTED -> "Tap to disconnect"
                    TunnelStatus.CONNECTING -> "Connecting…"
                    TunnelStatus.DISCONNECTING -> "Disconnecting…"
                    else -> "Tap to connect"
                },
                TS.Small, color = C.muted,
            )
        }

        Blocked(s)

        Gap(28.dp)
        if (connected) Connected(s, st.rxBytes + st.txBytes) else LocationCard(s)

        if (m != null && !connected) {
            Gap(10.dp)
            if (pro) ProCard(m) else FreeCard(s, m)
        }
        if (s.tunnel.engine.simulated) {
            Gap(Space.lg)
            T("Simulated tunnel — no real traffic is routed.", TS.Caption, color = C.muted, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun StatusLine(status: TunnelStatus, since: Long?) {
    val now = nowMs()
    val (dot, text, label) = when (status) {
        TunnelStatus.CONNECTED -> Triple(C.green, C.green, "Protected")
        TunnelStatus.CONNECTING -> Triple(C.faint, C.muted, "Connecting")
        TunnelStatus.ERROR -> Triple(C.warn, C.warnText, "Couldn’t connect")
        else -> Triple(C.warn, C.warnText, "Not protected")
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        T(label, TS.Body, FontWeight.SemiBold, text)
        if (status == TunnelStatus.CONNECTED && since != null) {
            T("·", TS.Body, FontWeight.SemiBold, text)
            T(Format.clock((now - since) / 1000), TS.Body, FontWeight.Medium, text, mono = true)
        }
    }
}

@Composable
private fun PowerButton(on: Boolean, busy: Boolean, onClick: () -> Unit) {
    val size = if (on) 196.dp else 184.dp
    Box(
        Modifier.size(size + 28.dp).clip(CircleShape).background(if (on) C.greenTint else C.lineSoft),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size).clip(CircleShape)
                .background(if (on) C.green else Color.White)
                .border(2.dp, if (on) C.green else C.ink, CircleShape)
                .clickable(enabled = !busy, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = if (on) "Disconnect" else "Connect" },
            contentAlignment = Alignment.Center,
        ) { Icon(Ic.Power, if (on) Color.White else if (busy) C.faint else C.ink, if (on) 64.dp else 60.dp) }
    }
}

@Composable
private fun Blocked(s: AppState) {
    val code = s.tunnel.blockedBy
    val msg = s.tunnel.failure ?: return
    val (text, action) = when (code) {
        ErrorCode.DATA_LIMIT -> "You’ve used this month’s free data." to "Get unlimited"
        ErrorCode.DEVICE_LIMIT -> "Your plan’s device limit is reached." to "See plans"
        else -> msg to null
    }
    Gap(Space.lg)
    Row(
        Modifier.fillMaxWidth().background(Color(0xFFF6E7DD), RoundedCornerShape(R.button)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Ic.Warning, C.warnText, 18.dp, 2f)
        T(text, TS.Label, FontWeight.Medium, C.warnText, modifier = Modifier.weight(1f))
        if (action != null) Pill(action, C.ink, Color.White) { s.router.go(Route.Plans) }
    }
}

@Composable
private fun LocationCard(s: AppState) {
    val servers = load(Unit) { s.api.servers().servers }
    val server = (servers as? Load.Ok)?.value?.let { list -> list.firstOrNull { it.id == s.settings.state.value.serverId } ?: list.firstOrNull() }
    val proto = s.settings.state.value.protocol ?: server?.protocols?.firstOrNull { it in s.tunnel.engine.protocols }
    Card(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(Ic.Globe)
            Column(Modifier.weight(1f)) {
                T("Server location", TS.Caption, color = C.muted)
                T(server?.let { "${it.city}, ${it.countryCode}" } ?: "…", TS.Body, FontWeight.SemiBold)
            }
            Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { s.router.go(Route.Protocol) }.padding(6.dp)) {
                T(proto?.let(ProtocolInfo::label) ?: "", TS.Caption, color = C.muted)
            }
        }
    }
}

@Composable
private fun Connected(s: AppState, sessionBytes: Long) {
    val cfg = s.tunnel.config
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SpeedTile("Download", Ic.ArrowDown, Format.mbps(s.tunnel.downBps), "Mbps", Modifier.weight(1f))
        SpeedTile("Upload", Ic.ArrowUp, Format.mbps(s.tunnel.upBps), "Mbps", Modifier.weight(1f))
        SpeedTile("Protocol", null, cfg?.protocol?.let(ProtocolInfo::label) ?: "–", "", Modifier.weight(1f), mono = false)
    }
    Gap(12.dp)
    Card(padding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            KV("Location", cfg?.location ?: "–")
            KV("Tunnel address", cfg?.address?.substringBefore('/') ?: "–", mono = true)
            KV("DNS", cfg?.dns?.joinToString() ?: "–", mono = true)
            KV("This session", Format.bytes(sessionBytes), mono = true)
        }
    }
}

@Composable
private fun SpeedTile(label: String, icon: Ic?, value: String, unit: String, modifier: Modifier, mono: Boolean = true) {
    Column(modifier.fillMaxHeight().background(Color.White, RoundedCornerShape(R.tile)).border(1.dp, C.line, RoundedCornerShape(R.tile)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (icon != null) Icon(icon, C.muted, 14.dp, 2f)
            T(label, TS.Caption, color = C.muted)
        }
        T(value, if (mono) TS.Stat else TS.Body, FontWeight.Medium, mono = mono, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
        T(unit, TS.Caption, color = C.muted)
    }
}

@Composable
private fun FreeCard(s: AppState, m: MeRes) {
    val limit = m.entitlement.monthlyDataLimitBytes ?: 1
    val left = (limit - m.usage.bytesUsed).coerceAtLeast(0)
    Card(padding = 14.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            T("Free plan", TS.Small, FontWeight.SemiBold)
            Row {
                T(Format.bytes(left), TS.Small, color = C.muted, mono = true)
                T(" left this month", TS.Small, color = C.muted)
            }
        }
        Gap(8.dp)
        Progress(m.usage.bytesUsed.toFloat() / limit, C.warn)
        Gap(12.dp)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(R.button)).background(C.green)
                .clickable(role = Role.Button) { s.router.go(Route.Plans) }.padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            T("Unlimited data, 5 devices", TS.Small, FontWeight.SemiBold, Color.White)
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Upgrade", TS.Small, FontWeight.SemiBold, Color.White)
                Icon(Ic.ChevronRight, Color.White, 16.dp, 2.2f)
            }
        }
    }
}

@Composable
private fun ProCard(m: MeRes) = Card(padding = 14.dp) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        T("Pro · unlimited data", TS.Small, FontWeight.SemiBold)
        T((if (m.entitlement.autoRenew) "Renews " else "Until ") + Format.date(m.entitlement.expiresAt), TS.Small, color = C.muted)
    }
}
