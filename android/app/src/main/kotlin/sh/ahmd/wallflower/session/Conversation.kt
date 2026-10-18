package sh.ahmd.wallflower.session

import sh.ahmd.wallflower.protocol.Entries
import sh.ahmd.wallflower.protocol.Entry

/**
 * A pane's conversation, as the program in it recorded it. [loaded] once the
 * gateway has sent it; [more] if it holds earlier entries than these;
 * [error] when there's none to show. [state] is what the program is doing,
 * if it says ([Entries.WORKING]...), and [since] when it started; [context]
 * how many tokens it takes of the program's context, 0 while that's unknown.
 */
data class Conversation(
    val entries: List<Entry> = emptyList(),
    val more: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
    val state: String? = null,
    val since: Long = 0,
    val context: Int = 0,
) {
    val working get() = state == Entries.WORKING

    /** With [m] applied: a reset replaces everything, else entries replace theirs by id or come last; a state replaces the last. */
    fun apply(m: Entries): Conversation {
        if (m.reset) {
            return Conversation(m.entries.takeLast(MAX_ENTRIES), m.more || m.entries.size > MAX_ENTRIES, m.error, loaded = true, m.state, m.since, m.context ?: 0)
        }
        val context = m.context ?: context
        if (m.entries.isEmpty() && (m.state != null || m.context != null)) {
            return copy(state = m.state ?: state, since = if (m.state != null) m.since else since, error = m.error ?: error, context = context)
        }
        val at = HashMap<String, Int>(entries.size)
        entries.forEachIndexed { i, e -> at[e.id] = i }
        val next = entries.toMutableList()
        for (e in m.entries) {
            val i = at[e.id]
            if (i != null) {
                next[i] = e
            } else {
                at[e.id] = next.size
                next += e
            }
        }
        val dropped = (next.size - MAX_ENTRIES).coerceAtLeast(0)
        return copy(
            entries = if (dropped > 0) next.drop(dropped) else next,
            more = more || dropped > 0,
            error = m.error,
            loaded = true,
            state = m.state ?: state,
            since = if (m.state != null) m.since else since,
            context = context,
        )
    }

    /** Whether the program changed files after the user's message [entry]: what rewinding to before it can restore. */
    fun changedCodeAfter(entry: String): Boolean {
        val at = entries.indexOfFirst { it.id == entry }
        return at >= 0 && entries.drop(at + 1).any { it.kind == "tool" && it.name in EDITS && it.done && !it.error }
    }

    companion object {
        const val MAX_ENTRIES = 1000

        /** Claude Code's tools whose changes a rewind can undo (not bash's). */
        private val EDITS = setOf("Edit", "Write", "MultiEdit", "NotebookEdit")
    }
}
