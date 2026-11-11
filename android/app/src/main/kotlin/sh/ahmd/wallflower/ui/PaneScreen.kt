package sh.ahmd.wallflower.ui

import android.os.SystemClock
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.ahmd.wallflower.program.Asked
import sh.ahmd.wallflower.program.Aside
import sh.ahmd.wallflower.program.Cycle
import sh.ahmd.wallflower.program.Mode
import sh.ahmd.wallflower.program.Program
import sh.ahmd.wallflower.screen.InputLine
import sh.ahmd.wallflower.screen.Prompt
import sh.ahmd.wallflower.terminal.Terminal

/**
 * What the shown pane's screen says: its program's input line, what it says
 * it's doing, a prompt to answer while one can be, questions it asked
 * waiting to be brought up, what's shown aside of its line, and the mode
 * it's in. Of an answer aside, [answer] is what's been read of it so far,
 * as it's scrolled.
 */
@Stable
internal class PaneScreen {
    var line: InputLine? by mutableStateOf(null)
    var status: String? by mutableStateOf(null)
    var prompt: Prompt? by mutableStateOf(null)
    var asked: Asked? by mutableStateOf(null)
    var aside: Aside? by mutableStateOf(null)
    var answer: List<String> by mutableStateOf(emptyList())
    var mode: Mode? by mutableStateOf(null)
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
 * can start asking with its screen already drawn. What it says it's doing,
 * and its mode, hold while its line can't be read.
 */
@Composable
internal fun rememberPaneScreen(pane: String?, terminal: Terminal?, program: Program, asks: Boolean, activity: Any?): PaneScreen {
    val screen = remember { PaneScreen() }
    val reader by rememberUpdatedState(program)
    val asking by rememberUpdatedState(asks)
    // Another pane's; the same pane's new terminal (repainted, as after a resize) says the same once it's drawn.
    LaunchedEffect(pane) {
        screen.line = null
        screen.status = null
        screen.prompt = null
        screen.asked = null
        screen.aside = null
        screen.answer = emptyList()
        screen.mode = null
    }
    LaunchedEffect(asks, activity) { screen.reread() }
    LaunchedEffect(terminal) {
        var question: String? = null
        var asideAt = 0L
        val output = Runnable { screen.reread() }
        terminal?.addListener(output)
        try {
            screen.reread()
            for (tick in screen.ticks) {
                val p = reader
                val a = asking
                val read = withContext(Dispatchers.Default) {
                    val s = terminal?.screen()
                    Read(s?.let(p::line), s?.let(p::status), s?.takeIf { a }?.let(p::prompt), s?.let(p::asked), s?.let(p::aside), s?.let(p::mode))
                }
                screen.line = read.line
                // A screen without the program's line is between drawings, or covered by what it asks: it doesn't say it stopped.
                if (read.line != null || read.status != null) screen.status = read.status
                screen.prompt = read.prompt
                screen.asked = read.asked
                if (read.mode != null) screen.mode = read.mode
                // What's read of an answer is kept through a screen between drawings, and laid together as it scrolls, till another question.
                (read.aside as? Aside.Answer)?.let { answer ->
                    screen.answer = if (answer.question == question) answer.over(screen.answer) else answer.rows.orEmpty()
                    question = answer.question
                }
                // A screen between drawings has none of it: what was aside holds a moment, till it's read again.
                val now = SystemClock.uptimeMillis()
                if (read.aside != null) asideAt = now
                if (read.aside == null && read.line == null && read.prompt == null && screen.aside != null && now - asideAt < ASIDE_HOLD_MS) {
                    launch {
                        delay(ASIDE_HOLD_MS)
                        screen.reread()
                    }
                } else {
                    screen.aside = read.aside
                }
                delay(EVERY_MS)
            }
        } finally {
            terminal?.removeListener(output)
        }
    }
    return screen
}

private class Read(val line: InputLine?, val status: String?, val prompt: Prompt?, val asked: Asked?, val aside: Aside?, val mode: Mode?)

private const val EVERY_MS = 80L

private const val ASIDE_HOLD_MS = 400L

/** The modes each program has been seen to go round, while the app runs: they're how it's set up on the computer, the same in each of its panes. */
internal object Cycles {
    private val all = HashMap<Program, Cycle>()

    fun of(program: Program): Cycle = all.getOrPut(program) { Cycle(program.modes, program::hue) }
}
