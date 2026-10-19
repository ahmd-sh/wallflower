package sh.ahmd.wallflower.read

import sh.ahmd.wallflower.terminal.Commands

/**
 * A shell pane's read view, read off its terminal: each command a title
 * (what was typed) and its output in a card, laid out as the terminal has
 * it, an empty one if it printed nothing. Above them, what came before the first command the phone saw marked,
 * in one card kept above the screen until scrolled to ([Gap]). Long output
 * comes in pieces of rows, so it scrolls by without being drawn all at once.
 */
internal sealed interface ShellItem {
    val key: String

    /** Command [id]'s prompt rows, [from] up to [to], as typed. */
    data class Title(override val key: String, val id: Int, val from: Int, val to: Int) : ShellItem

    /** Rows [from] up to [to] of a card, none for an empty one; [first] and [last] piece of it, [live] while its command runs. */
    data class Rows(override val key: String, val from: Int, val to: Int, val first: Boolean, val last: Boolean, val live: Boolean) : ShellItem

    /** Keeps the history above the screen while the commands below are shorter than it. */
    data object Gap : ShellItem {
        override val key = "gap"
    }

    /** The newest command's screen, while it's a full-screen app's. */
    data class Fullscreen(override val key: String) : ShellItem

    /** The prompt waiting for a command, as what's being written for it. */
    data object Draft : ShellItem {
        override val key = "draft"
    }
}

/**
 * What [commands] shows, oldest first; [fullscreen] while the newest runs a
 * full-screen app. A prompt nothing ran from isn't shown: the one waiting is
 * what's being written for it ([ShellItem.Draft]), and the rest were left
 * (Ctrl-C, an empty line). Repainted history doesn't say which prompts ran
 * without output, so those show, with empty cards, but for those known to
 * have had [nothing] typed at them.
 */
internal fun shellItems(commands: Commands, fullscreen: Boolean, nothing: Set<Int> = emptySet(), piece: Int = PIECE_ROWS): List<ShellItem> {
    val items = mutableListOf<ShellItem>()
    fun card(key: String, from: Int, to: Int, live: Boolean) {
        if (to <= from) {
            items += ShellItem.Rows("$key.0", from, from, first = true, last = true, live = live)
            return
        }
        var at = from
        var k = 0
        while (at < to) {
            val end = minOf(to, at + piece)
            items += ShellItem.Rows("$key.$k", at, end, first = at == from, last = end == to, live = live)
            at = end
            k++
        }
    }
    if (commands.history > 0) {
        card("h", 0, commands.history, live = false)
        items += ShellItem.Gap
    }
    val list = commands.list
    for ((i, c) in list.withIndex()) {
        val newest = i == list.lastIndex
        if (newest && !c.ran && !fullscreen) {
            items += ShellItem.Draft
            continue
        }
        if (!newest && !c.ran && (c.typed || c.id in nothing)) continue
        items += ShellItem.Title("t${c.id}", c.id, c.start, if (c.ran) c.output else c.end)
        when {
            newest && fullscreen -> items += ShellItem.Fullscreen("f${c.id}")
            c.ran -> card("o${c.id}", c.output, c.end, live = newest)
            else -> card("o${c.id}", c.end, c.end, live = false)
        }
    }
    if (fullscreen && list.isEmpty()) items += ShellItem.Fullscreen("f")
    return items
}

internal const val PIECE_ROWS = 32
