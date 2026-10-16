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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Glyph { Plus, Close, Power }

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
        title = { Text(title, color = Palette.fg, fontFamily = Mono, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(body, color = Palette.grey, fontFamily = Mono, fontSize = 13.sp)
                Row(
                    Modifier.padding(top = 12.dp).clip(RoundedCornerShape(8.dp)).clickable { never = !never }.padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = never,
                        onCheckedChange = { never = it },
                        colors = CheckboxDefaults.colors(checkedColor = Palette.orange, uncheckedColor = Palette.grey, checkmarkColor = Palette.bg0),
                    )
                    Text("don't ask again", color = Palette.fg, fontFamily = Mono, fontSize = 13.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(never) }) { Text(action, color = Palette.red, fontFamily = Mono) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("cancel", color = Palette.fg, fontFamily = Mono) } },
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
        title = { Text("rename tab", color = Palette.fg, fontFamily = Mono, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            BasicTextField(
                state = state,
                lineLimits = TextFieldLineLimits.SingleLine,
                textStyle = TextStyle(color = Palette.fg, fontFamily = Mono, fontSize = 15.sp),
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
        confirmButton = { TextButton(onClick = { rename() }) { Text("rename", color = Palette.green, fontFamily = Mono) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("cancel", color = Palette.fg, fontFamily = Mono) } },
    )
}
