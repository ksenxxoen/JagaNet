package dev.jaganet.app.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jaganet.composeapp.generated.resources.Res
import jaganet.composeapp.generated.resources.ibm_plex_mono_medium
import jaganet.composeapp.generated.resources.ibm_plex_mono_regular
import jaganet.composeapp.generated.resources.ibm_plex_sans_bold
import jaganet.composeapp.generated.resources.ibm_plex_sans_medium
import jaganet.composeapp.generated.resources.ibm_plex_sans_regular
import jaganet.composeapp.generated.resources.ibm_plex_sans_semibold
import org.jetbrains.compose.resources.Font

/**
 * Design tokens from the JagaNet canvas. Screens use these names, never raw
 * values, so a re-theme (or dark mode) is a change here only.
 */
object C {
    // Light surfaces (all user-facing screens)
    val paper = Color(0xFFF4F3EF) // screen background
    val surface = Color(0xFFFFFFFF) // cards, tab bar
    val ink = Color(0xFF16181A) // primary text, dark buttons
    val muted = Color(0xFF5C605F) // secondary text (4.5:1 on paper)
    val faint = Color(0xFF9A9D9B) // radio rings, dashed borders — never body text
    val line = Color(0xFFE2E0DA) // card borders
    val lineSoft = Color(0xFFECEAE4) // row dividers, progress tracks, power-button halo
    val lineStrong = Color(0xFFD6D3CC) // secondary button borders
    val toggleOff = Color(0xFFC9C6BE)

    // Brand / state
    val green = Color(0xFF1E6B57) // protected, primary action, selected
    val greenDark = Color(0xFF14493B) // text on greenTint
    val greenTint = Color(0xFFDCEBE4) // Pro badge, connected halo
    val warn = Color(0xFFB4501A) // "not protected" dot, quota bar
    val warnText = Color(0xFF9A4312) // "not protected" label, destructive text

    // Dark surfaces (owner dashboard, log console)
    val night = Color(0xFF16181A)
    val nightCard = Color(0xFF222527)
    val nightTrack = Color(0xFF33373A)
    val nightMuted = Color(0xFFB9BCBA)
    val nightText = Color(0xFFF4F3EF)
    val chartGreen = Color(0xFF6FA592)
    val alert = Color(0xFFE0A27E)
    val alertBg = Color(0xFF3A2A20)
    val alertText = Color(0xFFF2C9AE)

    // Log levels on the dark console
    val logInfo = Color(0xFF8FCBB5)
    val logWarn = Color(0xFFF2C46D)
    val logError = Color(0xFFFF9C6E)
    val logText = Color(0xFFE8E6E0)
}

object R {
    val icon = 10.dp
    val button = 12.dp
    val tile = 14.dp
    val card = 16.dp
    val hero = 18.dp
}

object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val gutter = 20.dp
    val xl = 24.dp
}

class Fonts(val sans: FontFamily, val mono: FontFamily)

val LocalFonts = staticCompositionLocalOf<Fonts> { error("Fonts not provided") }

@Composable
fun rememberFonts() = Fonts(
    sans = FontFamily(
        Font(Res.font.ibm_plex_sans_regular, FontWeight.Normal),
        Font(Res.font.ibm_plex_sans_medium, FontWeight.Medium),
        Font(Res.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
        Font(Res.font.ibm_plex_sans_bold, FontWeight.Bold),
    ),
    mono = FontFamily(
        Font(Res.font.ibm_plex_mono_regular, FontWeight.Normal),
        Font(Res.font.ibm_plex_mono_medium, FontWeight.Medium),
    ),
)
