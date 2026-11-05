package sh.ahmd.wallflower.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import sh.ahmd.wallflower.protocol.Entries
import sh.ahmd.wallflower.protocol.Read
import sh.ahmd.wallflower.read.Reading
import sh.ahmd.wallflower.read.apply

/** The mobile views of the panes the phone shows them for, as the gateway sends them. */
internal class MobileViews(private val link: Link, private val clock: () -> Long) {
    private val _readings = MutableStateFlow<Map<String, Reading>>(emptyMap())
    val readings: StateFlow<Map<String, Reading>> = _readings.asStateFlow()

    @Volatile private var wanted: Set<String> = emptySet()
    @Volatile private var quiet = false // the user is away: none stream, until they're back
    private val asked = HashSet<String>() // what this connection was asked for; guarded by the link's lock

    fun linked() = asked.clear()

    /** A new connection is asked for them all again. */
    fun up() = ask()

    /** Has the gateway send these panes' mobile views, and no others'. */
    fun read(panes: Set<String>) {
        wanted = panes
        ask()
        _readings.update { r -> if (r.keys.all { it in panes }) r else r.filterKeys { it in panes } }
    }

    /** Away a while, none stream; what they showed stays until they're back. */
    fun quiet(on: Boolean) {
        quiet = on
        ask()
    }

    private fun ask() {
        val panes = if (quiet) emptySet() else wanted
        synchronized(link.lock) {
            if (link.linked) {
                for (p in asked - panes) link.send(Read(p, on = false))
                for (p in panes - asked) link.send(Read(p, on = true))
                asked.clear()
                asked += panes
            }
        }
    }

    fun entries(m: Entries) {
        if (m.pane in wanted) _readings.update { it + (m.pane to (it[m.pane] ?: Reading()).apply(m)) }
    }

    /** [pane]'s conversation was rewound to before the user's message [said] matches: hidden from there until the program records more. */
    fun rewound(pane: String, said: (String) -> Boolean) = _readings.update { all -> all[pane]?.let { all + (pane to it.rewoundTo(said)) } ?: all }

    /** The user stopped [pane]'s program, which may take their latest message back. */
    fun stopped(pane: String) = _readings.update { all -> all[pane]?.let { all + (pane to it.copy(stopped = clock())) } ?: all }

    /** [pane]'s program has put the user's latest message back in its input [line], if that's what it holds just after a stop: hidden as [rewound]. */
    fun takenBack(pane: String, line: String?) = _readings.update { all -> all[pane]?.let { all + (pane to it.takenBack(line, clock())) } ?: all }
}
