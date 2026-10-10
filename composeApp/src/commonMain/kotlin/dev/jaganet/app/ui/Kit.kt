package dev.jaganet.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jaganet.app.i18n.t
import dev.jaganet.app.theme.C
import dev.jaganet.app.theme.LocalFonts
import dev.jaganet.app.theme.R
import dev.jaganet.app.theme.Space

/* ---------- text ---------- */

/** Type scale on a 4 dp line grid. */
enum class TS(val size: TextUnit, val line: TextUnit) {
    Display(32.sp, 40.sp), Title(24.sp, 32.sp), Plan(20.sp, 28.sp), Brand(18.sp, 24.sp), Stat(20.sp, 28.sp),
    Body(15.sp, 20.sp), Small(14.sp, 20.sp), Label(13.sp, 16.sp), Caption(12.sp, 16.sp), Tab(11.sp, 16.sp),
}

@Composable
fun T(
    text: String,
    style: TS = TS.Body,
    weight: FontWeight = FontWeight.Normal,
    color: Color = C.ink,
    /** Numbers: tabular figures, so columns of digits line up. */
    mono: Boolean = false,
    modifier: Modifier = Modifier,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    /** Codes and logs: the monospaced face. */
    code: Boolean = false,
) {
    val f = LocalFonts.current
    BasicText(
        text,
        modifier,
        style = TextStyle(
            fontFamily = if (code) f.mono else f.sans,
            fontWeight = if (code && weight > FontWeight.Medium) FontWeight.Medium else weight,
            fontFeatureSettings = if (mono) "tnum" else null,
            fontSize = style.size,
            lineHeight = style.line,
            color = color,
            textAlign = align ?: TextAlign.Unspecified,
            letterSpacing = letterSpacing,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Title(text: String, color: Color = C.ink) =
    T(text, TS.Title, FontWeight.SemiBold, color, modifier = Modifier.semantics { heading() })

@Composable
fun SectionLabel(text: String, dark: Boolean = false) = T(
    text, TS.Small, FontWeight.SemiBold, if (dark) C.nightText else C.ink,
    modifier = Modifier.padding(top = Space.xl, bottom = Space.sm).semantics { heading() },
)

/* ---------- layout ---------- */

/** Scrollable screen on the paper background. */
@Composable
fun Screen(
    dark: Boolean = false,
    onBack: (() -> Unit)? = null,
    scroll: Boolean = true,
    bottomPadding: Dp = 32.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(if (dark) C.night else C.paper)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(start = Space.gutter, end = Space.gutter, top = if (onBack != null) 4.dp else Space.lg, bottom = bottomPadding),
    ) {
        if (onBack != null) {
            Box(
                Modifier.offset(x = (-10).dp).size(44.dp).clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = t("Back"), onClick = onBack)
                    .semantics { contentDescription = t("Back") },
                contentAlignment = Alignment.Center,
            ) { Icon(Ic.ChevronLeft, if (dark) C.nightText else C.ink, strokeWidth = 2f) }
        }
        content()
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, dark: Boolean = false, padding: Dp = 0.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(R.card)
    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(if (dark) C.nightCard else C.surface)
            .then(if (dark) Modifier else Modifier.border(1.dp, C.line, shape))
            .padding(padding),
        content = content,
    )
}

/** Label / value line used in detail cards. */
@Composable
fun KV(k: String, v: String, mono: Boolean = false, dark: Boolean = false) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    T(k, TS.Small, color = if (dark) C.nightMuted else C.muted)
    T(v, TS.Small, mono = mono, color = if (dark) C.nightText else C.ink)
}

/** Settings-style row inside a Card. */
@Composable
fun ListRow(
    label: String,
    hint: String? = null,
    value: String? = null,
    last: Boolean = false,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Column(Modifier.weight(1f)) {
            T(label, TS.Body, FontWeight.Medium, if (danger) C.warnText else C.ink)
            if (hint != null) T(hint, TS.Label, color = C.muted, modifier = Modifier.padding(top = 2.dp))
        }
        if (value != null) T(value, TS.Small, color = C.muted)
        if (trailing != null) trailing() else if (onClick != null) Icon(Ic.ChevronRight, C.muted, 18.dp, 2f)
    }
    if (!last) Divider()
}

@Composable
fun Divider(color: Color = C.lineSoft) = Box(Modifier.fillMaxWidth().height(1.dp).background(color))

/* ---------- controls ---------- */

enum class ButtonKind(val bg: Color, val fg: Color, val border: Color?) {
    /** The main action of a screen (there is one). */
    Dark(C.primary, Color.White, null),
    Primary(C.primary, Color.White, null),
    Secondary(C.surface, C.ink, C.lineStrong),
    Ghost(Color.Transparent, C.ink, null),
    Danger(C.surface, C.warnText, C.lineStrong),
}

@Composable
fun Button(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Dark,
    icon: Ic? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val shape = RoundedCornerShape(R.button)
    Row(
        modifier.heightIn(min = 44.dp).clip(shape).background(if (enabled) kind.bg else if (kind.border == null && kind.bg != Color.Transparent) C.lineSoft else kind.bg)
            .then(kind.border?.let { Modifier.border(BorderStroke(1.dp, it), shape) } ?: Modifier)
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.lg),
        horizontalArrangement = Arrangement.spacedBy(Space.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, kind.fg, 18.dp, 2f)
        T(if (busy) "…" else label, TS.Body, FontWeight.Medium, if (enabled) kind.fg else C.faint)
    }
}

@Composable
fun Toggle(value: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Box(
        Modifier.size(44.dp, 24.dp).clip(CircleShape).background(if (value) C.primary else C.toggleOff)
            .toggleable(value, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = label }
            .padding(2.dp),
        contentAlignment = if (value) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(20.dp).clip(CircleShape).background(Color.White)) }
}

@Composable
fun <K> Segmented(options: List<Pair<K, String>>, value: K, onChange: (K) -> Unit) {
    // A button group: equal segments, 1 dp borders between them, the chosen one filled gray.
    val shape = RoundedCornerShape(R.button)
    Row(Modifier.fillMaxWidth().height(40.dp).clip(shape).border(1.dp, C.lineStrong, shape)) {
        options.forEachIndexed { i, (k, l) ->
            val on = k == value
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(C.lineStrong))
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(if (on) C.lineSoft else C.surface)
                    .selectable(on, role = Role.Tab) { onChange(k) },
                contentAlignment = Alignment.Center,
            ) { T(l, TS.Small, if (on) FontWeight.SemiBold else FontWeight.Normal, C.ink, maxLines = 1) }
        }
    }
}

@Composable
fun Radio(on: Boolean) = Box(
    Modifier.size(20.dp).clip(CircleShape).border(if (on) 6.dp else 1.dp, if (on) C.primary else C.faint, CircleShape),
)

@Composable
fun Progress(value: Float, tone: Color = C.primary, track: Color = C.lineSoft) {
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(track)) {
        Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(4.dp).background(tone))
    }
}

@Composable
fun Pill(text: String, bg: Color, fg: Color, onClick: (() -> Unit)? = null) = Box(
    Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(R.button)).background(bg)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(horizontal = 12.dp),
    contentAlignment = Alignment.Center,
) { T(text, TS.Small, FontWeight.Medium, fg) }

/** Small status label: 20 dp tall, fully rounded, tinted. */
@Composable
fun Badge(text: String, bg: Color = C.lineSoft, fg: Color = C.ink) = Box(
    Modifier.height(20.dp).clip(CircleShape).background(bg).padding(horizontal = 8.dp),
    contentAlignment = Alignment.Center,
) { T(text, TS.Caption, FontWeight.Medium, fg, maxLines = 1) }

@Composable
fun IconTile(icon: Ic, bg: Color = C.lineSoft, fg: Color = C.ink, size: Dp = 36.dp) = Box(
    Modifier.size(size).clip(RoundedCornerShape(R.icon)).background(bg),
    contentAlignment = Alignment.Center,
) { Icon(icon, fg, 20.dp) }

@Composable
fun Loading(dark: Boolean = false) = Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
    T(t("Loading…"), TS.Small, color = if (dark) C.nightMuted else C.muted)
}

@Composable
fun ErrorNote(message: String?) {
    if (message != null) T(message, TS.Label, color = C.warnText, modifier = Modifier.padding(top = Space.sm))
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun HGap(w: Dp) = Spacer(Modifier.width(w))

/* ---------- data loading ---------- */

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/** Loads `fetch` and reloads whenever any key changes (e.g. AppState.dataVersion). */
@Composable
fun <T> load(vararg keys: Any?, fetch: suspend () -> T): Load<T> {
    var state by remember { mutableStateOf<Load<T>>(Load.Loading) }
    LaunchedEffect(*keys) {
        state = try {
            Load.Ok(fetch())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Load.Failed(e.message ?: t("Something went wrong"))
        }
    }
    return state
}

/** Renders loading / error states, and `content` once loaded. */
@Composable
fun <T> Loaded(l: Load<T>, dark: Boolean = false, content: @Composable (T) -> Unit) = when (l) {
    is Load.Loading -> Loading(dark)
    is Load.Failed -> ErrorNote(l.message)
    is Load.Ok -> content(l.value)
}
