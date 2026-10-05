package dev.jaganet.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jaganet.app.theme.C
import dev.jaganet.app.theme.LocalFonts
import dev.jaganet.app.theme.R

/** Labelled text field in the design's card style. */
@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    mono: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    val f = LocalFonts.current
    Column(Modifier.fillMaxWidth()) {
        T(label, TS.Label, FontWeight.Medium, C.muted, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        val shape = RoundedCornerShape(R.button)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(
                fontFamily = if (mono) f.mono else f.sans,
                fontSize = if (mono) 22.sp else 16.sp,
                letterSpacing = if (mono) 4.sp else 0.sp,
                color = C.ink,
            ),
            cursorBrush = SolidColor(C.green),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).background(C.surface, shape).border(1.dp, C.lineStrong, shape).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) T(placeholder, if (mono) TS.Stat else TS.Body, color = C.faint, mono = mono)
                    inner()
                }
            },
        )
    }
}
