package sh.ahmd.wallflower.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import sh.ahmd.wallflower.protocol.Close
import sh.ahmd.wallflower.protocol.DEFAULT_HISTORY
import sh.ahmd.wallflower.protocol.Open
import sh.ahmd.wallflower.protocol.Reset
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

    /** Panes whose terminal a shell marked commands in (OSC 133). */
    private val _marked = MutableStateFlow<Set<String>>(emptySet())
    val marked: StateFlow<Set<String>> = _marked.asStateFlow()

    /** Pane -> history lines it has, least recently viewed first; what this connection opened. */
    private val opened = LinkedHashMap<String, Int>()

    /** A new connection opens panes again, from its first state. */
    fun linked() = synchronized(opened) { opened.clear() }

    /**
     * Opens a pane, or reopens it with its scrollback if it only had its
     * screen. [full] panes get scrollback; the others only open while there
     * are fewer than [MAX_OPEN], and the least recently viewed go first.
     * A pane is only counted open once its open is sent.
     */
    fun open(pane: String, viewed: Boolean, full: Boolean = viewed) {
        val history = if (full) DEFAULT_HISTORY else PREVIEW_HISTORY
        val evict = synchronized(opened) {
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

    /** The gateway repaints a pane from scratch, in a new terminal. */
    fun reset(m: Reset) {
        val term = Terminal(m.cols, m.rows)
        _marked.update { it - m.pane } // until the repaint's marks say so again
        _terminals.getAndUpdate { it + (m.pane to term) }[m.pane]?.close()
    }

    fun write(pane: String, bytes: ByteArray, offset: Int, length: Int) {
        val term = _terminals.value[pane] ?: return
        term.write(bytes, offset, length)
        if (term.marked && pane !in _marked.value) _marked.update { it + pane }
    }

    /** Panes that no longer exist close quietly; the gateway forgot them already. */
    fun keep(live: Set<String>) {
        synchronized(opened) { opened.keys.retainAll(live) }
        _marked.update { it intersect live }
        if (_terminals.value.keys.all { it in live }) return
        val before = _terminals.getAndUpdate { all -> all.filterKeys { it in live } }
        before.filterKeys { it !in live }.values.forEach { it.close() }
    }

    companion object {
        const val MAX_OPEN = 64
        const val PREVIEW_HISTORY = 1
    }
}
