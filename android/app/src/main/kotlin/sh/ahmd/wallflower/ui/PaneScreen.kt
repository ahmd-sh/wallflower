package sh.ahmd.wallflower.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import sh.ahmd.wallflower.program.Program
import sh.ahmd.wallflower.screen.InputLine
import sh.ahmd.wallflower.screen.Prompt
import sh.ahmd.wallflower.terminal.Terminal

/** What the shown pane's screen says: its program's input line, what it says it's doing, and a prompt to answer while one can be. */
@Stable
internal class PaneScreen {
    var line: InputLine? by mutableStateOf(null)
    var status: String? by mutableStateOf(null)
    var prompt: Prompt? by mutableStateOf(null)
    val ticks = Channel<Unit>(Channel.CONFLATED)

    /** Reads it again soon. */
    fun reread() {
        ticks.trySend(Unit)
    }
}

/**
 * Reads [terminal]'s screen whenever its output changes, at most every
 * [EVERY_MS], as [program] reads it: its input line, and, while [asks]
 * (it can be answered: in the mobile view, the program not working), a
 * prompt. Changes in what the program is doing ([activity]) reread it: it
 * can start asking with its screen already drawn.
 */
@Composable
internal fun rememberPaneScreen(pane: String?, terminal: Terminal?, program: Program, asks: Boolean, activity: Any?): PaneScreen {
    val screen = remember { PaneScreen() }
    val reader by rememberUpdatedState(program)
    val asking by rememberUpdatedState(asks)
    LaunchedEffect(pane) { screen.prompt = null } // another pane's; the same pane's new terminal (more history) offers the same
    LaunchedEffect(asks, activity) { screen.reread() }
    LaunchedEffect(terminal) {
        screen.line = null
        screen.status = null
        val output = Runnable { screen.reread() }
        terminal?.addListener(output)
        try {
            screen.reread()
            for (tick in screen.ticks) {
                val p = reader
                val a = asking
                val read = withContext(Dispatchers.Default) {
                    val s = terminal?.screen()
                    Triple(s?.let(p::line), s?.let(p::status), s?.takeIf { a }?.let(p::prompt))
                }
                screen.line = read.first
                screen.status = read.second
                screen.prompt = read.third
                delay(EVERY_MS)
            }
        } finally {
            terminal?.removeListener(output)
        }
    }
    return screen
}

private const val EVERY_MS = 80L
