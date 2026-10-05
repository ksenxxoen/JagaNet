package dev.jaganet.app.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size

private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"
private fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float) =
    "M${x + rx} ${y}h${w - 2 * rx}a$rx $rx 0 0 1 $rx ${rx}v${h - 2 * rx}a$rx $rx 0 0 1 ${-rx} ${rx}h${-(w - 2 * rx)}a$rx $rx 0 0 1 ${-rx} ${-rx}v${-(h - 2 * rx)}a$rx $rx 0 0 1 $rx ${-rx}z"

/** Stroke icons from the design canvas (24×24 grid, round caps). */
enum class Ic(vararg val paths: String) {
    Shield("M12 3l8 3v6c0 4.5-3.4 8.2-8 9-4.6-.8-8-4.5-8-9V6l8-3z"),
    Power("M12 3v8", "M6.3 6.8a8 8 0 1 0 11.4 0"),
    Stats("M5 20v-6M12 20V5M19 20v-10"),
    Devices(rect(2f, 4f, 14f, 10f, 1.5f), "M6 18h6", rect(17f, 8f, 5f, 12f, 1f)),
    Settings("M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12", circle(16f, 6f, 2f), circle(10f, 12f, 2f), circle(18f, 18f, 2f)),
    Globe(circle(12f, 12f, 9f), "M3 12h18M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3z"),
    ChevronRight("M9 6l6 6-6 6"),
    ChevronLeft("M15 6l-6 6 6 6"),
    Close("M6 6l12 12M18 6L6 18"),
    ArrowDown("M12 5v14M6 13l6 6 6-6"),
    ArrowUp("M12 19V5M6 11l6-6 6 6"),
    Phone(rect(6f, 2f, 12f, 20f, 2f), "M11 18h2"),
    Laptop(rect(4f, 4f, 16f, 11f, 1.5f), "M2 19h20"),
    Tablet(rect(4f, 2f, 16f, 20f, 2f), "M11 18h2"),
    Plus("M12 5v14M5 12h14"),
    Check("M5 12.5l4.5 4.5L19 7"),
    Gift(rect(3f, 8f, 18f, 13f, 1.5f), "M12 8v13M3 12h18M12 8c-2-4-6-4-6-1.5S10 8 12 8zm0 0c2-4 6-4 6-1.5S14 8 12 8z"),
    Share("M12 3v12M7 8l5-5 5 5M5 13v6a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6"),
    Warning("M12 3l9 16H3z", "M12 10v4M12 17v.5"),
    Mail(rect(3f, 5f, 18f, 14f, 2f), "M3 7l9 6 9-6"),
    Layers("M12 3l9 5-9 5-9-5 9-5zM3 13l9 5 9-5"),
    More(circle(12f, 5f, 0.8f), circle(12f, 12f, 0.8f), circle(12f, 19f, 0.8f)),
    ;

    fun vector(strokeWidth: Float): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach {
            addPath(
                pathData = addPathNodes(it),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
}

@Composable
fun Icon(icon: Ic, tint: Color, size: Dp = 24.dp, strokeWidth: Float = 1.8f, modifier: Modifier = Modifier) {
    val v = remember(icon, strokeWidth) { icon.vector(strokeWidth) }
    Image(v, contentDescription = null, modifier = modifier.size(size), colorFilter = ColorFilter.tint(tint))
}
