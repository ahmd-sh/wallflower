package sh.ahmd.wallflower.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import sh.ahmd.wallflower.protocol.Close
import sh.ahmd.wallflower.protocol.DEFAULT_HISTORY
import sh.ahmd.wallflower.protocol.Open
import sh.ahmd.wallflower.protocol.Reset
import sh.ahmd.wallflower.screen.Screen
import sh.ahmd.wallflower.terminal.Terminal

/**
 * The terminals of the panes the phone has open. Every pane is kept open
 * and streaming, so switching to one, or seeing them all, needs no round
 * trip: the panes on screen (each window's shown pane) with their
 * scrollback, the rest screen only until they're looked at.
 */
internal class Terminals(private val link: Link) {
    /** By pane, replaced on every reset. */
    private val _terminals = MutableStateFlow<Map<String, Terminal>>(emptyMap())
    val terminals: StateFlow<Map<String, Terminal>> = _terminals.asStateFlow()

    /** Pane -> history lines it has, least recently viewed first; what this connection opened. */
    private val opened = LinkedHashMap<String, Int>()

    /** Every pane stopped streaming: the user is away; terminals keep what they show until they open again. */
    private var quiet = false

    /** A new connection opens panes again, from its first state. */
    fun linked() = synchronized(opened) { opened.clear() }

    fun quiet(on: Boolean) {
        val closing = synchronized(opened) {
            quiet = on
            if (!on) return
            opened.keys.toList().also { opened.clear() }
        }
        closing.forEach { link.send(Close(it)) }
    }

    /**
     * Opens a pane, or reopens it with its scrollback if it only had its
     * screen. [full] panes get scrollback; the others only open while there
     * are fewer than [MAX_OPEN], and the least recently viewed go first.
     * A pane is only counted open once its open is sent.
     */
    fun open(pane: String, viewed: Boolean, full: Boolean = viewed) {
        val history = if (full) DEFAULT_HISTORY else PREVIEW_HISTORY
        val evict = synchronized(opened) {
            if (quiet) return
            val had = opened[pane]
            if (had == null && !full && opened.size >= MAX_OPEN) return
            if (had != null && had >= history) {
                if (viewed) opened[pane] = opened.remove(pane)!! // now the most recently viewed
                return
            }
            if (!link.send(Open(pane, history.takeIf { it != DEFAULT_HISTORY }))) return
            if (viewed || had == null) opened.remove(pane)
            opened[pane] = history
            if (opened.size > MAX_OPEN) opened.keys.first().also { opened.remove(it) } else null
        }
        if (evict != null) {
            link.send(Close(evict))
            _terminals.getAndUpdate { it - evict }[evict]?.close()
        }
    }

    /**
     * [pane]'s screen once its output settles, opening it for the while if
     * it isn't streaming (the user's away); null if it can't be had.
     */
    suspend fun peek(pane: String): Screen? {
        val before = _terminals.value[pane]
        val opening = synchronized(opened) { pane !in opened }
        if (opening && !link.send(Open(pane, PREVIEW_HISTORY))) return null
        try {
            val term = if (!opening) {
                before
            } else {
                withTimeoutOrNull(PEEK_OPEN_MS) { terminals.first { it[pane].let { t -> t != null && t !== before } }[pane] }
            } ?: return null
            settle(term, painted = !opening)
            return withContext(Dispatchers.Default) { term.screen() }
        } finally {
            // Unless it's been opened since, as the user came back.
            if (opening) synchronized(opened) { if (pane !in opened) link.send(Close(pane)) }
        }
    }

    /** Until [term]'s had no output for a moment, once it has some unless it's [painted]. */
    private suspend fun settle(term: Terminal, painted: Boolean) {
        val changed = Channel<Unit>(Channel.CONFLATED)
        val output = Runnable { changed.trySend(Unit) }
        term.addListener(output)
        try {
            withTimeoutOrNull(PEEK_SETTLE_MAX_MS) {
                if (!painted) changed.receive()
                while (withTimeoutOrNull(PEEK_QUIET_MS) { changed.receive() } != null) Unit
            }
        } finally {
            term.removeListener(output)
        }
    }

    /** The gateway repaints a pane from scratch, in a new terminal. */
    fun reset(m: Reset) {
        val term = Terminal(m.cols, m.rows)
        _terminals.getAndUpdate { it + (m.pane to term) }[m.pane]?.close()
    }

    fun write(pane: String, bytes: ByteArray, offset: Int, length: Int) {
        _terminals.value[pane]?.write(bytes, offset, length)
    }

    /** Panes that no longer exist close quietly; the gateway forgot them already. */
    fun keep(live: Set<String>) {
        synchronized(opened) { opened.keys.retainAll(live) }
        if (_terminals.value.keys.all { it in live }) return
        val before = _terminals.getAndUpdate { all -> all.filterKeys { it in live } }
        before.filterKeys { it !in live }.values.forEach { it.close() }
    }

    companion object {
        const val MAX_OPEN = 64
        const val PREVIEW_HISTORY = 1
        const val PEEK_OPEN_MS = 3_000L
        const val PEEK_QUIET_MS = 250L
        const val PEEK_SETTLE_MAX_MS = 2_000L
    }
}
