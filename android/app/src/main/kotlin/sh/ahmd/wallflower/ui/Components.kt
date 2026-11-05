package sh.ahmd.wallflower.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The pieces wallflower's screens are made of.

/** A rounded chip of text (a key's [faceOf] too), as keys and choices are: lit orange while [on], a step lighter while [pressed]. [input] takes its touches. */
@Composable
internal fun Pill(label: AnnotatedString, on: Boolean, modifier: Modifier = Modifier, input: Modifier = Modifier, pressed: Boolean = false, font: FontFamily = Mono) {
    val background by animateColorAsState(
        when {
            on -> Palette.orange
            pressed -> Palette.bg3
            else -> Palette.bg2
        },
        Motion.effect(),
        label = "pill",
    )
    val ink by animateColorAsState(if (on) Palette.bg0 else Palette.fg, Motion.effect(), label = "pill")
    Text(
        label,
        color = ink,
        fontFamily = font,
        fontSize = 13.sp,
        maxLines = 1,
        inlineContent = glyphs(ink),
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .then(input)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

/** A line between things in a list. */
@Composable
internal fun Rule(modifier: Modifier = Modifier, color: Color = Palette.bg2) =
    Box(modifier.fillMaxWidth().height(1.dp).background(color))

/** A small control floating over a pane, as the mobile view toggle and the pane dots are. */
@Composable
internal fun Modifier.chrome() = this
    .clip(RoundedCornerShape(8.dp))
    .background(Palette.bg1)
    .border(1.dp, Palette.border, RoundedCornerShape(8.dp))

@Composable
internal fun checkboxColors(): CheckboxColors = CheckboxDefaults.colors(checkedColor = Palette.orange, uncheckedColor = Palette.grey, checkmarkColor = Palette.bg0)

@Composable
internal fun Section(label: String) = Text(
    label,
    color = Palette.orange,
    fontFamily = words,
    fontSize = wordSize(12.sp, 15.sp),
    fontWeight = if (grotesque) FontWeight.SemiBold else FontWeight.Bold,
    modifier = Modifier.padding(top = 8.dp),
)

@Composable
internal fun Body(text: String) = Text(
    text,
    color = Palette.grey,
    style = Type.label,
    lineHeight = if (grotesque) 20.sp else TextUnit.Unspecified,
)

@Composable
internal fun Field(label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Palette.dim, style = Type.caption)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Palette.fg, fontFamily = Mono, fontSize = 15.sp),
            cursorBrush = SolidColor(Palette.orange),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.bg1).padding(12.dp),
        )
    }
}

@Composable
internal fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val haptics = haptics()
    val flip = {
        haptics.toggle(!on)
        onChange(!on)
    }
    if (grotesque) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = flip).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(on, onCheckedChange = null, colors = checkboxColors())
            Text(label, color = Palette.fg, fontFamily = Grotesque, fontSize = 15.sp, lineHeight = 20.sp, modifier = Modifier.padding(start = 14.dp).weight(1f))
        }
        return
    }
    Row(Modifier.clickable(onClick = flip), verticalAlignment = Alignment.CenterVertically) {
        Text(if (on) "[x] " else "[ ] ", color = Palette.orange, fontFamily = Mono, fontSize = 13.sp)
        Text(label, color = Palette.fg, fontFamily = Mono, fontSize = 12.sp)
    }
}

@Composable
internal fun Button(label: String, primary: Boolean = false, onClick: () -> Unit) = Text(
    label,
    color = if (primary) Palette.bg0 else Palette.fg,
    fontFamily = words,
    fontSize = wordSize(13.sp, 14.sp),
    fontWeight = if (!primary) FontWeight.Normal else if (grotesque) FontWeight.SemiBold else FontWeight.Bold,
    modifier = Modifier
        .clip(RoundedCornerShape(8.dp))
        .background(if (primary) Palette.green else Palette.bg3)
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp),
)

internal fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("wallflower", text))
}

/** A choice among a few: on when [selected]. */
@Composable
internal fun Chip(label: String, selected: Boolean, font: FontFamily = Mono, onClick: () -> Unit) {
    val haptics = haptics()
    Pill(
        AnnotatedString(label),
        on = selected,
        font = font,
        input = Modifier.clickable {
            if (!selected) haptics.tick()
            onClick()
        },
    )
}

/** A key to pick or add, as its [face]: on when [selected]; held, it says its [name]. */
@Composable
internal fun KeyChoice(face: AnnotatedString, name: String, selected: Boolean, onClick: () -> Unit) {
    val haptics = haptics()
    var held by remember { mutableStateOf(false) }
    Box {
        Pill(
            face,
            on = selected,
            input = Modifier
                .onLetGo { held = false }
                .combinedClickable(
                    hapticFeedbackEnabled = false, // its own feel, which heeds the setting
                    onLongClick = {
                        haptics.hold()
                        held = true
                    },
                ) {
                    if (!selected) haptics.tick()
                    onClick()
                },
        )
        if (held) HeldName(name)
    }
}
