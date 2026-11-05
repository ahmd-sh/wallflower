package sh.ahmd.wallflower.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A line of a unified diff, as shown. */
internal data class DiffLine(val kind: Kind, val text: String) {
    enum class Kind { Hunk, Context, Added, Removed, Note }
}

private val hunk = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

/** A diff's hunks as lines: each hunk's header says where it is, in the file as it is now. */
internal fun diffLines(diff: String): List<DiffLine> = diff.lines().map { line ->
    val h = hunk.find(line)
    when {
        h != null -> DiffLine(DiffLine.Kind.Hunk, "line " + h.groupValues[2].toInt().coerceAtLeast(1))
        line.startsWith("+") -> DiffLine(DiffLine.Kind.Added, line.drop(1))
        line.startsWith("-") -> DiffLine(DiffLine.Kind.Removed, line.drop(1))
        line.startsWith(" ") -> DiffLine(DiffLine.Kind.Context, line.drop(1))
        else -> DiffLine(DiffLine.Kind.Note, line) // "\ No newline at end of file", or how much was cut
    }
}

/** How many lines a diff adds and removes. */
internal fun diffCounts(lines: List<DiffLine>): Pair<Int, Int> =
    lines.count { it.kind == DiffLine.Kind.Added } to lines.count { it.kind == DiffLine.Kind.Removed }

/** A diff's [lines], unwrapped and scrolling sideways together, changed ones tinted edge to edge. */
@Composable
internal fun DiffView(lines: List<DiffLine>, modifier: Modifier = Modifier) {
    val colors = Palette
    val text = remember(lines, colors) {
        buildAnnotatedString {
            lines.forEachIndexed { i, line ->
                if (i > 0) append('\n')
                when (line.kind) {
                    DiffLine.Kind.Added -> {
                        withStyle(SpanStyle(color = colors.green)) { append("+ ") }
                        append(line.text)
                    }
                    DiffLine.Kind.Removed -> {
                        withStyle(SpanStyle(color = colors.red)) { append("- ") }
                        append(line.text)
                    }
                    DiffLine.Kind.Context -> withStyle(SpanStyle(color = colors.grey)) { append("  " + line.text) }
                    DiffLine.Kind.Hunk, DiffLine.Kind.Note -> withStyle(SpanStyle(color = colors.dim)) { append(line.text) }
                }
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Scrolling sideways, it's laid out at least as wide as it shows, so tints reach the edge.
    Text(
        text,
        style = TextStyle(color = Palette.fg, fontFamily = Mono, fontSize = 11.sp, lineHeight = 15.sp),
        softWrap = false,
        onTextLayout = { layout = it },
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.bg1)
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
            .drawBehind {
                val l = layout ?: return@drawBehind
                lines.forEachIndexed { i, line ->
                    val tint = when (line.kind) {
                        DiffLine.Kind.Added -> colors.bgGreen
                        DiffLine.Kind.Removed -> colors.bgRed
                        else -> return@forEachIndexed
                    }
                    if (i < l.lineCount) drawRect(tint, Offset(0f, l.getLineTop(i)), Size(size.width, l.getLineBottom(i) - l.getLineTop(i)))
                }
            }
            .padding(horizontal = 10.dp),
    )
}
