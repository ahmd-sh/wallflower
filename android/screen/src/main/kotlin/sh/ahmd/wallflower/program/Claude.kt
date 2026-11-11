package sh.ahmd.wallflower.program

import sh.ahmd.wallflower.screen.InputLine
import sh.ahmd.wallflower.screen.Prompt
import sh.ahmd.wallflower.screen.Screen

/**
 * Claude Code: its prompt box, the menus it asks with, and its own ways:
 * vim mode, /compact, /rewind, which takes its conversation back, its
 * permission modes, its transcript, and /btw's answers aside.
 */
object Claude : Program {
    override val clearKeys = listOf("C-u") // a row at a time

    override val newlineKeys = listOf("C-j") // one key: ⌥⏎ comes as Escape then Enter, which vim mode can take apart

    override val compact = "/compact"

    override val queues = true

    override val acceptSuggestion = listOf("Tab")

    override val commands = listOf(
        Command("/compact", "summarize the conversation, freeing context"),
        Command("/rewind", "go back to an earlier message"),
        Command("/clear", "start a new conversation"),
        Command("/model", "switch model"),
        Command("/btw", "ask a side question, answered apart from the conversation"),
    )

    override val keyBar = KeyDefaults(
        "claude code",
        listOf("shortcuts", "Escape", "BTab", "Up", "Down", "Enter", "1", "2", "3", "C-o", "C-c"),
        stop = listOf("C-c"), // vim mode's INSERT takes Esc to leave it, not to stop Claude
    )

    /**
     * A few, to show what a shortcut can be: keys (ctrl ones: vim mode takes
     * an alt key's Esc apart), a command run as sent, and /btw, which starts
     * what's sent, the question to ask.
     */
    override val shortcuts = listOf(
        Shortcut(listOf("C-o"), "transcript"),
        Shortcut(listOf("C-t"), "todos"),
        Shortcut(listOf("/compact", "Enter")),
        Shortcut(listOf("/btw")),
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
            val faint = ghost.trim().replace(spaces, " ")
            val suggestion = if (text.isEmpty() && faint !in PLACEHOLDERS) tried.matchEntire(faint)?.groupValues?.get(1) ?: faint else ""
            return InputLine(text, end - y, suggestion)
        }
        return null
    }

    /**
     * What Claude asks takes its prompt box's place: with the box there, a
     * numbered list on screen is something said (a message starting 1.), not
     * a menu; nor is one in its transcript, or in an answer aside.
     */
    override fun prompt(screen: Screen): Prompt? = if (line(screen) != null || aside(screen) != null) null else Prompt.read(screen)

    /**
     * Above its prompt box while it works, Claude says what it's doing: a
     * mark that spins, its word for it ("Compacting conversation", or one of
     * its whims), then how long. What it's queued, and tips, can come between.
     * An answer aside takes the box's place, and it says so above that.
     */
    override fun status(screen: Screen): String? {
        val s = screen
        val top = box(s)?.let { it - 1 } ?: answered(s) ?: return null
        for (y in top - 1 downTo maxOf(0, top - 11)) {
            if (s.text(0, y) !in SPINS || s.text(1, y).isNotBlank()) continue
            return working.find(row(s, y, 2))?.groupValues?.get(1)
        }
        return null
    }

    /**
     * Below its prompt box Claude says which permission mode it's in, after
     * vim mode's -- INSERT -- while that's on: a mark (⏵⏵, ⏸), the mode's
     * name and "on" ("⏸ plan mode on", "⏵⏵ accept edits on"), then how to
     * change it. Shift+Tab steps to the next, round the modes it's set up
     * with (here manual, accept edits, plan, auto).
     */
    override fun mode(screen: Screen): Mode? {
        val s = screen
        val box = box(s) ?: return null
        val end = (box + 1 until s.rows).firstOrNull { rule(s, it) } ?: return null
        for (y in end + 1 until s.rows) {
            val m = moded.find(row(s, y, 0)) ?: continue
            return Mode(m.groupValues[2].removeSuffix(" mode"), m.groupValues[1])
        }
        return null
    }

    override val nextMode = listOf("BTab")

    override val modes = listOf("manual", "accept edits", "plan", "auto")

    private val moded = Regex("^\\s*(?:-- [A-Z]+ --\\s+)?([\u2300-\u23ff]+)\\s+(\\p{L}[\\p{L} ]*?) on\\b")

    /**
     * Claude's own views in its prompt box's place, where keys are theirs:
     * its detailed transcript (ctrl+o), which says so below its last rule, a
     * faint one (or its keys there, after ?, or a search, after /); ctrl+o
     * leaves it from any of those. And an answer to /btw (see [answer]).
     */
    override fun aside(screen: Screen): Aside? {
        val s = screen
        if (box(s) != null) return null
        val last = (s.rows - 1 downTo 0).firstOrNull { rule(s, it) }
        if (last != null) {
            val below = (last + 1 until s.rows).map { row(s, it, 0).trim() }.filter { it.isNotEmpty() }
            val first = below.firstOrNull().orEmpty()
            val faint = (0 until s.cols).count { s.faint(it, last) } >= s.cols / 2
            if (first.startsWith("Showing detailed transcript") || below.any { "ctrl+o toggle" in it } || faint && first.startsWith("/")) {
                return Aside.View("transcript", listOf("C-o"))
            }
        }
        return answer(s)
    }

    /**
     * /btw's answer: under a rule of ▔, the questions asked aside so far, a
     * row each (/btw …), the one answered below in bold; a blank row, then
     * the answer, indented past them (✽ Answering… while it comes), wrapped
     * eight columns short of the screen's edge; a blank row, and grey hints,
     * which say Esc closes it. Taller than the screen has room for, it takes
     * all but the top seven rows, and the arrows move it three rows at a time.
     */
    private fun answer(s: Screen): Aside.Answer? {
        val top = answered(s) ?: return null
        val asked = (top + 1 until s.rows).firstOrNull { row(s, it, 0).isNotBlank() } ?: return null
        val questions = (asked until s.rows).takeWhile { y -> btw.matches(row(s, y, 0)) }
        if (questions.isEmpty()) return null
        val shown = questions.firstOrNull { y -> !s.muted(4, y) } ?: questions.last()
        val foot = (s.rows - 1 downTo 0).firstOrNull { row(s, it, 0).isNotBlank() } ?: return null
        var hint = foot
        while (hint - 1 > questions.last() && indent(row(s, hint - 1, 0)) == 4 && s.muted(4, hint - 1)) hint--
        if (indent(row(s, hint, 0)) != 4 || !s.muted(4, hint)) return null
        val rows = (questions.last() + 2 until hint - 1).map { row(s, it, 0).drop(ANSWER_INDENT).trimEnd() }
        val answering = rows.singleOrNull()?.let { it.firstOrNull()?.toString() in SPINS && it.endsWith("Answering…") } == true
        return Aside.Answer(
            question = btw.matchEntire(row(s, shown, 0))!!.groupValues[1].trim(),
            rows = if (answering) null else rows,
            width = s.cols - 8,
            taller = top <= ABOVE_TALLEST,
            leave = listOf("Escape"),
            up = listOf("Up"),
            down = listOf("Down"),
            step = 3,
        )
    }

    /** The ▔ rule over an answer aside, the bottom-most on screen, with a question asked aside below it (fullscreen mode rules its menus with ▔ too). */
    private fun answered(s: Screen): Int? {
        val top = (s.rows - 1 downTo 0).firstOrNull { y -> s.text(0, y) == "▔" && (0 until s.cols).count { s.text(it, y) == "▔" } >= s.cols / 2 } ?: return null
        val asked = (top + 1 until s.rows).firstOrNull { row(s, it, 0).isNotBlank() } ?: return null
        return top.takeIf { btw.matches(row(s, asked, 0)) }
    }

    private val btw = Regex("^ {4}/btw(?: (.*))?$")

    /** How many of the screen's rows an answer aside leaves above it at most, to the conversation. */
    private const val ABOVE_TALLEST = 7

    /** Where an answer aside's rows start. */
    private const val ANSWER_INDENT = 6

    private fun indent(row: String) = row.length - row.trimStart().length

    /** The first row of Claude's prompt box, if it's on screen. */
    private fun box(s: Screen): Int? = (s.rows - 2 downTo 1).firstOrNull { y ->
        (s.text(0, y) == "❯" || s.text(0, y) == "!") && s.text(1, y).isBlank() && rule(s, y - 1)
    }

    /** Row [y]'s text from column [from], a blank for each empty cell. */
    private fun row(s: Screen, y: Int, from: Int): String {
        val row = StringBuilder()
        for (x in from until s.cols) if (!s.spacer(x, y)) row.append(s.text(x, y).ifEmpty { " " })
        return row.toString()
    }

    /** The marks Claude's working line spins through. */
    private val SPINS = setOf("·", "✢", "✳", "✶", "✻", "✽", "*")

    private val working = Regex("^(\\p{L}[\\p{L} '’-]*)…")

    /** Faint words in an empty box that aren't a suggestion to take. */
    private val PLACEHOLDERS = setOf("Press up to edit queued messages")

    /** Claude's command is its version; its title is the conversation's name, after a mark that spins while it works. */
    override fun label(title: String): String? = title.trimStart { !it.isLetterOrDigit() }.trim().ifEmpty { null }

    override val readsScreenToNotify = true

    /**
     * Claude notifies of whatever it asks (a permission, a question, a plan,
     * a server's form) once it's gone a few seconds unanswered, and of more
     * besides (done and waiting, an agent finished, a push), each its own
     * way, or with only the bell, as it's set to. What it asks always takes
     * its prompt box's place: so one notice while the box is gone, or the
     * screen couldn't be read; none while it's there, or a view of Claude's
     * own is in its place, as that's asking nothing.
     */
    override fun notice(title: String?, body: String?, screen: Screen?): Notice? =
        if (screen == null || line(screen) == null && aside(screen) == null) Notice("Claude Code", "Claude needs your input") else null

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

    /**
     * /rewind lists the user's messages, its cursor on (current) below the
     * latest, a few at a time, with how many more there are above and below
     * (↑ / ↓ N more): up goes to the one before, and Enter asks to confirm,
     * quoting the message, which is the user's to answer; Esc goes back to
     * the list. (Arrows that come in a burst, it takes for a scroll wheel,
     * and says so over the list's rule for a while, so the list is walked
     * by the confirmations it asks.)
     */
    override fun walk(prompt: Prompt, said: String): Walk? {
        if (prompt.quote.isNotEmpty()) {
            if (prompt.options.none { it.label in RESTORES }) return null
            return Walk.Picked(quotes(prompt.quote.filterNot(Char::isWhitespace), said.filterNot(Char::isWhitespace)), back = listOf("Escape"))
        }
        if (prompt.lines.firstOrNull()?.text != "Rewind") return null
        val shown = prompt.options.filter { it.scroll == 0 }
        val at = shown.indexOfFirst { it.current }.takeIf { it >= 0 } ?: return null
        fun more(way: Int) = prompt.options.firstOrNull { it.scroll == way }?.let { count.find(it.label)?.value?.toInt() } ?: 0
        val back = more(1) + shown.size - 1 - at - 1 // past (current), at the foot
        return Walk.Listed(back, last = back + at + more(-1), up = listOf("Up"), down = listOf("Down"), pick = listOf("Enter"))
    }

    private val count = Regex("\\d+")

    /** Restoring the conversation from /rewind takes it back to before the message the prompt quotes. */
    override fun picked(prompt: Prompt, option: Prompt.Option): Effect? {
        if (option.label !in RESTORES) return null
        val quote = prompt.quote.filterNot(Char::isWhitespace)
        if (quote.isEmpty()) return null
        return Effect.Rewound { said -> quotes(quote, said.filterNot(Char::isWhitespace)) }
    }

    private val RESTORES = setOf("Restore conversation", "Restore code and conversation")

    /** Restored, wherever it was picked, the message Claude quoted is back in its box. */
    override fun settled(prompt: Prompt, line: InputLine?): Effect? {
        val restore = prompt.options.firstOrNull { it.label in RESTORES } ?: return null
        val quote = prompt.quote.filterNot(Char::isWhitespace)
        val text = line?.text?.filterNot(Char::isWhitespace).orEmpty()
        if (quote.isEmpty() || text.isEmpty() || !quotes(quote, text)) return null
        return picked(prompt, restore)
    }

    /** Whether [message] is what Claude quotes as [quote]: its words from the start, to the end unless cut short with …. */
    private fun quotes(quote: String, message: String): Boolean {
        val parts = quote.split('…')
        if (!message.startsWith(parts.first())) return false
        var at = parts.first().length
        for (part in parts.drop(1)) {
            val i = message.indexOf(part, at)
            if (i < 0) return false
            at = i + part.length
        }
        return quote.endsWith('…') || message.endsWith(parts.last()) && (parts.size > 1 || message.length == quote.length)
    }

    private val spaces = Regex(" {2,}")
    private val tried = Regex("^Try \"(.+)\"$")

    private fun rule(s: Screen, y: Int) =
        s.text(0, y) == "─" && (0 until s.cols).count { s.text(it, y) == "─" } >= s.cols / 2
}
