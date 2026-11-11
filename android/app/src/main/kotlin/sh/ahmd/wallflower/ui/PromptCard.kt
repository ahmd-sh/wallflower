package sh.ahmd.wallflower.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.ahmd.wallflower.screen.Prompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay

/**
 * Choices the program's screen offers, to answer by touch: what it's about,
 * its questions as tabs, its options. Picking one sends [onKeys] the keys
 * that pick it on the screen, so the program, and the computer, see the same
 * answer as if typed, and [onPick] the option. The card goes when the screen
 * moves on.
 */
@Composable
fun PromptCard(prompt: Prompt, onKeys: (List<String>) -> Unit, modifier: Modifier = Modifier, nudged: Long = 0, onPick: (Prompt.Option) -> Unit = {}) {
    val haptics = haptics()
    // Something sent while it's open waits: it says so a moment.
    val held by produceState(false, nudged) {
        if (nudged != 0L) {
            value = true
            delay(HELD_MS)
            value = false
        }
    }
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Palette.bg1).padding(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // The first row leaves the corner to esc.
            if (prompt.tabs.isNotEmpty()) {
                Row(
                    Modifier.padding(end = ESC_ROOM).clip(RoundedCornerShape(14.dp)).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (tab in prompt.tabs) {
                        Text(
                            tab.label,
                            color = if (tab.current) Palette.fg else Palette.grey,
                            fontFamily = words,
                            fontSize = wordSize(12.sp, 13.sp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (tab.current) Palette.bg3 else Palette.bg2)
                                .clickable(enabled = prompt.tabs.size > 1) {
                                    haptics.tick()
                                    onKeys(prompt.keys(tab))
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            if (prompt.lines.isNotEmpty()) About(prompt.lines, if (prompt.tabs.isEmpty()) ESC_ROOM else 0.dp)
            if (prompt.tabs.isEmpty() && prompt.lines.isEmpty()) Spacer(Modifier.height(18.dp))
            if (held) Text("answer this first (or esc), then send", color = Palette.orange, fontFamily = words, fontSize = wordSize(12.sp, 13.sp), modifier = Modifier.padding(horizontal = 4.dp))
            Column {
                prompt.options.forEachIndexed { i, option ->
                    if (i > 0) Rule(Modifier.padding(horizontal = 10.dp))
                    Choice(option) {
                        if (option.checked != null) haptics.toggle(option.checked != true) else haptics.confirm()
                        onKeys(prompt.keys(option))
                        onPick(option)
                    }
                }
            }
        }
        Text(
            "esc",
            color = Palette.grey,
            fontFamily = Mono,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.bg2)
                .clickable {
                    haptics.tick()
                    onKeys(listOf("Escape"))
                }
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

private val ESC_ROOM = 44.dp

private const val HELD_MS = 3_000L

/** What the menu is about: the first and last lines (a title, the question) stand out, boxed rows are code. */
@Composable
private fun About(lines: List<Prompt.Line>, firstEnd: Dp) {
    val prose = lines.withIndex().filter { !it.value.code }.map { it.index }
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var i = 0
        while (i < lines.size) {
            val end = if (i == 0) firstEnd else 0.dp
            if (lines[i].code) {
                val start = i
                while (i < lines.size && lines[i].code) i++
                Text(
                    lines.subList(start, i).joinToString("\n") { it.text },
                    color = Palette.fg,
                    fontFamily = Mono,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    softWrap = false,
                    modifier = Modifier
                        .padding(end = end)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Palette.bg0)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
                continue
            }
            val stands = i == prose.first() || i == prose.last()
            Text(
                lines[i].text,
                color = if (stands) Palette.fg else Palette.grey,
                fontFamily = words,
                fontSize = if (stands) wordSize(13.sp, 15.sp) else wordSize(12.sp, 13.sp),
                fontWeight = if (stands) (if (grotesque) FontWeight.SemiBold else FontWeight.Bold) else FontWeight.Normal,
                lineHeight = wordSize(17.sp, 19.sp),
                modifier = Modifier.padding(end = end),
            )
            i++
        }
    }
}

/** An option: its number (the key that picks it) or box, its label, and what it means beneath. The cursor's one is lit. */
@Composable
private fun Choice(option: Prompt.Option, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (option.current) Palette.bg2 else Palette.bg1)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val checked = option.checked
        when {
            checked != null -> Checkbox(
                checked,
                onCheckedChange = null,
                colors = checkboxColors(),
            )
            // As wide as a box, so labels line up whichever a menu mixes.
            else -> Text(
                option.number?.toString() ?: "›",
                color = Palette.dim,
                fontFamily = Mono,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(24.dp),
            )
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                option.label,
                color = if (option.muted) Palette.grey else Palette.fg,
                fontFamily = words,
                fontSize = wordSize(13.sp, 14.sp),
                lineHeight = wordSize(17.sp, 18.sp),
            )
            if (option.detail.isNotEmpty()) {
                Text(option.detail, color = Palette.grey, fontFamily = words, fontSize = wordSize(11.sp, 12.sp), lineHeight = wordSize(15.sp, 16.sp))
            }
            // A lesser option under the cursor is one to write: what's sent from below goes into it.
            if (option.current && option.muted && option.scroll == 0) {
                Text("write your answer below, and send", color = Palette.blue, fontFamily = words, fontSize = wordSize(11.sp, 12.sp), lineHeight = wordSize(15.sp, 16.sp))
            }
        }
    }
}
