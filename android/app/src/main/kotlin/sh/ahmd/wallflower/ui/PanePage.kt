package sh.ahmd.wallflower.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.input.TextFieldState
import sh.ahmd.wallflower.read.Prompts
import sh.ahmd.wallflower.read.Reading
import sh.ahmd.wallflower.read.activityOf
import sh.ahmd.wallflower.screen.InputLine
import sh.ahmd.wallflower.screen.Prompt
import sh.ahmd.wallflower.terminal.Terminal
import sh.ahmd.wallflower.terminal.TerminalView

/**
 * A pane's page: its terminal, and its read view over it while that's on,
 * which pauses the terminal underneath, as the cards covering it do: its
 * program's conversation ([reading]), or its shell's commands ([commands]).
 * With no pane ([actions] null), an empty terminal that measures the grid
 * until there are panes. [current]: the page on screen, which gets the
 * [prompt] the pane's screen offers, can compact (with its [line]), and
 * animates; its terminal view goes to [onView].
 */
@Composable
internal fun PanePage(
    actions: PaneActions?,
    terminal: Terminal?,
    reading: Reading?,
    commands: Boolean,
    /** What the phone learned of prompts, and what's written in the pane's composer, for its commands. */
    prompts: Prompts,
    draft: TextFieldState?,
    /** The composer has the keyboard (on the [current] page). */
    typing: Boolean,
    current: Boolean,
    swipe: Swipe?,
    settleHeight: Int,
    covered: Boolean,
    prompt: Prompt?,
    line: () -> InputLine?,
    onGridSize: (cols: Int, rows: Int, ahead: Boolean) -> Unit,
    onView: (TerminalView) -> Unit,
) = Box(Modifier.fillMaxSize()) {
    AndroidView(
        modifier = Modifier.fillMaxSize().padding(horizontal = PAGE_PADDING).clipToBounds(),
        factory = { ctx -> TerminalView(ctx).apply { this.onGridSize = onGridSize } },
        update = {
            it.terminal = terminal
            it.settleHeight = settleHeight
            it.paused = covered || reading != null || commands
            it.onSwipe = swipe?.let { s -> s::drag }
            it.onSwipeEnd = swipe?.let { s -> s::end }
            it.onAppScroll = { up, wheel -> actions?.wheel(up, wheel) }
            if (current) onView(it)
        },
    )
    val shown = rememberLast(reading) ?: Reading()
    val shell = rememberLast(terminal.takeIf { commands && reading == null })
    if (actions == null) return@Box
    AnimatedVisibility(reading != null, enter = fadeIn(Motion.effect()), exit = fadeOut(Motion.effect())) {
        val compact = actions.compact
        ReadView(
            shown,
            activity = activityOf(actions.pane),
            since = actions.pane.since,
            picture = actions::picture,
            swipe = swipe,
            prompt = if (current) prompt else null,
            onKeys = { keys -> actions.keys(keys) },
            onPicked = actions::picked,
            onCompact = if (current && compact != null) ({ compact(line()) }) else null,
            live = current,
        )
    }
    AnimatedVisibility(commands && reading == null && terminal != null, enter = fadeIn(Motion.effect()), exit = fadeOut(Motion.effect())) {
        if (shell != null && draft != null) {
            CommandsView(
                shell,
                actions.pane.cols,
                prompts,
                draft,
                pending = if (current) line()?.text.orEmpty() else "",
                focused = current && typing,
                swipe = swipe,
                onWheel = actions::wheel,
            )
        }
    }
}

/** Either side of a pane's terminal. */
internal val PAGE_PADDING = 4.dp
