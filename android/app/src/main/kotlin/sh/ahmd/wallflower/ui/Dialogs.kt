package sh.ahmd.wallflower.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties

enum class Glyph { Plus, Close, Power, Chat, Terminal, Spark, Copy, Check }

/** Line icons, drawn to match the send button rather than pulled from an icon set. */
@Composable
fun GlyphIcon(glyph: Glyph, color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier.size(size)) {
        val w = 1.8.dp.toPx()
        val c = center
        val r = this.size.minDimension / 2 - w
        when (glyph) {
            Glyph.Plus -> {
                drawLine(color, Offset(c.x - r, c.y), Offset(c.x + r, c.y), w, StrokeCap.Round)
                drawLine(color, Offset(c.x, c.y - r), Offset(c.x, c.y + r), w, StrokeCap.Round)
            }
            Glyph.Close -> {
                val d = r * 0.75f
                drawLine(color, Offset(c.x - d, c.y - d), Offset(c.x + d, c.y + d), w, StrokeCap.Round)
                drawLine(color, Offset(c.x + d, c.y - d), Offset(c.x - d, c.y + d), w, StrokeCap.Round)
            }
            Glyph.Power -> {
                val ring = r * 0.85f
                drawArc(
                    color, startAngle = -50f, sweepAngle = 280f, useCenter = false,
                    topLeft = Offset(c.x - ring, c.y - ring + r * 0.1f), size = Size(ring * 2, ring * 2),
                    style = Stroke(w, cap = StrokeCap.Round),
                )
                drawLine(color, Offset(c.x, c.y - r), Offset(c.x, c.y), w, StrokeCap.Round)
            }
            Glyph.Chat -> {
                val top = c.y - r * 0.8f
                val bottom = c.y + r * 0.45f
                drawRoundRect(
                    color, topLeft = Offset(c.x - r, top), size = Size(r * 2, bottom - top),
                    cornerRadius = CornerRadius(r * 0.45f), style = Stroke(w),
                )
                drawLine(color, Offset(c.x - r * 0.35f, bottom), Offset(c.x - r * 0.6f, c.y + r), w, StrokeCap.Round)
                drawLine(color, Offset(c.x - r * 0.6f, c.y + r), Offset(c.x + r * 0.05f, bottom), w, StrokeCap.Round)
            }
            Glyph.Terminal -> {
                drawLine(color, Offset(c.x - r, c.y - r * 0.6f), Offset(c.x - r * 0.35f, c.y), w, StrokeCap.Round)
                drawLine(color, Offset(c.x - r * 0.35f, c.y), Offset(c.x - r, c.y + r * 0.6f), w, StrokeCap.Round)
                drawLine(color, Offset(c.x + r * 0.05f, c.y + r * 0.7f), Offset(c.x + r, c.y + r * 0.7f), w, StrokeCap.Round)
            }
            Glyph.Copy -> {
                // A sheet, and the edges of one behind it.
                val side = r * 1.4f
                val k = r * 0.3f
                drawRoundRect(color, topLeft = Offset(c.x + r - side, c.y + r - side), size = Size(side, side), cornerRadius = CornerRadius(k), style = Stroke(w))
                drawPath(
                    Path().apply {
                        moveTo(c.x - r, c.y - r + side - k)
                        lineTo(c.x - r, c.y - r + k)
                        quadraticTo(c.x - r, c.y - r, c.x - r + k, c.y - r)
                        lineTo(c.x - r + side - k, c.y - r)
                    },
                    color,
                    style = Stroke(w, cap = StrokeCap.Round),
                )
            }
            Glyph.Check -> {
                drawLine(color, Offset(c.x - r, c.y + r * 0.05f), Offset(c.x - r * 0.3f, c.y + r * 0.7f), w, StrokeCap.Round)
                drawLine(color, Offset(c.x - r * 0.3f, c.y + r * 0.7f), Offset(c.x + r, c.y - r * 0.6f), w, StrokeCap.Round)
            }
            Glyph.Spark -> drawPath(
                Path().apply {
                    moveTo(c.x, c.y - r)
                    quadraticTo(c.x, c.y, c.x + r, c.y)
                    quadraticTo(c.x, c.y, c.x, c.y + r)
                    quadraticTo(c.x, c.y, c.x - r, c.y)
                    quadraticTo(c.x, c.y, c.x, c.y - r)
                    close()
                },
                color,
            )
        }
    }
}

/** A menu's choice: what it says, in what color, and what it does. */
class MenuAction(val label: String, val color: Color = Palette.fg, val onClick: () -> Unit)

/**
 * A menu dropping from what opened it, rounded and compact, its choices
 * ruled apart beneath an optional [header]. Felt as it opens and shuts.
 */
@Composable
fun PopupMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: List<MenuAction>,
    header: (@Composable () -> Unit)? = null,
    /** Leave the keyboard up while it's open, rather than taking focus. */
    keepKeyboard: Boolean = false,
) {
    val haptics = haptics()
    var felt by remember { mutableStateOf(expanded) }
    LaunchedEffect(expanded) {
        if (expanded != felt) haptics.slide(if (expanded) MENU_IN_MS else MENU_OUT_MS)
        felt = expanded
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = Palette.bg1,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, Palette.border),
        properties = PopupProperties(focusable = !keepKeyboard),
    ) {
        Column(Modifier.widthIn(min = 150.dp).padding(horizontal = 4.dp)) {
            header?.let {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { it() }
                MenuRule()
            }
            actions.forEachIndexed { i, action ->
                if (i > 0) MenuRule()
                Text(
                    action.label,
                    color = action.color,
                    fontFamily = words,
                    fontSize = wordSize(13.sp, 15.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            haptics.tick()
                            action.onClick()
                        }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                )
            }
        }
    }
}

@Composable
private fun MenuRule() = Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.dp).background(Palette.bg2))

/** How long a menu takes to open and to shut (Material's). */
private const val MENU_IN_MS = 120L
private const val MENU_OUT_MS = 75L

/** Asks before closing something. "Don't ask again" comes back with the answer. */
@Composable
fun ConfirmClose(title: String, body: String, action: String, onConfirm: (dontAskAgain: Boolean) -> Unit, onDismiss: () -> Unit) {
    var never by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.bg1,
        title = { DialogTitle(title) },
        text = {
            Column {
                Text(body, color = Palette.grey, fontFamily = words, fontSize = wordSize(13.sp, 15.sp), lineHeight = wordSize(TextUnit.Unspecified, 21.sp))
                Row(
                    Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).clickable { never = !never }.padding(vertical = 6.dp).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = never,
                        onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = Palette.orange, uncheckedColor = Palette.grey, checkmarkColor = Palette.bg0),
                    )
                    Text("don't ask again", color = Palette.fg, fontFamily = words, fontSize = wordSize(13.sp, 15.sp), modifier = Modifier.padding(start = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(never) }) { Text(action, color = Palette.red, fontFamily = words) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("cancel", color = Palette.fg, fontFamily = words) } },
    )
}

@Composable
fun RenameDialog(name: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTextFieldState(name, initialSelection = TextRange(0, name.length))
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val rename = { state.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onRename) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.bg1,
        title = { DialogTitle("rename tab") },
        text = {
            BasicTextField(
                state = state,
                lineLimits = TextFieldLineLimits.SingleLine,
                textStyle = TextStyle(color = Palette.fg, fontFamily = words, fontSize = wordSize(15.sp, 16.sp)),
                cursorBrush = SolidColor(Palette.orange),
                onKeyboardAction = { rename() },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.bg2)
                    .padding(12.dp),
            )
        },
        confirmButton = { TextButton(onClick = { rename() }) { Text("rename", color = Palette.green, fontFamily = words) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("cancel", color = Palette.fg, fontFamily = words) } },
    )
}

@Composable
private fun DialogTitle(text: String) =
    Text(text, color = Palette.fg, fontFamily = words, fontSize = wordSize(16.sp, 20.sp), fontWeight = if (grotesque) FontWeight.SemiBold else FontWeight.Bold)
