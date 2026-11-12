package sh.ahmd.wallflower.ui

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import sh.ahmd.wallflower.program.Cycle
import sh.ahmd.wallflower.program.Mode

/**
 * The modes a pane's program goes round, as [screen] shows them, and a mode
 * picked stepped to a key at a time: each step as far as the screen shows
 * it went, and no further round than where it started.
 */
internal class ModeWalk(private val screen: PaneScreen) {
    /** The modes the pane's program goes round, from the one it's in, as far as they've been seen. */
    fun modes(actions: PaneHand?): List<Mode> {
        val mode = screen.mode ?: return emptyList()
        return cycle(actions)?.from(mode).orEmpty()
    }

    /** The mode on screen now is one the program goes round. */
    fun saw(actions: PaneHand?) {
        screen.mode?.let { cycle(actions)?.saw(it) }
    }

    /**
     * Steps the pane's program round to the mode named [to], [onStep] at each
     * mode passed on the way: whether it got there, or null if there's no
     * going round (no mode on screen, or no key to go round with).
     */
    suspend fun walk(to: String, actions: PaneHand?, onStep: () -> Unit = {}): Boolean? {
        val keys = actions?.program?.nextMode ?: return null
        val cycle = cycle(actions) ?: return null
        val from = screen.mode ?: return null
        var at: Mode = from
        for (i in 0 until MODE_STEPS) {
            // Only at its line: a menu, or what's aside of it, would take the key as its own (shift+tab allows edits, in some).
            if (at.name == to || screen.line == null || screen.aside != null) break
            actions.keys(keys)
            // Not moved: something's over its line, which takes the key.
            val next = moved(from = at) ?: break
            cycle.stepped(at, next)
            at = next
            if (at == from) {
                cycle.missed(to) // round, and it wasn't there
                break
            }
            if (at.name != to) onStep()
        }
        return at.name == to
    }

    /** The mode once it's moved on from [from]; null if it hasn't in a while. */
    private suspend fun moved(from: Mode): Mode? =
        withTimeoutOrNull(MODE_STEP_MS) { snapshotFlow { screen.mode }.first { it != null && it != from } }

    private fun cycle(actions: PaneHand?): Cycle? = actions?.program?.takeIf { it.nextMode != null }?.let(Cycles::of)
}

/** How many steps a mode picked is gone round to at most, and how long each may take to show. */
private const val MODE_STEPS = 8
private const val MODE_STEP_MS = 2_000L
