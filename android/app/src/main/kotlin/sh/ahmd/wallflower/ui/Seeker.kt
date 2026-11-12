package sh.ahmd.wallflower.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.ahmd.wallflower.program.Recall
import sh.ahmd.wallflower.program.Walk
import sh.ahmd.wallflower.read.Entry
import sh.ahmd.wallflower.read.Party
import sh.ahmd.wallflower.read.Reading
import sh.ahmd.wallflower.screen.InputLine
import sh.ahmd.wallflower.screen.Prompt
import sh.ahmd.wallflower.ui.composer.Recalled

/**
 * A sent message asked back to edit, and the way to it: a program that
 * brings one back with a command (Claude Code's /rewind) lists the user's
 * messages, and the one asked for is found in that list, up to where the
 * user confirms. What a prompt did shows in the line it leaves, a moment
 * after it goes, whoever answered it. Its waits run in [scope].
 */
@Stable
internal class Seeker(private val scope: CoroutineScope, private val now: () -> Long = System::currentTimeMillis) {
    /**
     * A message asked back, looked for in the list its command opens: [id]
     * and what it [said], with [newer] of the user's messages after it. The
     * [list] as it opened; the places in it picked to see what they ask, the
     * latest last ([tried]); the prompt the last keys went [from]; and whether
     * they went [back] to the list, from what picking another asked.
     */
    data class Seeking(
        val id: String,
        val said: String,
        val newer: Int,
        val list: Walk.Listed? = null,
        val tried: List<Int> = emptyList(),
        val from: Prompt? = null,
        val back: Boolean = false,
    )

    private val sought = mutableStateOf<Seeking?>(null)
    var seeking: Seeking?
        get() = sought.value
        private set(value) {
            if (value == sought.value) return
            sought.value = value
            expire()
        }

    /** The message walked to, to edit. */
    var editing by mutableStateOf<String?>(null)
        private set

    /** The message asked back to edit, its text, each time it's asked. */
    var recalled by mutableStateOf<Recalled?>(null)
        private set

    private var asked: Prompt? = null
    private var gone: Long? = null
    private var expiring: Job? = null

    /** Another pane: nothing of the last one's carries over. */
    fun reset() {
        seeking = null
        editing = null
        recalled = null
        asked = null
        gone = null
    }

    /** The user's own hand on the prompt: the walk stops. */
    fun handled() {
        seeking = null
    }

    /** The user asked for [message] back, from [reading], to edit. */
    fun edit(message: Entry.Said, actions: PaneHand, reading: Reading, line: InputLine?) {
        val r = actions.recall(message, reading, line) ?: return
        recalled = Recalled(message.text, System.nanoTime())
        if (r is Recall.Command) {
            val after = reading.shown.dropWhile { it.id != message.id }.drop(1)
            seeking = Seeking(message.id, message.text, newer = after.count { it is Entry.Said && it.by == Party.User })
        }
    }

    /** Looked for a while since the last keys, it's given up: the user picks. */
    private fun expire() {
        expiring?.cancel()
        val s = seeking ?: return
        expiring = scope.launch {
            // The list can't be read while Claude says the wheel sends arrows, for some seconds, after asking about another.
            delay(if (s.back) SEEKS_BACK_MS else SEEKS_MS)
            seeking = null
        }
    }

    /** The pane's screen offers [p] now: a step on the way, if one's being found. */
    fun prompt(p: Prompt?, actions: PaneHand?) {
        val s = seeking ?: return
        if (p == null || p == s.from || actions == null) return // what the last keys went from: they haven't shown yet
        val list = s.list
        when (val walk = actions.program.walk(p, s.said)) {
            null -> Unit // something between
            is Walk.Listed -> {
                // Straight to where the conversation has it, and picked, which only asks; once back from asking about another, close by.
                if (list != null && !s.back) return // on the way to what a pick asks
                val at = nearby(s.newer, walk.last).firstOrNull { it !in s.tried }
                if (at == null) {
                    seeking = null // the list hasn't it: the user picks
                    return
                }
                actions.keys(moves(walk, at - walk.back) + walk.pick)
                seeking = s.copy(list = list ?: walk, tried = s.tried + at, from = p, back = false)
            }
            is Walk.Picked -> {
                if (list == null || s.back) return // not one this asked, or on the way back
                if (walk.on) {
                    editing = s.id
                    seeking = null
                    return
                }
                // Not it (the list may count a message differently): back to the list, keys alone, since
                // what comes right after Esc can be taken with it (alt and a key), and then Enter would answer this.
                actions.keys(walk.back)
                seeking = if (nearby(s.newer, list.last).any { it !in s.tried }) s.copy(from = p, back = true) else null
            }
        }
    }

    /** What a prompt did shows in the line it leaves, a moment after it goes, whoever answered it: a rewind restored at the computer, say. */
    fun settle(prompt: Prompt?, held: String?, line: InputLine?, actions: PaneHand?) {
        val was = asked
        if (prompt != null) {
            asked = prompt
            gone = null
        } else if (was != null && gone == null) {
            gone = now()
        }
        val g = gone
        if (prompt == null && was != null && held != null && g != null && now() - g < SETTLES_MS) {
            actions?.settled(was, line, editing)
            asked = null
            editing = null
        }
    }

    companion object {
        /** Keys that move the list's cursor [by] messages back (forward, if negative). */
        private fun moves(list: Walk.Listed, by: Int): List<String> = List(abs(by)) { if (by > 0) list.up else list.down }.flatten()

        /** Where to look for a message [at] in a list going [last] back: there, then a step either side, then two, and so on, up to [NEARBY]. */
        fun nearby(at: Int, last: Int): List<Int> =
            (0..NEARBY).flatMap { d -> if (d == 0) listOf(at) else listOf(at - d, at + d) }.filter { it in 0..last }
    }
}

/** How long a message asked back is looked for in what its command opens, from the last keys sent. */
internal const val SEEKS_MS = 10_000L

/** Or back in its list from another's confirmation: the list can't be read while Claude says the wheel sends arrows, for some seconds. */
internal const val SEEKS_BACK_MS = 20_000L

/** How far from where the conversation has a message the list it's asked back from is looked through. */
private const val NEARBY = 3

/** How soon after a prompt goes the line it leaves says what answering it did. */
internal const val SETTLES_MS = 3_000L
