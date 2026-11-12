package sh.ahmd.wallflower.ui.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.ahmd.wallflower.read.Entry
import sh.ahmd.wallflower.read.trace
import sh.ahmd.wallflower.ui.DiffLine
import sh.ahmd.wallflower.ui.DiffView
import sh.ahmd.wallflower.ui.Mono
import sh.ahmd.wallflower.ui.Motion
import sh.ahmd.wallflower.ui.Palette
import sh.ahmd.wallflower.ui.Type
import sh.ahmd.wallflower.ui.diffCounts
import sh.ahmd.wallflower.ui.diffLines

/** A run of tool calls and thinking, as one line saying what ran; open, a line per step. */
@Composable
internal fun Steps(steps: List<Entry>) {
    var open by remember { mutableStateOf(false) }
    val feel = rememberFold()
    val tools = steps.filterIsInstance<Entry.Step>()
    val running = tools.lastOrNull()?.takeIf { it.status == Entry.Step.Status.Running }
    val failed = tools.count { it.status == Entry.Step.Status.Failed }
    val names = tools.map { it.name }.distinct()
    val red = Palette.red
    val summary = buildAnnotatedString {
        append(if (steps.size == 1) "1 step" else "${steps.size} steps")
        if (names.isNotEmpty()) append(" · " + names.take(3).joinToString(", ") + if (names.size > 3) " +${names.size - 3}" else "")
        if (failed > 0) {
            append(" · ")
            withStyle(SpanStyle(color = red)) { append("$failed failed") }
        }
    }
    Column {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    feel.tap()
                    if (open) feel.leave()
                    open = !open
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val turn by animateFloatAsState(if (open) 90f else 0f, Motion.spatial(), label = "chevron")
            Text("▸", color = Palette.grey, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.graphicsLayer { rotationZ = turn })
            if (running != null) Running(Modifier.padding(start = 8.dp))
            Text(
                summary,
                color = Palette.grey,
                style = Type.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        AnimatedVisibility(
            open,
            enter = expandVertically(Motion.spatial()) + fadeIn(Motion.effect()),
            exit = shrinkVertically(Motion.spatial()) + fadeOut(Motion.effect()),
        ) {
            Column(
                Modifier.onSizeChanged { feel.laidOut(it.height) }.padding(start = 6.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (s in steps) Step(s)
                CopyIcon { trace(steps) }
            }
        }
    }
}

/** A dot that breathes while a tool runs. */
@Composable
internal fun Running(modifier: Modifier) {
    val breath = if (LocalLive.current) {
        rememberInfiniteTransition(label = "running").animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "breath")
    } else {
        null
    }
    Box(modifier.size(6.dp).graphicsLayer { alpha = breath?.value ?: 1f }.clip(CircleShape).background(Palette.purple))
}

@Composable
private fun Step(s: Entry) {
    val step = s as? Entry.Step
    val name = step?.name ?: "thinking"
    val input = step?.input ?: (s as? Entry.Thought)?.text.orEmpty()
    val output = step?.output.orEmpty()
    val diff = step?.diff.orEmpty()
    val diffCut = step?.diffCut ?: 0
    val diffLines = remember(diff, diffCut) {
        if (diff.isEmpty()) emptyList() else diffLines(diff) + listOfNotNull(more(diffCut, "line")?.let { DiffLine(DiffLine.Kind.Note, it) })
    }
    val outputCut = step?.outputCut ?: 0
    val failed = step?.status == Entry.Step.Status.Failed
    var open by remember { mutableStateOf(false) }
    val feel = rememberFold()
    val mono = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp)
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(Motion.spatial())
            .onSizeChanged { feel.laidOut(it.height) } // the size it's going to
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                feel.tap()
                open = !open
            }
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = mono.copy(
                    color = when {
                        failed -> Palette.red
                        step?.status == Entry.Step.Status.Running -> Palette.purple
                        else -> Palette.aqua
                    },
                    fontStyle = if (step == null) FontStyle.Italic else FontStyle.Normal,
                ),
            )
            if (!open) {
                Text(
                    input.lineSequence().firstOrNull().orEmpty(),
                    style = mono.copy(color = Palette.grey),
                    maxLines = 1,
                    // A changed file's name is at its end.
                    overflow = if (diff.isNotEmpty()) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (diffLines.isNotEmpty()) {
                val (added, removed) = remember(diffLines) { diffCounts(diffLines) }
                Text(
                    buildAnnotatedString {
                        if (added > 0) withStyle(SpanStyle(color = Palette.green)) { append("+$added") }
                        if (added > 0 && removed > 0) append(" ")
                        if (removed > 0) withStyle(SpanStyle(color = Palette.red)) { append("−$removed") }
                    },
                    style = mono.copy(fontSize = 11.sp),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (open) {
            if (input.isNotEmpty()) Text(input, style = mono.copy(color = Palette.fg), modifier = Modifier.padding(top = 4.dp))
            if (diffLines.isNotEmpty() && !failed) {
                DiffView(diffLines, Modifier.padding(top = 6.dp))
            } else if (output.isNotEmpty()) {
                Text(
                    output + more(outputCut, "character")?.let { "\n$it" }.orEmpty(),
                    style = mono.copy(color = if (failed) Palette.red else Palette.grey, fontSize = 11.sp, lineHeight = 15.sp),
                    softWrap = false,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Palette.bg1)
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                )
            }
        }
    }
}
