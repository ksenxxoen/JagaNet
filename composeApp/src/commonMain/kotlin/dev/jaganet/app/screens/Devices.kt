package dev.jaganet.app.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jaganet.api.Device
import dev.jaganet.api.Format
import dev.jaganet.api.PairingCodeRes
import dev.jaganet.api.Platform
import dev.jaganet.app.APP_NAME
import dev.jaganet.app.state.AppState
import dev.jaganet.app.state.Route
import dev.jaganet.app.theme.C
import dev.jaganet.app.theme.Space
import dev.jaganet.app.tunnel.ProtocolInfo
import dev.jaganet.app.ui.Button
import dev.jaganet.app.ui.ButtonKind
import dev.jaganet.app.ui.Card
import dev.jaganet.app.ui.Divider
import dev.jaganet.app.ui.ErrorNote
import dev.jaganet.app.ui.Gap
import dev.jaganet.app.ui.Ic
import dev.jaganet.app.ui.Icon
import dev.jaganet.app.ui.IconTile
import dev.jaganet.app.ui.Loaded
import dev.jaganet.app.ui.Screen
import dev.jaganet.app.ui.T
import dev.jaganet.app.ui.TS
import dev.jaganet.app.ui.Title
import dev.jaganet.app.ui.load
import kotlinx.coroutines.launch

@Composable
fun DevicesScreen(s: AppState) {
    val devices = load(s.dataVersion) { s.api.devices() }
    var pairing by remember { mutableStateOf<PairingCodeRes?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    val a = rememberAction()
    val scope = rememberCoroutineScope()

    Screen {
        Title("Devices")
        Loaded(devices) { d ->
            val withTunnel = d.devices.count { it.tunnelAddress != null }
            T("$withTunnel of ${d.limit} device${if (d.limit == 1) "" else "s"} on your plan", TS.Small, color = C.muted, modifier = Modifier.padding(top = 6.dp))
            Gap(18.dp)
            Card {
                d.devices.forEachIndexed { i, dev ->
                    DeviceRow(dev, nowMs(), expanded = open == dev.id) { open = if (open == dev.id) null else dev.id }
                    if (open == dev.id && !dev.isCurrent) {
                        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button("Remove", {
                                scope.launch {
                                    runCatching { s.api.removeDevice(dev.id) }.onFailure { a.error = it.message }
                                    open = null
                                    s.invalidate()
                                }
                            }, Modifier.weight(1f), ButtonKind.Secondary)
                        }
                        T("Removing signs the device out and frees a slot.", TS.Caption, color = C.muted, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp))
                    }
                    if (i < d.devices.lastIndex) Divider()
                }
            }
        }
        ErrorNote(a.error)
        Gap(14.dp)
        Button("Add device", {
            scope.launch {
                a.busy = true
                runCatching { pairing = s.api.pairingCode() }.onFailure { a.error = it.message }
                a.busy = false
            }
        }, Modifier.fillMaxWidth(), icon = Ic.Plus, busy = a.busy)

        pairing?.let { p ->
            Gap(18.dp)
            Card(padding = 18.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.size(132.dp).border(1.5.dp, C.faint, RoundedCornerShape(10.dp)).padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                    ) {
                        T("Sign-in code", TS.Caption, color = C.muted)
                        T(p.code.chunked(3).joinToString(" "), TS.Stat, FontWeight.Medium, mono = true)
                    }
                    Column(Modifier.weight(1f)) {
                        T("New device", TS.Body, FontWeight.SemiBold)
                        T("Install $APP_NAME on the other device, choose “Sign in with a device code” and enter this code. It expires in 10 minutes.", TS.Label, color = C.muted, modifier = Modifier.padding(top = 4.dp))
                        T("Need more devices?", TS.Label, FontWeight.SemiBold, C.green, modifier = Modifier.padding(top = Space.sm).clickable(role = Role.Button) { s.router.go(Route.Plans) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(d: Device, now: Long, expanded: Boolean, onMore: () -> Unit) {
    val icon = when (d.platform) { Platform.IOS, Platform.ANDROID -> if (d.name.contains("tablet", true)) Ic.Tablet else Ic.Phone; Platform.DESKTOP, Platform.OTHER -> Ic.Laptop }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(icon, if (d.online || d.isCurrent) C.greenTint else C.lineSoft, if (d.online || d.isCurrent) C.green else C.muted)
        Column(Modifier.weight(1f)) {
            T(d.name, TS.Body, FontWeight.SemiBold, maxLines = 1)
            // As in the draft: address · last contact. The protocol shows when the row is opened.
            val seen = if (d.tunnelAddress == null) "no tunnel yet" else "seen ${Format.ago(d.lastSeenAt, now)}"
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                d.tunnelAddress?.let { T(it, TS.Caption, color = C.muted, mono = true, maxLines = 1) }
                T(seen, TS.Caption, color = C.muted, maxLines = 1)
            }
            if (expanded && d.protocol != null) T("Protocol: ${ProtocolInfo.label(d.protocol!!)}", TS.Caption, color = C.muted, modifier = Modifier.padding(top = 2.dp))
        }
        when {
            d.isCurrent -> T("This device", TS.Caption, FontWeight.SemiBold, C.green, modifier = Modifier.padding(end = 8.dp))
            else -> T(if (d.online) "Online" else "Offline", TS.Caption, FontWeight.SemiBold, if (d.online) C.green else C.muted)
        }
        if (!d.isCurrent) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onMore).semantics { contentDescription = "More options for ${d.name}" },
                contentAlignment = Alignment.Center,
            ) { Icon(if (expanded) Ic.Close else Ic.More, C.muted, 20.dp, 2.2f) }
        }
    }
}
