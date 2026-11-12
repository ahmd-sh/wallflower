package sh.ahmd.wallflower.ui.mobile

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import sh.ahmd.wallflower.program.Aside
import sh.ahmd.wallflower.ui.Mono
import sh.ahmd.wallflower.ui.Motion
import sh.ahmd.wallflower.ui.Palette
import sh.ahmd.wallflower.ui.Type
import sh.ahmd.wallflower.ui.haptics
import sh.ahmd.wallflower.ui.wordSize
import sh.ahmd.wallflower.ui.words

/**
 * What the program shows aside of its line, whose keys are its own: a view
 * of its own, said so, with a way back; or an answer to a question asked
 * aside, as far as it's been read ([answer]), with esc to close it, and a way
 * down it while there may be more. Something sent while it's up waits: the
 * card says so a moment ([nudged]).
 */
@Composable
internal fun AsideCard(aside: Aside, answer: List<String>, nudged: Long, onKeys: (List<String>) -> Unit, modifier: Modifier = Modifier) {
    val haptics = haptics()
    // Something sent while it's up, not before it came.
    val before = remember { nudged }
    val held by produceState(false, nudged) {
        if (nudged != 0L && nudged != before) {
            value = true
            delay(HELD_MS)
            value = false
        }
    }
    val leave = {
        haptics.tick()
        onKeys(aside.leave)
    }
    when (aside) {
        is Aside.View -> Row(
            modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.bg1).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Palette.blue))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text("the terminal shows the ${aside.name}", color = Palette.fg, style = Type.label)
                Crossfade(held, animationSpec = Motion.effect(), label = "held") {
                    if (it) {
                        Text("go back first, then send", color = Palette.orange, style = Type.caption)
                    } else {
                        Text("what's typed goes to it till you're back", color = Palette.grey, style = Type.caption)
                    }
                }
            }
            Chip("back", Palette.blue, onClick = leave)
        }
        is Aside.Tasks -> Unit // a page of its own (TasksPage)
        is Aside.Answer -> Box(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Palette.bg1).padding(10.dp)) {
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.padding(end = 44.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("by the way", color = Palette.purple, style = Type.caption)
                    Text(aside.question, color = Palette.grey, style = Type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
                }
                if (held) Text("close this first (esc), then send", color = Palette.orange, fontFamily = words, fontSize = wordSize(12.sp, 13.sp))
                val rows = aside.rows
                if (rows == null) {
                    Working(0, "answering")
                } else {
                    val text = remember(answer, aside.width) { aside.text(answer) }
                    SelectionContainer {
                        Text(text, color = Palette.fg, fontFamily = words, fontSize = wordSize(14.sp, 15.sp), lineHeight = wordSize(21.sp, 22.sp), modifier = Modifier.animateContentSize(Motion.spatial()))
                    }
                    // Taller than its room, down a page at a time, but for a row or two, so what shows next meets what's been read; till a page brings nothing.
                    var ended by remember(aside.question) { mutableStateOf(false) }
                    var asked by remember(aside.question) { mutableStateOf<Int?>(null) }
                    LaunchedEffect(asked, answer.size) {
                        val at = asked ?: return@LaunchedEffect
                        if (answer.size <= at) {
                            delay(MORE_MS)
                            ended = true
                        }
                        asked = null
                    }
                    if (aside.taller && !ended) {
                        Chip("more ↓", Palette.blue) {
                            haptics.tick()
                            asked = answer.size
                            onKeys(List(maxOf(1, (rows.size - 2) / aside.step)) { aside.down }.flatten())
                        }
                    }
                }
            }
            Chip("esc", Palette.grey, Modifier.align(Alignment.TopEnd), mono = true, onClick = leave)
        }
    }
}

/** A small rounded button, as the cards' esc. */
@Composable
private fun Chip(text: String, color: Color, modifier: Modifier = Modifier, mono: Boolean = false, onClick: () -> Unit) = Text(
    text,
    color = color,
    fontFamily = if (mono) Mono else words,
    fontSize = 12.sp,
    modifier = modifier
        .clip(RoundedCornerShape(14.dp))
        .background(Palette.bg2)
        .clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 3.dp),
)

/** How long something sent while an aside is up says it's waiting. */
private const val HELD_MS = 3_000L

/** How long after asking for more of an answer nothing new means there's no more. */
private const val MORE_MS = 1_200L
