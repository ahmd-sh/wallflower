package sh.ahmd.wallflower.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.ahmd.wallflower.terminal.Menu

/**
 * A menu the program's screen offers, to answer by touch: what it's about,
 * its questions as tabs, its options. Picking one sends [onKeys] the keys
 * that pick it on the screen, so the program, and the Mac, see the same
 * answer as if typed. The card goes when the screen moves on.
 */
@Composable
fun MenuCard(menu: Menu, onKeys: (List<String>) -> Unit) {
    val haptics = haptics()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.bg1).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (menu.tabs.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (tab in menu.tabs) {
                    Text(
                        tab.label,
                        color = if (tab.current) Palette.fg else Palette.grey,
                        fontFamily = words,
                        fontSize = wordSize(12.sp, 13.sp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (tab.current) Palette.bg3 else Palette.bg2)
                            .clickable(enabled = menu.tabs.size > 1) {
                                haptics.tick()
                                onKeys(menu.keys(tab))
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
        About(menu.lines)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (option in menu.options) {
                Choice(option) {
                    if (option.checked != null) haptics.toggle(option.checked != true) else haptics.confirm()
                    onKeys(menu.keys(option))
                }
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                "esc",
                color = Palette.grey,
                fontFamily = Mono,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        haptics.tick()
                        onKeys(listOf("Escape"))
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** What the menu is about: the first and last lines (a title, the question) stand out, boxed rows are code. */
@Composable
private fun About(lines: List<Menu.Line>) {
    val words = lines.withIndex().filter { !it.value.code }.map { it.index }
    var i = 0
    while (i < lines.size) {
        if (lines[i].code) {
            val start = i
            while (i < lines.size && lines[i].code) i++
            Text(
                lines.subList(start, i).joinToString("\n") { it.text },
                color = Palette.fg,
                fontFamily = Mono,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.bg0)
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            )
            continue
        }
        val stands = i == words.first() || i == words.last()
        Text(
            lines[i].text,
            color = if (stands) Palette.fg else Palette.grey,
            fontFamily = sh.ahmd.wallflower.ui.words,
            fontSize = if (stands) wordSize(14.sp, 16.sp) else wordSize(12.sp, 14.sp),
            fontWeight = if (stands) (if (grotesque) FontWeight.SemiBold else FontWeight.Bold) else FontWeight.Normal,
            lineHeight = wordSize(18.sp, 21.sp),
        )
        i++
    }
}

/** An option: its number (the key that picks it) or box, its label, and what it means beneath. The cursor's one is lit. */
@Composable
private fun Choice(option: Menu.Option, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (option.current) Palette.bg3 else Palette.bg2)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val checked = option.checked
        when {
            checked != null -> Checkbox(
                checked,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = Palette.orange, uncheckedColor = Palette.grey, checkmarkColor = Palette.bg0),
            )
            else -> Text(
                option.number?.toString() ?: "›",
                color = Palette.dim,
                fontFamily = Mono,
                fontSize = 13.sp,
                modifier = Modifier.widthIn(min = 16.dp),
            )
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                option.label,
                color = if (option.muted) Palette.grey else Palette.fg,
                fontFamily = words,
                fontSize = wordSize(13.sp, 15.sp),
                lineHeight = wordSize(18.sp, 20.sp),
            )
            if (option.detail.isNotEmpty()) {
                Text(option.detail, color = Palette.grey, fontFamily = words, fontSize = wordSize(12.sp, 13.sp), lineHeight = wordSize(16.sp, 18.sp))
            }
        }
    }
}
