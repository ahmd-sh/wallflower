package sh.ahmd.wallflower.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import sh.ahmd.wallflower.read.Entry
import sh.ahmd.wallflower.read.Reading
import sh.ahmd.wallflower.read.activityOf
import sh.ahmd.wallflower.screen.InputLine
import sh.ahmd.wallflower.screen.Prompt
import sh.ahmd.wallflower.terminal.Terminal
import sh.ahmd.wallflower.terminal.TerminalView

/**
 * A pane's page: its terminal, and its mobile view over it while that's on
 * ([reading]), which pauses the terminal underneath, as the cards covering
 * it do. With no pane ([actions] null), an empty terminal that measures the
 * grid until there are panes. [current]: the page on screen, which gets the
 * [prompt] the pane's screen offers, and what its program says it's doing
 * ([status]), can compact (with its [line]), and animates; its terminal view goes to [onView]. A tap on the terminal
 * goes to [onTap]. A message asked back to edit goes to the program, and
 * [onRecalled] once it's on its way. The conversation's title shows unless
 * the [tab] says it already.
 */
@Composable
internal fun PanePage(
    actions: PaneActions?,
    terminal: Terminal?,
    reading: Reading?,
    current: Boolean,
    swipe: Swipe?,
    settleHeight: Int,
    covered: Boolean,
    prompt: Prompt?,
    line: () -> InputLine?,
    status: String? = null,
    nudged: Long = 0,
    onGridSize: (cols: Int, rows: Int, ahead: Boolean) -> Unit,
    onView: (TerminalView) -> Unit,
    onTap: (() -> Unit)?,
    onRecalled: (Entry.Said) -> Unit = {},
    tab: String? = null,
) = Box(Modifier.fillMaxSize()) {
    val theme = Palette.terminal
    AndroidView(
        modifier = Modifier.fillMaxSize().padding(horizontal = PAGE_PADDING).clipToBounds(),
        factory = { ctx -> TerminalView(ctx).apply { this.onGridSize = onGridSize } },
        update = {
            it.terminal = terminal
            it.theme = theme
            it.settleHeight = settleHeight
            it.paused = covered || reading != null
            it.onSwipe = swipe?.let { s -> s::drag }
            it.onSwipeEnd = swipe?.let { s -> s::end }
            it.onAppScroll = { up, wheel -> actions?.wheel(up, wheel) }
            it.onTap = onTap
            if (current) onView(it)
        },
    )
    val shown = rememberLast(reading) ?: Reading()
    if (actions == null) return@Box
    AnimatedVisibility(reading != null, enter = fadeIn(Motion.effect()), exit = fadeOut(Motion.effect())) {
        val compact = actions.compact
        MobileView(
            shown,
            activity = activityOf(actions.pane),
            since = actions.pane.since,
            picture = actions::picture,
            swipe = swipe,
            prompt = if (current) prompt else null,
            doing = if (current) status else null,
            nudged = if (current) nudged else 0,
            onKeys = { keys -> actions.keys(keys) },
            onPicked = actions::picked,
            onCompact = if (current && compact != null) ({ compact(line()) }) else null,
            onEdit = if (current) ({ e -> if (actions.recall(e, shown, line())) onRecalled(e) }) else null,
            title = shown.title?.takeIf { it != tab },
            live = current,
        )
    }
}

/** Either side of a pane's terminal. */
internal val PAGE_PADDING = 4.dp
