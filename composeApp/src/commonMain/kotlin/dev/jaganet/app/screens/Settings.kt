package dev.jaganet.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jaganet.api.Format
import dev.jaganet.api.Platform
import dev.jaganet.api.PlanId
import dev.jaganet.api.ProductId
import dev.jaganet.api.i18n.Lang
import dev.jaganet.api.Role as UserRole
import dev.jaganet.app.i18n.AppLang
import dev.jaganet.app.i18n.t
import dev.jaganet.app.i18n.tp
import dev.jaganet.app.state.AppState
import dev.jaganet.app.state.Route
import dev.jaganet.app.theme.C
import dev.jaganet.app.theme.R
import dev.jaganet.app.tunnel.LogLevel
import dev.jaganet.app.tunnel.ProtocolInfo
import dev.jaganet.app.tunnel.SplitMode
import dev.jaganet.app.ui.Button
import dev.jaganet.app.ui.ButtonKind
import dev.jaganet.app.ui.Card
import dev.jaganet.app.ui.Gap
import dev.jaganet.app.ui.Ic
import dev.jaganet.app.ui.Icon
import dev.jaganet.app.ui.ListRow
import dev.jaganet.app.ui.Load
import dev.jaganet.app.ui.Loaded
import dev.jaganet.app.ui.Radio
import dev.jaganet.app.ui.Screen
import dev.jaganet.app.ui.SectionLabel
import dev.jaganet.app.ui.T
import dev.jaganet.app.ui.TS
import dev.jaganet.app.ui.Title
import dev.jaganet.app.ui.Toggle
import dev.jaganet.app.ui.load

@Composable
fun SettingsScreen(s: AppState) {
    val st by s.settings.state.collectAsState()
    val me = load(s.dataVersion) { s.api.me() }
    val ios = s.platform.kind == Platform.IOS
    Screen {
        Title(t("Settings"))
        Gap(16.dp)
        Card(Modifier.clickable(role = Role.Button) { s.router.go(Route.Account) }, padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(C.ink), contentAlignment = Alignment.Center) {
                    T(s.user?.email?.take(1)?.uppercase() ?: "?", TS.Body, FontWeight.SemiBold, Color.White)
                }
                Column(Modifier.weight(1f)) {
                    T(s.user?.email ?: "", TS.Body, FontWeight.SemiBold, maxLines = 1)
                    T((me as? Load.Ok)?.value?.entitlement?.let { e ->
                        when (e.productId) {
                            ProductId.PRO_MONTHLY -> t("Pro monthly")
                            ProductId.PRO_YEARLY -> t("Pro yearly")
                            null -> when (e.plan) { PlanId.FREE -> t("Free"); PlanId.PRO -> t("Pro") }
                        }
                    } ?: "", TS.Label, color = C.muted)
                }
                Icon(Ic.ChevronRight, C.muted, 18.dp, 2f)
            }
        }
        Gap(10.dp)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(R.card)).background(C.greenTint).clickable(role = Role.Button) { s.router.go(Route.Referral) }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Ic.Gift, C.greenDark, 20.dp)
            T(t("Invite friends"), TS.Small, FontWeight.SemiBold, C.greenDark, modifier = Modifier.weight(1f))
            Icon(Ic.ChevronRight, C.greenDark, 18.dp, 2f)
        }

        SectionLabel(t("Language"))
        Card {
            Lang.entries.forEach { lang ->
                LanguageOption(lang.nativeName, AppLang.current == lang) { s.setLanguage(lang) }
            }
        }

        SectionLabel(t("Connection"))
        Card {
            ListRow(t("Kill switch"), if (ios) t("Route all traffic through the VPN while it’s on") else t("Block internet if the VPN drops"), trailing = {
                Toggle(st.killSwitch, t("Kill switch")) { v -> s.settings.update { it.copy(killSwitch = v) } }
            })
            ListRow(t("Auto-connect"), t("On Wi-Fi networks you don’t trust"), trailing = {
                Toggle(st.autoConnect, t("Auto-connect")) { v -> s.settings.update { it.copy(autoConnect = v) } }
            })
            ListRow(t("Protocol"), value = st.protocol?.let { t(ProtocolInfo.label(it)) } ?: t("Automatic"), onClick = { s.router.go(Route.Protocol) })
            if (ios) ListRow(t("Split tunneling"), t("Not available on iPhone: iOS doesn’t allow per-app VPN outside managed devices"), last = true)
            else ListRow(t("Split tunneling"), value = if (st.split.mode == SplitMode.ALL) t("Off") else tp(st.split.apps.size, "{n} app|{n} apps"), last = true, onClick = { s.router.go(Route.SplitTunnel) })
        }

        SectionLabel(t("App"))
        Card {
            ListRow(t("Notifications"), t("Disconnects and billing reminders"), last = ios, trailing = {
                Toggle(st.notifications, t("Notifications")) { v -> s.settings.update { it.copy(notifications = v) } }
            })
            if (!ios) ListRow(t("Start on boot"), t("Reconnect after the phone restarts"), last = true, trailing = {
                Toggle(st.startOnBoot, t("Start on boot")) { v -> s.settings.update { it.copy(startOnBoot = v) } }
            })
        }

        if (s.user?.role == UserRole.OWNER) {
            SectionLabel(t("Owner"))
            Card { ListRow(t("Business dashboard"), t("Revenue, users and server health"), last = true, onClick = { s.router.go(Route.Admin) }) }
        }

        SectionLabel(t("Help"))
        Card {
            ListRow(t("Contact support"), value = t("Usually replies in a day"), onClick = { s.platform.openUrl("mailto:support@jaganet.dev") })
            ListRow(t("Connection log"), onClick = { s.router.go(Route.Logs) })
            ListRow(t("Privacy policy"), onClick = { s.platform.openUrl("https://jaganet.dev/privacy") })
            ListRow(t("Terms of service"), value = "v0.1.0", last = true, onClick = { s.platform.openUrl("https://jaganet.dev/terms") })
        }
        Gap(16.dp)
        Button(t("Sign out"), { s.signOut() }, Modifier.fillMaxWidth(), ButtonKind.Ghost)
    }
}

@Composable
fun ProtocolScreen(s: AppState) {
    val st by s.settings.state.collectAsState()
    val servers = load(Unit) { s.api.servers().servers }
    Screen(onBack = { s.router.back() }) {
        Gap(8.dp)
        Title(t("Protocol"))
        T(t("How this device talks to the server. Automatic picks the best one both support."), TS.Small, color = C.muted, modifier = Modifier.padding(top = 6.dp))
        Gap(18.dp)
        Loaded(servers) { list ->
            val server = list.firstOrNull { it.id == st.serverId } ?: list.firstOrNull()
            val offered = server?.protocols.orEmpty()
            Card {
                ProtocolOption(t("Automatic"), t("Recommended"), st.protocol == null) { s.settings.update { it.copy(protocol = null) } }
                offered.forEach { id ->
                    val supported = id in s.tunnel.engine.protocols
                    ProtocolOption(
                        t(ProtocolInfo.label(id)),
                        if (supported) t(ProtocolInfo.description(id)) else t("Not supported on this device yet"),
                        st.protocol == id, enabled = supported,
                    ) { s.settings.update { it.copy(protocol = id) } }
                }
            }
            T(t("Changes apply the next time you connect."), TS.Caption, color = C.muted, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
        }
    }
}

@Composable
private fun ProtocolOption(title: String, hint: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).selectable(selected, enabled, Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Radio(selected)
        Column(Modifier.weight(1f)) {
            T(title, TS.Body, FontWeight.SemiBold, if (enabled) C.ink else C.muted)
            T(hint, TS.Label, color = C.muted)
        }
    }
}

/** One language in the picker. Names are always shown in their own language. */
@Composable
private fun LanguageOption(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Radio(selected)
        T(name, TS.Body, FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

@Composable
fun SplitTunnelScreen(s: AppState) {
    val st by s.settings.state.collectAsState()
    val apps = load(Unit) { s.tunnel.engine.installedApps() }
    Screen(onBack = { s.router.back() }) {
        Gap(8.dp)
        Title(t("Split tunneling"))
        T(t("Choose which apps use the tunnel. Leave out apps that don’t need protection, like local banking or streaming."), TS.Small, color = C.muted, modifier = Modifier.padding(top = 6.dp))
        Gap(18.dp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(SplitMode.ALL to t("All apps use the VPN"), SplitMode.ONLY to t("Only selected apps"), SplitMode.EXCLUDE to t("All except selected apps")).forEach { (mode, label) ->
                val on = st.split.mode == mode
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(R.button)).background(C.surface)
                        .border(if (on) 2.dp else 1.dp, if (on) C.green else C.line, RoundedCornerShape(R.button))
                        .selectable(on, role = Role.RadioButton) { s.settings.update { it.copy(split = it.split.copy(mode = mode)) } }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Radio(on)
                    T(label, TS.Body, FontWeight.Medium)
                }
            }
        }
        if (st.split.mode == SplitMode.ALL) {
            T(t("Every app goes through your server."), TS.Small, color = C.muted, modifier = Modifier.padding(top = 16.dp, start = 4.dp))
        } else {
            SectionLabel(if (st.split.mode == SplitMode.ONLY) t("Apps that use the VPN") else t("Apps that skip the VPN"))
            Loaded(apps) { list ->
                Card {
                    list.forEachIndexed { i, app ->
                        val checked = app.id in st.split.apps
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                .toggleable(checked, role = Role.Checkbox) { v ->
                                    s.settings.update { it.copy(split = it.split.copy(apps = if (v) it.split.apps + app.id else it.split.apps - app.id)) }
                                }.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(C.lineSoft), contentAlignment = Alignment.Center) {
                                T(app.label.take(1), TS.Small, FontWeight.SemiBold)
                            }
                            T(app.label, TS.Body, modifier = Modifier.weight(1f))
                            Box(
                                Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (checked) C.green else C.surface)
                                    .border(2.dp, if (checked) C.green else C.faint, RoundedCornerShape(6.dp)),
                                contentAlignment = Alignment.Center,
                            ) { if (checked) Icon(Ic.Check, Color.White, 16.dp, 2.6f) }
                        }
                        if (i < list.lastIndex) dev.jaganet.app.ui.Divider()
                    }
                }
            }
        }
    }
}

@Composable
fun LogsScreen(s: AppState) {
    var filter by remember { mutableStateOf("all") }
    val lines = s.tunnel.logs.filter { filter == "all" || (filter == "warn" && it.level != LogLevel.INFO) || (filter == "error" && it.level == LogLevel.ERROR) }
    val text = lines.joinToString("\n") { "${Format.clockOfDay(it.time)}  ${it.level}  ${it.msg}" }
    Screen(onBack = { s.router.back() }) {
        Gap(8.dp)
        Title(t("Connection log"))
        T(t("Kept only on this device."), TS.Small, color = C.muted, modifier = Modifier.padding(top = 4.dp))
        Gap(14.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to t("All"), "warn" to t("Warnings"), "error" to t("Errors")).forEach { (k, l) ->
                val on = filter == k
                Box(
                    Modifier.heightIn(min = 36.dp).clip(CircleShape).background(if (on) C.ink else C.surface)
                        .border(1.dp, if (on) C.ink else C.lineStrong, CircleShape)
                        .selectable(on, role = Role.Tab) { filter = k }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { T(l, TS.Label, FontWeight.Medium, if (on) Color.White else C.ink) }
            }
        }
        Gap(14.dp)
        Column(Modifier.fillMaxWidth().heightIn(min = 360.dp).clip(RoundedCornerShape(R.tile)).background(C.night).padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (lines.isEmpty()) T(t("Nothing logged yet. Connect to see tunnel events."), TS.Caption, color = C.nightMuted, mono = true)
            lines.forEach { l ->
                Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    T(Format.clockOfDay(l.time), TS.Caption, color = C.faint, mono = true)
                    T(l.level.name, TS.Caption, FontWeight.Medium, when (l.level) { LogLevel.ERROR -> C.logError; LogLevel.WARN -> C.logWarn; LogLevel.INFO -> C.logInfo }, mono = true, modifier = Modifier.size(width = 44.dp, height = 16.dp))
                    T(l.msg, TS.Caption, color = C.logText, mono = true)
                }
            }
        }
        Gap(14.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(t("Copy"), { s.platform.copy(text) }, Modifier.weight(1f), ButtonKind.Secondary)
            Button(t("Share"), { s.platform.share(text) }, Modifier.weight(1f))
        }
    }
}
