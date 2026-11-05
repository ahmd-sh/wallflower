package sh.ahmd.wallflower.read

/**
 * A pane's mobile view, as its program has it. [loaded] once the gateway has
 * sent it; [more] if there were earlier entries than these; [problem] when
 * there's nothing to read. [context] is how many tokens it takes of the
 * program's context, 0 while that's unknown. [rewound]: the user's message
 * a rewind went back to before, which the program doesn't record until the
 * next message; it and what followed aren't [shown] till then. What the
 * program is doing is its pane's ([activityOf]).
 */
data class Reading(
    val entries: List<Entry> = emptyList(),
    val more: Boolean = false,
    val problem: String? = null,
    val loaded: Boolean = false,
    val context: Int = 0,
    val rewound: String? = null,
) {
    val shown: List<Entry> get() = rewound?.let { r -> entries.takeWhile { it.id != r } } ?: entries

    /**
     * Rewound to before the user's message that [said] matches. Of messages it could be, the latest: what was
     * taken back may partly show till the next message, but nothing still said is hidden.
     */
    fun rewoundTo(said: (String) -> Boolean): Reading {
        val message = entries.lastOrNull { it is Entry.Said && it.by == Party.User && said(it.text) }
        return message?.let { copy(rewound = it.id) } ?: this
    }

    /**
     * Less the user's latest message, if the program has put it back in its
     * input line ([line]) unsent, as Claude Code does with one stopped before
     * its reply, or rewound to: taken back, though it's recorded till the next.
     */
    fun takenBack(line: String?): Reading {
        if (line.isNullOrBlank() || rewound != null) return this
        val last = entries.lastOrNull { it is Entry.Said && it.by == Party.User } as Entry.Said? ?: return this
        return if (bare(last.text) == bare(line)) copy(rewound = last.id) else this
    }

    private fun bare(text: String) = text.filterNot(Char::isWhitespace)

    /** With [new] entries: each replaces the one with its id, or comes last; the oldest go past [MAX_ENTRIES]. */
    fun with(new: List<Entry>): Reading {
        if (new.isEmpty()) return this
        val at = HashMap<String, Int>(entries.size)
        entries.forEachIndexed { i, e -> at[e.id] = i }
        val next = entries.toMutableList()
        for (e in new) {
            val i = at[e.id]
            if (i != null) {
                next[i] = e
            } else {
                at[e.id] = next.size
                next += e
            }
        }
        val dropped = (next.size - MAX_ENTRIES).coerceAtLeast(0)
        return copy(entries = if (dropped > 0) next.drop(dropped) else next, more = more || dropped > 0)
    }

    companion object {
        const val MAX_ENTRIES = 1000
    }
}
