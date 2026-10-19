package sh.ahmd.wallflower.read

import sh.ahmd.wallflower.terminal.Commands

/**
 * A shell pane's read view, read off its terminal: each command a title
 * (what was typed) and its output in a card, laid out as the terminal has
 * it. Above them, what came before the first command the phone saw marked,
 * in one card kept above the screen until scrolled to ([Gap]). Long output
 * comes in pieces of rows, so it scrolls by without being drawn all at once.
 */
internal sealed interface ShellItem {
    val key: String

    /** A command's prompt rows, [from] up to [to], as typed. */
    data class Title(override val key: String, val from: Int, val to: Int) : ShellItem

    /** Rows [from] up to [to] of a card; [first] and [last] piece of it, [live] while its command runs. */
    data class Rows(override val key: String, val from: Int, val to: Int, val first: Boolean, val last: Boolean, val live: Boolean) : ShellItem

    /** Keeps the history above the screen while the commands below are shorter than it. */
    data object Gap : ShellItem {
        override val key = "gap"
    }

    /** The newest command's screen, while it's a full-screen app's. */
    data class Fullscreen(override val key: String) : ShellItem
}

/**
 * What [commands] shows, oldest first; [fullscreen] while the newest runs a
 * full-screen app. A prompt nothing ran from isn't shown: the one waiting
 * has its line in the composer, and the rest were left (Ctrl-C, an empty
 * line). Repainted history doesn't say which prompts ran without output, so
 * those all show.
 */
internal fun shellItems(commands: Commands, fullscreen: Boolean, piece: Int = PIECE_ROWS): List<ShellItem> {
    val items = mutableListOf<ShellItem>()
    fun card(key: String, from: Int, to: Int, live: Boolean) {
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
        if (!c.ran && (if (newest) !fullscreen else c.typed)) continue
        items += ShellItem.Title("t${c.id}", c.start, if (c.ran) c.output else c.end)
        when {
            newest && fullscreen -> items += ShellItem.Fullscreen("f${c.id}")
            c.ran -> card("o${c.id}", c.output, c.end, live = newest)
        }
    }
    if (fullscreen && list.isEmpty()) items += ShellItem.Fullscreen("f")
    return items
}

internal const val PIECE_ROWS = 32
