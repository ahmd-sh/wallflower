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
import sh.ahmd.wallflower.program.Asked
import sh.ahmd.wallflower.program.Aside
import sh.ahmd.wallflower.program.Mode
import sh.ahmd.wallflower.program.Recall
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
 * [prompt] the pane's screen offers, what its program says it's doing
 * ([status]) and the questions it asked waiting to be brought up ([asked]),
 * what it shows [aside] of its line (with what's been read of an
 * [answer]), and its [mode] (with those it goes round), which picked goes to
 * [onMode]; it can compact (with its [line]), and animates; its terminal view goes to [onView]. A tap on the terminal
 * goes to [onTap]. While a message asked back is looked for ([seeking]), what it's looked for in isn't asked. A message asked back to edit goes to the program, and
 * [onRecalled] once it's on its way, [listed] if it's to be found in what the program lists. Keys from the prompt's card go to
 * [onPrompt] too. The conversation's title shows unless
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
    asked: Asked? = null,
    aside: Aside? = null,
    answer: List<String> = emptyList(),
    mode: Pair<Mode, List<Mode>>? = null,
    onMode: (String) -> Unit = {},
    nudged: Long = 0,
    seeking: Boolean = false,
    onGridSize: (cols: Int, rows: Int, ahead: Boolean) -> Unit,
    onView: (TerminalView) -> Unit,
    onTap: (() -> Unit)?,
    onRecalled: (Entry.Said, listed: Boolean) -> Unit = { _, _ -> },
    onPrompt: () -> Unit = {},
    held: String? = null,
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
            asked = if (current) asked else null,
            aside = if (current) aside else null,
            answer = answer,
            mode = mode?.first?.takeIf { current },
            modes = mode?.second.orEmpty(),
            onMode = onMode,
            nudged = if (current) nudged else 0,
            seeking = current && seeking,
            onKeys = { keys ->
                onPrompt()
                actions.keys(keys)
            },
            onPicked = { prompt, option ->
                onPrompt()
                actions.picked(prompt, option, held)
            },
            onCompact = if (current && compact != null) ({ compact(line()) }) else null,
            // Not while the program asks something, or a message is being found: what asks it back would answer that.
            onEdit = if (current && prompt == null && !seeking) ({ e -> actions.recall(e, shown, line())?.let { onRecalled(e, it is Recall.Command) } }) else null,
            title = shown.title?.takeIf { it != tab },
            live = current,
        )
    }
}

/** Either side of a pane's terminal. */
internal val PAGE_PADDING = 4.dp
