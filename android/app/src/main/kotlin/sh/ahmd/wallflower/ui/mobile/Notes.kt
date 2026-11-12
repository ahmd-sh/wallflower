package sh.ahmd.wallflower.ui.mobile

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.ahmd.wallflower.read.Entry
import sh.ahmd.wallflower.ui.Glyph
import sh.ahmd.wallflower.ui.GlyphIcon
import sh.ahmd.wallflower.ui.MarkdownText
import sh.ahmd.wallflower.ui.Mono
import sh.ahmd.wallflower.ui.Motion
import sh.ahmd.wallflower.ui.Palette
import sh.ahmd.wallflower.ui.Type

/** Something that happened, small and centered; tapped, it opens, with its [detail] if it has one (a compaction's summary). */
@Composable
internal fun Note(text: String, detail: String = "") {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().animateContentSize(Motion.spatial()).clickable { open = !open }.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (detail.isEmpty() || open) text else "$text · see more",
            color = Palette.dim,
            style = Type.caption,
            textAlign = TextAlign.Center,
            maxLines = if (open || detail.isNotEmpty()) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (open && detail.isNotEmpty()) {
            Text(
                detail,
                color = Palette.grey,
                style = Type.caption,
                modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Palette.bg1).padding(12.dp),
            )
        }
    }
}

/**
 * A message another agent sent the program, on the program's side: who, and
 * what it's about; tapped, the message itself opens beneath.
 */
@Composable
internal fun AgentMessage(e: Entry.Agent) {
    var open by remember { mutableStateOf(false) }
    val feel = rememberFold()
    val opens = e.message.isNotEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(end = 24.dp)
            .animateContentSize(Motion.spatial())
            .onSizeChanged { feel.laidOut(it.height) }
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.bg1)
            .clickable(enabled = opens) {
                feel.tap()
                open = !open
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.Spark, Palette.purple, size = 11.dp)
            Text(e.name, color = Palette.purple, style = Type.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false))
            if (opens) Text(if (open) "  hide" else "  see all", color = Palette.dim, style = Type.caption)
        }
        if (!open || !opens) {
            Text(e.about, color = Palette.fg, style = Type.label, maxLines = 3, overflow = TextOverflow.Ellipsis)
        } else {
            SelectionContainer { MarkdownText(e.message + (more(e.cut, "character")?.let { "\n\n$it" } ?: "")) }
        }
    }
}

/** A command the user ran, on their side and marked as one, with what it said back beneath; tapped, that opens if it's long. */
@Composable
internal fun Command(c: Entry.Command) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            listOf(c.name, c.args).filter { it.isNotEmpty() }.joinToString(" "),
            color = Palette.purple,
            fontFamily = Mono,
            fontSize = 13.sp,
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Palette.bg2).padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (c.output.isNotEmpty()) {
            Text(
                c.output + (more(c.outputCut, "character")?.let { "\n$it" } ?: ""),
                color = Palette.grey,
                fontFamily = Mono,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = if (open) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.animateContentSize(Motion.spatial()).clickable { open = !open }.padding(horizontal = 4.dp),
            )
        }
    }
}
