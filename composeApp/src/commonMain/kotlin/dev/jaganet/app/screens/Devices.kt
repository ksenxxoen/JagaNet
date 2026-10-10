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
import dev.jaganet.api.PairingCodeRes
import dev.jaganet.api.Platform
import dev.jaganet.app.APP_NAME
import dev.jaganet.app.i18n.fAgo
import dev.jaganet.app.i18n.t
import dev.jaganet.app.i18n.tp
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
        Title(t("Devices"))
        Loaded(devices) { d ->
            val withTunnel = d.devices.count { it.tunnelAddress != null }
            T(tp(d.limit, "{used} of {n} device on your plan|{used} of {n} devices on your plan", "used" to withTunnel), TS.Small, color = C.muted, modifier = Modifier.padding(top = 8.dp))
            Gap(16.dp)
            Card {
                d.devices.forEachIndexed { i, dev ->
                    DeviceRow(dev, nowMs(), expanded = open == dev.id) { open = if (open == dev.id) null else dev.id }
                    if (open == dev.id && !dev.isCurrent) {
                        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(t("Remove"), {
                                scope.launch {
                                    runCatching { s.api.removeDevice(dev.id) }.onFailure { a.error = it.message }
                                    open = null
                                    s.invalidate()
                                }
                            }, Modifier.weight(1f), ButtonKind.Secondary)
                        }
                        T(t("Removing signs the device out and frees a slot."), TS.Caption, color = C.muted, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp))
                    }
                    if (i < d.devices.lastIndex) Divider()
                }
            }
        }
        ErrorNote(a.error)
        Gap(16.dp)
        Button(t("Add device"), {
            scope.launch {
                a.busy = true
                runCatching { pairing = s.api.pairingCode() }.onFailure { a.error = it.message }
                a.busy = false
            }
        }, Modifier.fillMaxWidth(), icon = Ic.Plus, busy = a.busy)

        pairing?.let { p ->
            Gap(16.dp)
            Card(padding = 16.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.size(132.dp).border(1.5.dp, C.faint, RoundedCornerShape(10.dp)).padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                    ) {
                        T(t("Sign-in code"), TS.Caption, color = C.muted)
                        T(p.code.chunked(3).joinToString(" "), TS.Stat, FontWeight.Medium, mono = true)
                    }
                    Column(Modifier.weight(1f)) {
                        T(t("New device"), TS.Body, FontWeight.SemiBold)
                        T(t("Install {app} on the other device, choose “{button}” and enter this code. It expires in 10 minutes.", "app" to APP_NAME, "button" to t("Sign in with a device code")), TS.Label, color = C.muted, modifier = Modifier.padding(top = 4.dp))
                        T(t("Need more devices?"), TS.Small, FontWeight.Medium, C.primary, modifier = Modifier.padding(top = Space.sm).clickable(role = Role.Button) { s.router.go(Route.Plans) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(d: Device, now: Long, expanded: Boolean, onMore: () -> Unit) {
    val icon = when (d.platform) { Platform.IOS, Platform.ANDROID -> if (d.name.contains("tablet", true)) Ic.Tablet else Ic.Phone; Platform.DESKTOP, Platform.OTHER -> Ic.Laptop }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(icon, C.lineSoft, C.ink)
        Column(Modifier.weight(1f)) {
            T(d.name, TS.Body, FontWeight.SemiBold, maxLines = 1)
            // As in the draft: address, then last contact. The protocol shows when the row is opened.
            val seen = if (d.tunnelAddress == null) t("no tunnel yet") else t("seen {ago}", "ago" to fAgo(d.lastSeenAt, now))
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                d.tunnelAddress?.let { T(it, TS.Caption, color = C.muted, mono = true, maxLines = 1) }
                T(seen, TS.Caption, color = C.muted, maxLines = 1)
            }
            if (expanded && d.protocol != null) T(t("Protocol: {name}", "name" to t(ProtocolInfo.label(d.protocol!!))), TS.Caption, color = C.muted, modifier = Modifier.padding(top = 2.dp))
        }
        when {
            d.isCurrent -> dev.jaganet.app.ui.Badge(t("This device"), C.primaryTint, C.primaryDark)
            d.online -> dev.jaganet.app.ui.Badge(t("Online"), C.greenTint, C.greenDark)
            else -> dev.jaganet.app.ui.Badge(t("Offline"))
        }
        // The menu column is always there, so badges line up in every row.
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .then(if (d.isCurrent) Modifier else Modifier.clickable(role = Role.Button, onClick = onMore).semantics { contentDescription = t("More options for {name}", "name" to d.name) }),
            contentAlignment = Alignment.Center,
        ) { if (!d.isCurrent) Icon(if (expanded) Ic.Close else Ic.More, C.muted, 20.dp, 2.2f) }
    }
}
