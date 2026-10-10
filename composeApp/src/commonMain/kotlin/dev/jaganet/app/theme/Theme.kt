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
import jaganet.composeapp.generated.resources.inter_bold
import jaganet.composeapp.generated.resources.inter_medium
import jaganet.composeapp.generated.resources.inter_regular
import jaganet.composeapp.generated.resources.inter_semibold
import org.jetbrains.compose.resources.Font

/**
 * Design tokens, after GitLab's Pajamas design system: neutral grays, one blue for
 * actions and selection, green only for "protected / active / online", 4 dp corners on
 * controls and 8 dp on panels, 1 dp borders, no shadows. Screens use these names, never
 * raw values, so a re-theme (or dark mode) is a change here only.
 */
object C {
    // Surfaces and text
    val paper = Color(0xFFFBFAFD) // screen background
    val surface = Color(0xFFFFFFFF) // cards, tab bar
    val ink = Color(0xFF1F1E24) // headings, primary text
    val muted = Color(0xFF626168) // secondary text
    val faint = Color(0xFF89888D) // input borders, icons at rest, never body text
    val line = Color(0xFFDCDCDE) // card borders
    val lineSoft = Color(0xFFECECEF) // row dividers, tracks, selected segment
    val lineStrong = Color(0xFFBFBFC3) // secondary button borders
    val toggleOff = Color(0xFFBFBFC3)

    // Actions and selection
    val primary = Color(0xFF1F75CB)
    val primaryDark = Color(0xFF0B5CAD) // text on primaryTint
    val primaryTint = Color(0xFFE9F3FC)

    // State
    val green = Color(0xFF108548) // protected, active, online
    val greenDark = Color(0xFF24663B) // text on greenTint
    val greenTint = Color(0xFFECF4EE)
    val warn = Color(0xFFAB6100) // "not protected" dot, quota bar
    val warnText = Color(0xFFAE1800) // "not protected" label, destructive text
    val warnTint = Color(0xFFFDF1DD)

    // Charts: blue for the main series, a lighter blue for the second one
    val chart1 = primary
    val chart2 = Color(0xFF97ACE8)

    // The owner dashboard uses the same light surfaces as every other screen.
    val night = paper
    val nightCard = surface
    val nightTrack = lineSoft
    val nightMuted = muted
    val nightText = ink
    val chartGreen = chart2
    val alert = warn
    val alertBg = warnTint
    val alertText = Color(0xFF8F4700)

    // Connection log console (dark, like a terminal)
    val console = Color(0xFF1F1E24)
    val consoleMuted = Color(0xFFBFBFC3)
    val logInfo = Color(0xFF8FC7FF)
    val logWarn = Color(0xFFF5D9A8)
    val logError = Color(0xFFFCB5AA)
    val logText = Color(0xFFECECEF)
}

/** Corners: 4 dp for controls, 8 dp for panels. */
object R {
    val icon = 4.dp
    val button = 4.dp
    val tile = 8.dp
    val card = 8.dp
    val hero = 8.dp
}

/** A 4 dp grid. */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val gutter = 16.dp
    val xl = 24.dp
}

/** Inter for everything; the mono face only for codes and the connection log. */
class Fonts(val sans: FontFamily, val mono: FontFamily)

val LocalFonts = staticCompositionLocalOf<Fonts> { error("Fonts not provided") }

@Composable
fun rememberFonts() = Fonts(
    sans = FontFamily(
        Font(Res.font.inter_regular, FontWeight.Normal),
        Font(Res.font.inter_medium, FontWeight.Medium),
        Font(Res.font.inter_semibold, FontWeight.SemiBold),
        Font(Res.font.inter_bold, FontWeight.Bold),
    ),
    mono = FontFamily(
        Font(Res.font.ibm_plex_mono_regular, FontWeight.Normal),
        Font(Res.font.ibm_plex_mono_medium, FontWeight.Medium),
    ),
)
