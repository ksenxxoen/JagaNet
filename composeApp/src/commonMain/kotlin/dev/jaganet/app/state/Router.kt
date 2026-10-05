package dev.jaganet.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Every screen in the app. Tabs are the first four. */
sealed interface Route {
    data object SignIn : Route
    data class Verify(val email: String, val devCode: String?) : Route
    data object Pair : Route
    data object Home : Route
    data object Stats : Route
    data object Devices : Route
    data object Settings : Route
    data object Plans : Route
    data object Account : Route
    data object Referral : Route
    data object SplitTunnel : Route
    data object Protocol : Route
    data object Logs : Route
    data object Admin : Route
}

val TABS = listOf(Route.Home, Route.Stats, Route.Devices, Route.Settings)

/** Minimal stack navigator: tabs replace the stack root, other screens push. */
class Router(start: Route) {
    var stack by mutableStateOf(listOf(start))
        private set
    val current get() = stack.last()

    fun go(r: Route) {
        stack = if (r in TABS) listOf(r) else stack + r
    }
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack = stack.dropLast(1)
        return true
    }
    fun reset(r: Route) {
        stack = listOf(r)
    }
}
