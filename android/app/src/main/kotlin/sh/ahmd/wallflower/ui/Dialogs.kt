package sh.ahmd.wallflower.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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

enum class Glyph { Plus, Close, Power, Chat, Terminal, Spark }

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
