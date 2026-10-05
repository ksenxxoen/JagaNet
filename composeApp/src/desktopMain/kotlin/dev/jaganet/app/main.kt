package dev.jaganet.app

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/** `./gradlew :composeApp:run` — the app at phone size, against `./gradlew :server:sim`. */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "$APP_NAME — simulator",
        state = rememberWindowState(size = DpSize(390.dp, 844.dp)),
    ) {
        App(DesktopPlatform())
    }
}
