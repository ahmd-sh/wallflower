package sh.ahmd.wallflower.session

import sh.ahmd.wallflower.protocol.Entries
import sh.ahmd.wallflower.protocol.Entry

/**
 * A pane's conversation, as the program in it recorded it. [loaded] once the
 * gateway has sent it; [more] if it holds earlier entries than these;
 * [error] when there's none to show. [state] is what the program is doing,
 * if it says ([Entries.WORKING]...), and [since] when it started; [context]
 * how many tokens it takes of the program's context, 0 while that's unknown.
 * [rewound]: the user's message a rewind went back to before, which the
 * transcript doesn't show until the next message; it and what followed
 * aren't [shown] till then.
 */
data class Conversation(
    val entries: List<Entry> = emptyList(),
    val more: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
    val state: String? = null,
    val since: Long = 0,
    val context: Int = 0,
    val rewound: String? = null,
) {
    val working get() = state == Entries.WORKING

    val shown: List<Entry> get() = rewound?.let { r -> entries.takeWhile { it.id != r } } ?: entries

    /** Rewound to before the user's message that [quote] is from, as the program quotes it (cut short with …). */
    fun rewoundTo(quote: String): Conversation {
        val q = quote.filterNot(Char::isWhitespace)
        if (q.isEmpty()) return this
        val said = entries.lastOrNull { it.role == "user" && it.kind == "text" && quotes(q, it.text.filterNot(Char::isWhitespace)) }
        return said?.let { copy(rewound = it.id) } ?: this
    }

    private fun quotes(quote: String, message: String): Boolean {
        var at = 0
        for (part in quote.split('…')) {
            val i = message.indexOf(part, at)
            if (i < 0) return false
            at = i + part.length
        }
        return true
    }

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

    companion object {
        const val MAX_ENTRIES = 1000
    }
}
