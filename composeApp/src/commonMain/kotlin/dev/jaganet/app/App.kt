package dev.jaganet.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jaganet.app.i18n.t
import dev.jaganet.app.platform.AppPlatform
import dev.jaganet.app.screens.AccountScreen
import dev.jaganet.app.screens.AdminScreen
import dev.jaganet.app.screens.DevicesScreen
import dev.jaganet.app.screens.HomeScreen
import dev.jaganet.app.screens.LogsScreen
import dev.jaganet.app.screens.PairScreen
import dev.jaganet.app.screens.PlansScreen
import dev.jaganet.app.screens.ProtocolScreen
import dev.jaganet.app.screens.ReferralScreen
import dev.jaganet.app.screens.SettingsScreen
import dev.jaganet.app.screens.SignInScreen
import dev.jaganet.app.screens.SplitTunnelScreen
import dev.jaganet.app.screens.StatsScreen
import dev.jaganet.app.screens.VerifyScreen
import dev.jaganet.app.state.AppState
import dev.jaganet.app.state.Route
import dev.jaganet.app.state.TABS
import dev.jaganet.app.theme.C
import dev.jaganet.app.theme.LocalFonts
import dev.jaganet.app.theme.rememberFonts
import dev.jaganet.app.ui.Divider
import dev.jaganet.app.ui.Ic
import dev.jaganet.app.ui.Icon
import dev.jaganet.app.ui.T
import dev.jaganet.app.ui.TS

/** Product name in one place: the drafts called it "Burrow", the project is JagaNet. */
const val APP_NAME = "JagaNet"

@Composable
fun App(platform: AppPlatform) {
    val state = remember { AppState(platform) }
    App(state)
}

@Composable
fun App(state: AppState) {
    CompositionLocalProvider(LocalFonts provides rememberFonts()) {
        Column(Modifier.fillMaxSize().background(C.paper)) {
            Box(Modifier.weight(1f)) { RouteContent(state, state.router.current) }
            if (state.router.current in TABS) TabBar(state)
        }
    }
}

@Composable
private fun RouteContent(s: AppState, r: Route) = when (r) {
    Route.SignIn -> SignInScreen(s)
    is Route.Verify -> VerifyScreen(s, r)
    Route.Pair -> PairScreen(s)
    Route.Home -> HomeScreen(s)
    Route.Stats -> StatsScreen(s)
    Route.Devices -> DevicesScreen(s)
    Route.Settings -> SettingsScreen(s)
    Route.Plans -> PlansScreen(s)
    Route.Account -> AccountScreen(s)
    Route.Referral -> ReferralScreen(s)
    Route.SplitTunnel -> SplitTunnelScreen(s)
    Route.Protocol -> ProtocolScreen(s)
    Route.Logs -> LogsScreen(s)
    Route.Admin -> AdminScreen(s)
}

@Composable
private fun TabBar(s: AppState) {
    Column(Modifier.background(C.surface).windowInsetsPadding(WindowInsets.navigationBars)) {
        Divider(C.line)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceAround) {
            listOf(Route.Home to (Ic.Shield to t("Connect")), Route.Stats to (Ic.Stats to t("Stats")), Route.Devices to (Ic.Devices to t("Devices")), Route.Settings to (Ic.Settings to t("Settings")))
                .forEach { (route, v) ->
                    val on = s.router.current == route
                    val color = if (on) C.green else C.muted
                    Column(
                        Modifier.widthIn(min = 64.dp).heightIn(min = 48.dp)
                            .clickable(role = Role.Tab) { s.router.go(route) }
                            .semantics { selected = on },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(v.first, color)
                        T(v.second, TS.Tab, if (on) FontWeight.SemiBold else FontWeight.Medium, color)
                    }
                }
        }
    }
}
