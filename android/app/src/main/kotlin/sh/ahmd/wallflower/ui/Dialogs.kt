package sh.ahmd.wallflower.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** wallflower's icons; from [Escape] on, keys'; from [Hexagon] on, programs' (with [Terminal], [Spark] and [Edit]: see [glyphOf]). */
enum class Glyph {
    Plus, Close, Chat, Terminal, Spark, Copy, Check, Edit,
    Tab, BackTab, Enter, Up, Down, Left, Right, Backspace, Space, Ctrl, Shift, Arrows, Bolt,
    Hexagon, Snake, Branch, Pulse, Globe, Boxes, Folder, Database, Book, Hammer,
}

/** Line icons, drawn to match the send button rather than pulled from an icon set. */
@Composable
fun GlyphIcon(glyph: Glyph, color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier.size(size)) {
        val w = 1.8.dp.toPx()
        val c = center
        val r = this.size.minDimension / 2 - w
        val line = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun at(x: Float, y: Float) = Offset(c.x + x * r, c.y + y * r)
        fun stroke(vararg points: Pair<Float, Float>, closed: Boolean = false) = drawPath(
            Path().apply {
                points.forEachIndexed { i, (x, y) ->
                    val p = at(x, y)
                    if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                if (closed) close()
            },
            color,
            style = line,
        )
        fun curve(vararg points: Pair<Float, Float>) = drawPath(
            Path().apply {
                val p = points.map { (x, y) -> at(x, y) }
                moveTo(p[0].x, p[0].y)
                for (i in 1 until p.size step 3) cubicTo(p[i].x, p[i].y, p[i + 1].x, p[i + 1].y, p[i + 2].x, p[i + 2].y)
            },
            color,
            style = line,
        )
        fun up() {
            stroke(0f to 1f, 0f to -1f)
            stroke(-0.65f to -0.35f, 0f to -1f, 0.65f to -0.35f)
        }
        fun tab() {
            stroke(-1f to 0f, 0.6f to 0f)
            stroke(0.05f to -0.55f, 0.6f to 0f, 0.05f to 0.55f)
            stroke(1f to -0.75f, 1f to 0.75f)
        }
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
            Glyph.Edit -> rotate(-45f, c) {
                stroke(-1.2f to 0f, -0.7f to -0.32f, 1.1f to -0.32f, 1.1f to 0.32f, -0.7f to 0.32f, closed = true)
                stroke(0.6f to -0.32f, 0.6f to 0.32f)
            }
            Glyph.Tab -> tab()
            Glyph.BackTab -> scale(-1f, 1f, c) { tab() }
            Glyph.Enter -> {
                stroke(0.8f to -0.9f, 0.8f to 0.25f, -0.85f to 0.25f)
                stroke(-0.3f to -0.3f, -0.85f to 0.25f, -0.3f to 0.8f)
            }
            Glyph.Up -> up()
            Glyph.Down -> rotate(180f, c) { up() }
            Glyph.Left -> rotate(-90f, c) { up() }
            Glyph.Right -> rotate(90f, c) { up() }
            Glyph.Arrows -> {
                // Arrow keys as a keyboard has them, one over three.
                val k = this.size.width * 0.28f
                val step = (this.size.width - k) / 2
                val top = c.y - (k + step) / 2
                for ((x, y) in listOf(0 to 0, -1 to 1, 0 to 1, 1 to 1)) {
                    drawRoundRect(color, Offset(c.x - k / 2 + x * step, top + y * step), Size(k, k), CornerRadius(k * 0.25f))
                }
            }
            Glyph.Backspace -> {
                stroke(-1f to 0f, -0.45f to -0.75f, 1f to -0.75f, 1f to 0.75f, -0.45f to 0.75f, closed = true)
                stroke(-0.02f to -0.3f, 0.58f to 0.3f)
                stroke(0.58f to -0.3f, -0.02f to 0.3f)
            }
            Glyph.Space -> stroke(-1f to -0.15f, -1f to 0.5f, 1f to 0.5f, 1f to -0.15f)
            Glyph.Ctrl -> stroke(-0.7f to 0.35f, 0f to -0.4f, 0.7f to 0.35f)
            Glyph.Bolt -> stroke(0.3f to -1f, -0.6f to 0.15f, 0.05f to 0.15f, -0.3f to 1f, 0.6f to -0.15f, -0.05f to -0.15f, closed = true)
            Glyph.Shift -> stroke(0f to -1f, 0.9f to -0.05f, 0.42f to -0.05f, 0.42f to 0.85f, -0.42f to 0.85f, -0.42f to -0.05f, -0.9f to -0.05f, closed = true)
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
            Glyph.Hexagon -> stroke(0f to -1f, 0.87f to -0.5f, 0.87f to 0.5f, 0f to 1f, -0.87f to 0.5f, -0.87f to -0.5f, closed = true)
            Glyph.Snake -> {
                curve(0.45f to -0.7f, -0.1f to -1f, -0.85f to -0.7f, -0.6f to -0.2f, -0.4f to 0.2f, 0.65f to 0f, 0.6f to 0.5f, 0.55f to 1f, -0.4f to 1f, -0.9f to 0.75f)
                drawCircle(color, r * 0.32f, at(0.55f, -0.62f))
            }
            Glyph.Branch -> {
                stroke(-0.67f to -1f, -0.67f to 0.33f)
                drawCircle(color, r * 0.33f, at(0.67f, -0.67f), style = line)
                drawCircle(color, r * 0.33f, at(-0.67f, 0.67f), style = line)
                drawArc(color, 0f, 90f, useCenter = false, topLeft = at(-1.33f, -1.33f), size = Size(r * 2, r * 2), style = line)
            }
            Glyph.Pulse -> stroke(-1f to 0f, -0.55f to 0f, -0.25f to -0.8f, 0.25f to 0.8f, 0.55f to 0f, 1f to 0f)
            Glyph.Globe -> {
                drawCircle(color, r, c, style = line)
                drawOval(color, topLeft = at(-0.42f, -1f), size = Size(r * 0.84f, r * 2), style = line)
                stroke(-1f to 0f, 1f to 0f)
            }
            Glyph.Boxes -> {
                drawRoundRect(color, topLeft = at(-1f, 0f), size = Size(r * 2, r * 0.85f), cornerRadius = CornerRadius(r * 0.15f), style = line)
                drawRoundRect(color, topLeft = at(-0.5f, -0.85f), size = Size(r, r * 0.85f), cornerRadius = CornerRadius(r * 0.15f), style = line)
                stroke(0f to 0f, 0f to 0.85f)
            }
            Glyph.Folder -> stroke(-1f to 0.75f, -1f to -0.75f, -0.3f to -0.75f, -0.1f to -0.45f, 1f to -0.45f, 1f to 0.75f, closed = true)
            Glyph.Database -> {
                val ring = Size(r * 1.7f, r * 0.6f)
                drawOval(color, topLeft = at(-0.85f, -0.92f), size = ring, style = line)
                drawArc(color, 0f, 180f, useCenter = false, topLeft = at(-0.85f, -0.3f), size = ring, style = line)
                drawArc(color, 0f, 180f, useCenter = false, topLeft = at(-0.85f, 0.32f), size = ring, style = line)
                stroke(-0.85f to -0.62f, -0.85f to 0.62f)
                stroke(0.85f to -0.62f, 0.85f to 0.62f)
            }
            Glyph.Book -> {
                stroke(0f to -0.55f, -1f to -0.85f, -1f to 0.6f, 0f to 0.85f, closed = true)
                stroke(0f to -0.55f, 1f to -0.85f, 1f to 0.6f, 0f to 0.85f, closed = true)
            }
            Glyph.Hammer -> rotate(45f, c) {
                stroke(0f to 1.1f, 0f to -0.25f)
                stroke(-0.6f to -0.85f, 0.6f to -0.85f, 0.6f to -0.25f, -0.6f to -0.25f, closed = true)
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
        title = { DialogTitle(title) },
        text = {
            Column {
                Text(body, color = Palette.grey, style = Type.body, lineHeight = wordSize(TextUnit.Unspecified, 21.sp))
                Row(
                    Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).clickable { never = !never }.padding(vertical = 6.dp).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = never,
                        onCheckedChange = null,
                        colors = checkboxColors(),
                    )
                    Text("don't ask again", color = Palette.fg, style = Type.body, modifier = Modifier.padding(start = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(never) }) { Text(action, color = Palette.red, fontFamily = words) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("cancel", color = Palette.fg, fontFamily = words) } },
    )
}

/** Asks for a name: a new one for a tab, say. With [blank], none is an answer too. */
@Composable
fun RenameDialog(
    name: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "rename tab",
    action: String = "rename",
    blank: Boolean = false,
) {
    val state = rememberTextFieldState(name, initialSelection = TextRange(0, name.length))
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val rename = { state.text.toString().trim().takeIf { blank || it.isNotEmpty() }?.let(onRename) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.bg1,
        title = { DialogTitle(title) },
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
        confirmButton = { TextButton(onClick = { rename() }) { Text(action, color = Palette.green, fontFamily = words) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("cancel", color = Palette.fg, fontFamily = words) } },
    )
}

/** Asks before something that can't be undone. */
@Composable
fun Confirm(title: String, body: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.bg1,
        title = { DialogTitle(title) },
        text = { Text(body, color = Palette.grey, style = Type.body, lineHeight = wordSize(TextUnit.Unspecified, 21.sp)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action, color = Palette.red, fontFamily = words) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("cancel", color = Palette.fg, fontFamily = words) } },
    )
}

@Composable
internal fun DialogTitle(text: String) =
    Text(text, color = Palette.fg, style = Type.title, fontWeight = if (grotesque) FontWeight.SemiBold else FontWeight.Bold)
