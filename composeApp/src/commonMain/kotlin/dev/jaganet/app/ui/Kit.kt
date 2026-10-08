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

enum class TS(val size: TextUnit, val line: TextUnit) {
    Display(34.sp, 40.sp), Title(28.sp, 34.sp), Plan(24.sp, 30.sp), Brand(20.sp, 26.sp), Stat(20.sp, 26.sp),
    Body(15.sp, 21.sp), Small(14.sp, 20.sp), Label(13.sp, 18.sp), Caption(12.sp, 16.sp), Tab(11.sp, 14.sp),
}

@Composable
fun T(
    text: String,
    style: TS = TS.Body,
    weight: FontWeight = FontWeight.Normal,
    color: Color = C.ink,
    mono: Boolean = false,
    modifier: Modifier = Modifier,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) {
    val f = LocalFonts.current
    BasicText(
        text,
        modifier,
        style = TextStyle(
            fontFamily = if (mono) f.mono else f.sans,
            fontWeight = if (mono && weight > FontWeight.Medium) FontWeight.Medium else weight,
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
    T(text, TS.Title, FontWeight.Bold, color, letterSpacing = (-0.3).sp, modifier = Modifier.semantics { heading() })

@Composable
fun SectionLabel(text: String, dark: Boolean = false) = T(
    text.uppercase(), TS.Caption, FontWeight.SemiBold, if (dark) C.nightMuted else C.muted,
    letterSpacing = 0.7.sp, modifier = Modifier.padding(start = 4.dp, top = Space.xl, bottom = Space.sm),
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
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = Space.lg, vertical = 10.dp),
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
    Dark(C.ink, Color.White, null),
    Primary(C.green, Color.White, null),
    Secondary(C.surface, C.ink, C.lineStrong),
    Ghost(Color.Transparent, C.ink, null),
    Danger(Color.Transparent, C.warnText, null),
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
        modifier.heightIn(min = 50.dp).clip(shape).background(kind.bg)
            .then(kind.border?.let { Modifier.border(BorderStroke(1.dp, it), shape) } ?: Modifier)
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.lg),
        horizontalArrangement = Arrangement.spacedBy(Space.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, kind.fg, 18.dp, 2f)
        T(if (busy) "…" else label, TS.Body, FontWeight.SemiBold, if (enabled) kind.fg else kind.fg.copy(alpha = 0.45f))
    }
}

@Composable
fun Toggle(value: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Box(
        Modifier.size(50.dp, 30.dp).clip(CircleShape).background(if (value) C.green else C.toggleOff)
            .toggleable(value, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = label }
            .padding(3.dp),
        contentAlignment = if (value) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(24.dp).clip(CircleShape).background(Color.White)) }
}

@Composable
fun <K> Segmented(options: List<Pair<K, String>>, value: K, onChange: (K) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(R.button)).background(C.lineSoft).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (k, l) ->
            val on = k == value
            Box(
                Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (on) C.ink else Color.Transparent)
                    .selectable(on, role = Role.Tab) { onChange(k) },
                contentAlignment = Alignment.Center,
            ) { T(l, TS.Label, FontWeight.Medium, if (on) Color.White else C.ink) }
        }
    }
}

@Composable
fun Radio(on: Boolean) = Box(
    Modifier.size(22.dp).clip(CircleShape).border(if (on) 7.dp else 2.dp, if (on) C.green else C.faint, CircleShape),
)

@Composable
fun Progress(value: Float, tone: Color = C.green, track: Color = C.lineSoft) {
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(track)) {
        Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(tone))
    }
}

@Composable
fun Pill(text: String, bg: Color, fg: Color, onClick: (() -> Unit)? = null) = Box(
    Modifier.heightIn(min = 36.dp).clip(CircleShape).background(bg)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(horizontal = 14.dp),
    contentAlignment = Alignment.Center,
) { T(text, TS.Label, FontWeight.SemiBold, fg) }

@Composable
fun IconTile(icon: Ic, bg: Color = C.lineSoft, fg: Color = C.ink, size: Dp = 40.dp) = Box(
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
