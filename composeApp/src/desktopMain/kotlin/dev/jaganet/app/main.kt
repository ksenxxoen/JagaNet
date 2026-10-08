package dev.jaganet.app

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.jaganet.app.i18n.t

/** `./gradlew :composeApp:run`: the app at phone size, against `./gradlew :server:sim`. */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = t("{app} simulator", "app" to APP_NAME),
        state = rememberWindowState(size = DpSize(390.dp, 844.dp)),
    ) {
        App(DesktopPlatform())
    }
}
