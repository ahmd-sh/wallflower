package sh.ahmd.wallflower.ui.mobile

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import sh.ahmd.wallflower.program.Hue
import sh.ahmd.wallflower.program.Mode
import sh.ahmd.wallflower.ui.MenuAction
import sh.ahmd.wallflower.ui.Palette
import sh.ahmd.wallflower.ui.PopupMenu
import sh.ahmd.wallflower.ui.Type
import sh.ahmd.wallflower.ui.rememberOpener
import sh.ahmd.wallflower.ui.slides

/**
 * What goes beside the last reply's copy, or on its own: the [mode] the
 * program's in, to pick another of those it goes round ([modes]), and how
 * full its context is ([tokens]), to compact it.
 */
@Composable
internal fun Footer(mode: Mode?, modes: List<Mode>, onMode: (String) -> Unit, tokens: Int, onCompact: (() -> Unit)?) =
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        mode?.let { ModeButton(it, modes, onMode) }
        if (tokens > 0) ContextButton(tokens, onCompact)
    }

/**
 * How much of the program's context the conversation takes; tapped or
 * held, it offers to compact it (held, as it's in a list that scrolls, the
 * finger can slide on to that).
 */
@Composable
private fun ContextButton(tokens: Int, onCompact: (() -> Unit)?) {
    val menu = rememberOpener()
    Box {
        Text(
            "context ${count(tokens)}",
            color = Palette.purple,
            style = Type.caption,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .slides(menu)
                .combinedClickable(
                    enabled = onCompact != null,
                    hapticFeedbackEnabled = false, // the menu's opening is felt
                    onClick = { menu.open = true },
                    onLongClick = { menu.open = true },
                )
                .padding(vertical = 4.dp, horizontal = 2.dp),
        )
        PopupMenu(
            menu,
            actions = listOf(
                MenuAction("compact") {
                    menu.open = false
                    onCompact?.invoke()
                },
            ),
        )
    }
}

/**
 * The mode the program's in, colored as it shows it; tapped or held, its
 * modes, the one it's in lit, to step to another.
 */
@Composable
private fun ModeButton(mode: Mode, modes: List<Mode>, onMode: (String) -> Unit) {
    val menu = rememberOpener()
    Box {
        Text(
            said(mode),
            color = colorOf(mode.hue),
            style = Type.caption,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .slides(menu)
                .combinedClickable(
                    hapticFeedbackEnabled = false, // the menu's opening is felt
                    onClick = { menu.open = true },
                    onLongClick = { menu.open = true },
                )
                .padding(vertical = 4.dp, horizontal = 2.dp),
        )
        PopupMenu(
            menu,
            actions = modes.map { m ->
                MenuAction(said(m), colorOf(m.hue), current = m.name == mode.name) {
                    menu.open = false
                    if (m.name != mode.name) onMode(m.name)
                }
            },
        )
    }
}

/** A mode by its name, in its color: its mark (⏵⏵, ⏸) is an emoji in some fonts and missing in others. */
private fun said(mode: Mode) = mode.name

/** A hue a program shows something in, in the theme's colors. */
@Composable
private fun colorOf(hue: Hue) = when (hue) {
    Hue.Grey -> Palette.grey
    Hue.Red -> Palette.red
    Hue.Orange -> Palette.orange
    Hue.Yellow -> Palette.yellow
    Hue.Green -> Palette.green
    Hue.Aqua -> Palette.aqua
    Hue.Blue -> Palette.blue
    Hue.Purple -> Palette.purple
}

private fun count(n: Int) = when {
    n < 1_000 -> "$n"
    n < 1_000_000 -> "${n / 1_000}k"
    else -> "%.1fM".format(n / 1e6)
}
