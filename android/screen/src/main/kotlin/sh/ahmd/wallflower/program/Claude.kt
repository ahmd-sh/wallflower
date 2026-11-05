package sh.ahmd.wallflower.program

import sh.ahmd.wallflower.screen.InputLine
import sh.ahmd.wallflower.screen.Prompt
import sh.ahmd.wallflower.screen.Screen

/**
 * Claude Code: its prompt box, the menus it asks with, and its own ways:
 * vim mode, /compact, and /rewind, which takes its conversation back.
 */
object Claude : Program {
    override val clearKeys = listOf("C-u") // a row at a time

    override val newlineKeys = listOf("C-j") // one key: ⌥⏎ comes as Escape then Enter, which vim mode can take apart

    override val compact = "/compact"

    override val acceptSuggestion = listOf("Tab")

    override val commands = listOf(
        Command("/compact", "summarize the conversation, freeing context"),
        Command("/rewind", "go back to an earlier message"),
        Command("/clear", "start a new conversation"),
        Command("/model", "switch model"),
    )

    override val keyBar = KeyDefaults(
        "claude code",
        listOf("Escape", "BTab", "Up", "Down", "Enter", "1", "2", "3", "C-o", "C-c"),
        stop = listOf("C-c"), // vim mode's INSERT takes Esc to leave it, not to stop Claude
    )

    /**
     * The prompt box: between two rules, a first row starting `❯ ` (or `! `
     * in shell mode) and rows indented to match. Claude wraps long lines
     * itself, at words, two columns short of the screen's edge (and a
     * column shorter for the cursor after the last word), so a row that
     * couldn't fit the next one's first word was wrapped rather than ended;
     * a word too long for a row is broken at its end.
     * Faint text is the placeholder or a suggestion, not input: in an empty
     * box, it's the suggestion (the placeholder's `Try "…"` suggests what's
     * quoted), cut short with … if it's wider than the box.
     */
    override fun line(screen: Screen): InputLine? {
        val s = screen
        for (y in s.rows - 2 downTo 1) {
            val mark = s.text(0, y)
            if ((mark != "❯" && mark != "!") || s.text(1, y).isNotBlank() || !rule(s, y - 1)) continue
            val end = (y + 1 until s.rows).firstOrNull { rule(s, it) } ?: continue
            val width = s.cols - 4
            val out = StringBuilder(if (mark == "!") "!" else "")
            val ghost = StringBuilder()
            var previous: String? = null
            for (r in y until end) {
                val row = StringBuilder()
                for (x in 2 until s.cols) {
                    if (s.spacer(x, r)) continue
                    val c = s.text(x, r)
                    // Blanks go with both: apps draw the spaces between faint words plain.
                    if (c.isBlank()) {
                        row.append(' ')
                        ghost.append(' ')
                    } else {
                        (if (s.faint(x, r)) ghost else row).append(c)
                    }
                }
                ghost.append(' ')
                val line = row.trimEnd().toString()
                if (previous != null) {
                    val word = line.substringBefore(' ').length
                    val cursor = if (r == end - 1 && word == line.length) 1 else 0
                    out.append(
                        when {
                            // A word too long for any row (a path, say) is broken where the row ends.
                            previous.length >= width && previous.substringAfterLast(' ').length + word > width -> ""
                            line.isNotEmpty() && previous.length + 1 + word + cursor > width -> " "
                            else -> "\n"
                        },
                    )
                }
                out.append(line)
                previous = line
            }
            val text = out.toString().trimEnd()
            val suggestion = if (text.isEmpty()) ghost.trim().replace(spaces, " ").let { tried.matchEntire(it)?.groupValues?.get(1) ?: it } else ""
            return InputLine(text, end - y, suggestion)
        }
        return null
    }

    override fun prompt(screen: Screen): Prompt? = Prompt.read(screen)

    override val readsScreenToNotify = true

    /**
     * Claude notifies of whatever it asks (a permission, a question, a plan,
     * a server's form) once it's gone a few seconds unanswered, and of more
     * besides (done and waiting, an agent finished, a push), each its own
     * way, or with only the bell, as it's set to. What it asks always takes
     * its prompt box's place: so one notice while the box is gone, or the
     * screen couldn't be read; none while it's there.
     */
    override fun notice(title: String?, body: String?, screen: Screen?): Notice? =
        if (screen == null || line(screen) == null) Notice("Claude Code", "Claude needs your input") else null

    /**
     * Claude runs in vim mode, and where it can't be seen it may be in
     * NORMAL (after an Esc), where keys are commands: A first goes back to
     * INSERT at the row's end. In INSERT it's typed, and the first ^U takes it.
     */
    override fun clear(line: InputLine, keys: List<String>, unseen: Boolean): List<String> =
        (if (unseen && keys == clearKeys) listOf("A") else emptyList()) + super.clear(line, keys, unseen)

    /**
     * Stopped before it replies, Claude puts the message back in its box. Any
     * message comes back by a /rewind to before it, picked from Claude's list.
     */
    override fun recall(latest: Boolean, working: Boolean): Recall =
        if (latest && working) Recall.Keys(listOf("C-c")) else Recall.Command("/rewind")

    /** Restoring the conversation from /rewind takes it back to before the message the prompt quotes. */
    override fun picked(prompt: Prompt, option: Prompt.Option): Effect? {
        if (option.label !in RESTORES) return null
        val quote = prompt.quote.filterNot(Char::isWhitespace)
        if (quote.isEmpty()) return null
        return Effect.Rewound { said -> quotes(quote, said.filterNot(Char::isWhitespace)) }
    }

    private val RESTORES = setOf("Restore conversation", "Restore code and conversation")

    /** Whether [message] is what Claude quotes as [quote]: its words, cut short with …. */
    private fun quotes(quote: String, message: String): Boolean {
        var at = 0
        for (part in quote.split('…')) {
            val i = message.indexOf(part, at)
            if (i < 0) return false
            at = i + part.length
        }
        return true
    }

    private val spaces = Regex(" {2,}")
    private val tried = Regex("^Try \"(.+)\"$")

    private fun rule(s: Screen, y: Int) =
        s.text(0, y) == "─" && (0 until s.cols).count { s.text(it, y) == "─" } >= s.cols / 2
}
