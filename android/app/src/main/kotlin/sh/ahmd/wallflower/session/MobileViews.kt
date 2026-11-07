package sh.ahmd.wallflower.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import sh.ahmd.wallflower.protocol.Entries
import sh.ahmd.wallflower.protocol.Read
import sh.ahmd.wallflower.read.Reading
import sh.ahmd.wallflower.read.apply

/**
 * Messages a rewind or stop took back, by entry id, kept past the app's life: the program records it only
 * with the next message, so a view read afresh would show them again.
 */
interface Hidden {
    fun all(): Set<String>

    fun add(id: String)

    fun remove(id: String)

    object None : Hidden {
        override fun all() = emptySet<String>()

        override fun add(id: String) = Unit

        override fun remove(id: String) = Unit
    }
}

/** The mobile views of the panes the phone shows them for, as the gateway sends them. */
internal class MobileViews(private val link: Link, private val clock: () -> Long, private val hidden: Hidden = Hidden.None) {
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
        if (m.pane !in wanted) return
        val kept = if (m.reset) hidden.all() else emptySet()
        var ended: String? = null
        _readings.update {
            ended = null
            val was = it[m.pane] ?: Reading()
            var r = was.apply(m)
            if (r.rewound == null && kept.isNotEmpty()) r = r.copy(rewound = r.entries.firstOrNull { e -> e.id in kept && r.takesBack(e.id) }?.id)
            ended = was.rewound?.takeIf { r.rewound != it }
            it + (m.pane to r)
        }
        // The program has recorded more past it: nothing to keep hidden.
        ended?.let(hidden::remove)
    }

    /** [pane]'s conversation was rewound to before the user's message [said] matches: hidden from there until the program records more. */
    fun rewound(pane: String, said: (String) -> Boolean, held: String? = null) = change(pane) { it.rewoundTo(said, held) }

    /** The user stopped [pane]'s program, which may take their latest message back. */
    fun stopped(pane: String) = change(pane) { it.copy(stopped = clock()) }

    /** [pane]'s program has put the user's latest message back in its input [line], if that's what it holds just after a stop: hidden as [rewound]. */
    fun takenBack(pane: String, line: String?) = change(pane) { it.takenBack(line, clock()) }

    private fun change(pane: String, how: (Reading) -> Reading) {
        var took: String? = null
        _readings.update { all ->
            took = null // this pass's, if it's tried again
            val was = all[pane] ?: return@update all
            val now = how(was)
            took = now.rewound?.takeIf { it != was.rewound }
            all + (pane to now)
        }
        took?.let(hidden::add)
    }
}
