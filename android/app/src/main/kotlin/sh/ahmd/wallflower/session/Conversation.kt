package sh.ahmd.wallflower.session

import sh.ahmd.wallflower.protocol.Entries
import sh.ahmd.wallflower.protocol.Entry

/**
 * A pane's conversation, as the program in it recorded it. [loaded] once the
 * gateway has sent it; [more] if it holds earlier entries than these;
 * [error] when there's none to show.
 */
data class Conversation(
    val entries: List<Entry> = emptyList(),
    val more: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
) {
    /** With [m] applied: a reset replaces everything, else entries replace theirs by id or come last. */
    fun apply(m: Entries): Conversation {
        if (m.reset) return Conversation(m.entries.takeLast(MAX_ENTRIES), m.more || m.entries.size > MAX_ENTRIES, m.error, loaded = true)
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
        return copy(entries = if (dropped > 0) next.drop(dropped) else next, more = more || dropped > 0, error = m.error, loaded = true)
    }

    companion object {
        const val MAX_ENTRIES = 1000
    }
}
