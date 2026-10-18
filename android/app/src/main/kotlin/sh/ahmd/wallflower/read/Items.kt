package sh.ahmd.wallflower.read

/** What a read view lists: something said (or shown, or noted), or the run of steps between. */
sealed interface Item {
    val key: String

    /** [last]: the program's reply that ends its turn, which gets a copy button once the turn is done. */
    data class Said(val entry: Entry, val last: Boolean = false) : Item {
        override val key get() = entry.id
    }

    /** Steps and thoughts in a row, folded into one. */
    data class Steps(val entries: List<Entry>) : Item {
        override val key get() = "steps " + entries.first().id
    }
}

/** [entries] as a read view lists them: runs of steps folded, and each turn's last reply marked. */
fun items(entries: List<Entry>): List<Item> {
    val out = mutableListOf<Item>()
    var steps = mutableListOf<Entry>()
    for (e in entries) {
        if (e is Entry.Step || e is Entry.Thought) {
            steps += e
            continue
        }
        if (steps.isNotEmpty()) out += Item.Steps(steps)
        steps = mutableListOf()
        out += Item.Said(e)
    }
    if (steps.isNotEmpty()) out += Item.Steps(steps)
    // A turn ends where the user speaks next, or at the end.
    var ended = true
    for (i in out.indices.reversed()) {
        val e = (out[i] as? Item.Said)?.entry as? Entry.Said ?: continue
        when {
            e.by == Party.User -> ended = true
            ended -> {
                out[i] = Item.Said(e, last = true)
                ended = false
            }
        }
    }
    return out
}

/** Steps as text, to copy: what each ran on, and what came of it. */
fun trace(steps: List<Entry>) = steps.joinToString("\n\n") { s ->
    when (s) {
        is Entry.Step -> listOf(if (s.input.isEmpty()) s.name else "${s.name}: ${s.input}", s.diff.ifEmpty { s.output }).filter { it.isNotEmpty() }.joinToString("\n")
        is Entry.Thought -> "thinking: ${s.text}"
        else -> ""
    }
}
