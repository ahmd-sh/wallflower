package sh.ahmd.wallflower.program

import sh.ahmd.wallflower.screen.InputLine
import sh.ahmd.wallflower.screen.Prompt
import sh.ahmd.wallflower.screen.Screen

/**
 * OpenAI's Codex CLI: its composer, the approvals and questions it asks
 * with, and what it says it's doing. What's sent while it works goes into
 * the turn at once (Tab would queue it instead), and Esc stops it; ^C quits.
 */
object Codex : Program {
    override val clearKeys = listOf("C-u") // a row at a time

    override val newlineKeys = listOf("C-j")

    override val compact = "/compact"

    override val commands = listOf(
        Command("/model", "switch model and reasoning effort"),
        Command("/new", "start a new conversation"),
        Command("/resume", "go back to an earlier conversation"),
        Command("/compact", "summarize the conversation, freeing context"),
        Command("/diff", "what's changed, untracked files too"),
        Command("/review", "review the changes for issues"),
        Command("/plan", "switch to plan mode"),
        Command("/status", "the session's settings and tokens used"),
    )

    override val keyBar = KeyDefaults(
        "codex",
        listOf("Escape", "ctrl", "S-Left", "BTab", "arrows", "Tab", "1", "2", "3"),
        stop = listOf("Escape"),
    )

    /**
     * Its keys (← and its agents, and Esc Esc and its rewind, from an empty
     * composer), then its commands, run as sent; /side starts what's sent,
     * the question to ask aside.
     */
    override val shortcuts = listOf(
        Shortcut(listOf("BTab"), "mode"),
        Shortcut(listOf("F4"), "activity"),
        Shortcut(listOf("S-Left"), "answer questions"),
        Shortcut(listOf("Left"), "agents"),
        Shortcut(listOf("Escape", "Escape"), "rewind"),
        Shortcut(listOf("M-,"), "less reasoning"),
        Shortcut(listOf("M-."), "more reasoning"),
    ) + listOf("/model", "/new", "/resume", "/compact", "/diff", "/review", "/status", "/plan", "/recap", "/agents")
        .map { Shortcut(listOf(it, "Enter")) } + Shortcut(listOf("/side"))

    /**
     * The composer: a first row starting `› `, rows under it indented two,
     * then a blank row and the footer, `  <model> <mode> · <directory>…`,
     * at the foot of the screen; history's messages start `› ` too, but
     * nothing like that comes after them. Codex wraps long lines itself, at
     * words, three columns short of the screen's edge, and breaks a word too
     * long for a row where the row ends. Faint text is the placeholder.
     */
    override fun line(screen: Screen): InputLine? {
        val s = screen
        val first = composer(s) ?: return null
        var end = first + 1
        while (end < s.rows && s.text(0, end).isBlank() && s.text(1, end).isBlank() && text(s, end, 2).isNotBlank()) end++
        val width = s.cols - 3
        val out = StringBuilder()
        var previous: String? = null
        for (r in first until end) {
            val row = StringBuilder()
            for (x in 2 until s.cols) {
                if (s.spacer(x, r)) continue
                val c = s.text(x, r)
                row.append(if (c.isBlank()) " " else if (s.faint(x, r)) "" else c)
            }
            val line = row.trimEnd().toString()
            if (previous != null) {
                val word = line.substringBefore(' ').length
                out.append(
                    when {
                        previous.length >= width && previous.substringAfterLast(' ').length + word > width -> ""
                        line.isNotEmpty() && previous.length + 1 + word > width -> " "
                        else -> "\n"
                    },
                )
            }
            out.append(line)
            previous = line
        }
        return InputLine(out.toString().trim(), end - first)
    }

    /** The composer's first row, if it's on screen: the lowest `› ` row with the footer below it, past a blank row. */
    private fun composer(s: Screen): Int? = foot(s)?.first

    /** The composer's first row and its footer's. */
    private fun foot(s: Screen): Pair<Int, Int>? {
        for (footer in s.rows - 1 downTo maxOf(2, s.rows - 3)) {
            if (s.text(0, footer).isNotBlank() || s.text(1, footer).isNotBlank() || !text(s, footer, 2).contains(" · ")) continue
            if (text(s, footer - 1, 0).isNotBlank()) continue
            var y = footer - 2
            while (y >= 0 && s.text(0, y).isBlank() && s.text(1, y).isBlank() && text(s, y, 2).isNotBlank()) y--
            if (y >= 0 && s.text(0, y) == "›" && s.text(1, y).isBlank() && !option.matches(text(s, y, 0))) return y to footer
        }
        return null
    }

    /** In plan mode, Codex says so at its footer's end ("Plan mode"); in its default mode, nothing does. Shift+tab goes between them. */
    override fun mode(screen: Screen): Mode? {
        val (_, footer) = foot(screen) ?: return null
        return Mode(if (planning.containsMatchIn(text(screen, footer, 2))) "plan" else "default", "")
    }

    override val nextMode = listOf("BTab")

    override val modes = listOf("default", "plan")

    private val planning = Regex("""\sPlan mode$""")

    private val option = Regex("""^›\s+\d{1,2}\.\s.*""")

    /** What it asks takes the composer's place: an approval, or the picker for its questions. */
    override fun prompt(screen: Screen): Prompt? = if (composer(screen) != null) null else Prompt.read(screen)

    /**
     * While it works, Codex says so on a row of its own above the composer
     * (or its questions): a mark, its word, then how long and how to stop
     * it ("• Working (7s • esc to interrupt) · 1 background terminal…").
     */
    override fun status(screen: Screen): String? {
        val s = screen
        for (y in s.rows - 1 downTo maxOf(0, s.rows - 24)) {
            if (s.text(0, y) != "•" || s.text(1, y).isNotBlank()) continue
            working.find(text(s, y, 2))?.let { return it.groupValues[1] }
        }
        return null
    }

    private val working = Regex("""^(\p{L}[\p{L} '’-]*?) \((?:\d+[hms] ?)+• \S+ to interrupt\)""")

    /**
     * The questions Codex asks while it works wait below its working row,
     * "? 2 questions", until the user brings them up (⇧←) to answer, while
     * it carries on.
     */
    override fun asked(screen: Screen): Asked? {
        val s = screen
        if (composer(s) == null) return null
        for (y in s.rows - 1 downTo maxOf(0, s.rows - 16)) {
            val m = questions.find(text(s, y, 0)) ?: continue
            return Asked(m.groupValues[1], listOf("S-Left"))
        }
        return null
    }

    private val questions = Regex("""^\s+\?\s+(\d+ questions?)\b""")

    /** Codex's title is the conversation's name and the project's, after what it's doing; its name alone is the project's, until it names it. */
    override fun label(title: String): String? {
        val parts = title.replace(needs, "").trimStart { it.isWhitespace() || it in '⠀'..'⣿' }.split(" | ")
        return if (parts.size < 2) null else parts.dropLast(1).joinToString(" | ").trim().ifEmpty { null }
    }

    private val needs = Regex("""^\s*\[[^\]]*]\s*Action Required(\s*\|\s*|$)""")

    private fun text(s: Screen, y: Int, from: Int): String {
        val b = StringBuilder()
        for (x in from until s.cols) if (!s.spacer(x, y)) b.append(s.text(x, y).ifEmpty { " " })
        return b.toString().trimEnd()
    }
}
