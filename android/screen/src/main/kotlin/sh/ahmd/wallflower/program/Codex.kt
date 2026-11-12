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
        listOf("shortcuts", "Escape", "S-Left", "BTab", "arrows", "Tab", "1", "2", "3"),
        stop = listOf("Escape"),
    )

    /**
     * A few, to show what a shortcut can be: keys (← from its empty
     * composer brings up its agents), commands run as sent, and /side,
     * which starts what's sent, the question to ask aside.
     */
    override val shortcuts = listOf(
        Shortcut(listOf("Left"), "agents"),
        Shortcut(listOf("F4"), "activity"),
        Shortcut(listOf("/model", "Enter")),
        Shortcut(listOf("/diff", "Enter")),
        Shortcut(listOf("/side")),
    )

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
        val name = if (planning.containsMatchIn(text(screen, footer, 2))) "plan" else "default"
        return Mode(name, "", hue(name))
    }

    override val nextMode = listOf("BTab")

    override val modes = listOf("default", "plan")

    override fun hue(mode: String) = if (mode == "plan") Hue.Aqua else Hue.Grey

    private val planning = Regex("""\sPlan mode$""")

    private val option = Regex("""^›\s+\d{1,2}\.\s.*""")

    /**
     * What it asks takes the composer's place: an approval, or the picker for
     * its questions. No rule opens it: the conversation's rows come right
     * before, each its own • item (↪ for an answer), and aren't what it asks.
     * Asked whether to trust a folder, a number doesn't answer: 1 only moves
     * to trust, which Enter confirms, and 2 leaves at once.
     */
    override fun prompt(screen: Screen): Prompt? {
        if (composer(screen) != null || center(screen) != null) return null
        val p = Prompt.read(screen) ?: return null
        val from = p.lines.indexOfLast { it.text.trimStart().let { t -> t.startsWith("•") || t.startsWith("↪") } } + 1
        val lines = p.lines.drop(from).dropWhile { it.text.isBlank() }
        return p.copy(lines = lines, numbers = if (lines.any { trust.containsMatchIn(it.text) }) Prompt.Numbers.Ignored else p.numbers)
    }

    private val trust = Regex("""(?i)\btrust\b""")

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

    /**
     * Its agent command center (← from an empty composer), its own screen:
     * a heading, the filters, a rule, then its tasks under " Tasks", in
     * groups (faint: "~/dev/wallflower  24", "Pinned  1"), each task its
     * mark and name, the cursor's row "› " and lit; "↑" and "↓" where more
     * is out of sight; its keys at the foot. A filter's count is its own;
     * the current one isn't faint, and "‹" or "›" stand for more of them.
     * Of the marks, ! needs the user and ○ is nothing new; ● is working
     * (green) or needs the user or is new since it was looked at (blue),
     * told apart by the filter, or by nothing working or needing the user.
     * Its keys (?) show over the filters, in the tasks' place.
     */
    override fun aside(screen: Screen): Aside? {
        val s = screen
        val top = center(s) ?: return null
        val filters = filters(s, top + 1)
        var foot = s.rows - 1
        while (foot > top && text(s, foot, 0).isBlank()) foot--
        if (!keys.containsMatchIn(text(s, foot, 0))) foot++
        val current = filters.firstOrNull { it.current }?.name
        val working = filters.firstOrNull { it.name == "Working" }?.count
        val needing = filters.firstOrNull { it.name == "Needs you" }?.count
        // Its keys, over the filters: a view of its own on its own.
        if (text(s, top + 3, 0).trim() == "Task shortcuts") return Aside.View("agents' keys", listOf("Escape"))
        val groups = ArrayList<Aside.Tasks.Group>()
        var name: String? = null
        var count = ""
        var tasks = ArrayList<Aside.Tasks.Task>()
        var above = false
        var below = false
        var says: String? = null
        fun close() {
            if (name != null || tasks.isNotEmpty()) groups += Aside.Tasks.Group(name.orEmpty(), count, tasks)
            name = null
            count = ""
            tasks = ArrayList()
        }
        for (y in top + 3 until foot) {
            val row = text(s, y, 0)
            if (row.isBlank()) continue
            val at = row.indexOfFirst { !it.isWhitespace() }
            val rest = row.substring(at)
            when {
                rest.startsWith("↑") -> above = true
                rest.startsWith("↓") -> below = true
                s.faint(at, y) && at <= 2 && heading.matches(rest) -> {
                    close()
                    val parts = rest.split(Regex("""\s{2,}"""), limit = 2)
                    name = parts[0]
                    count = parts[1]
                }
                s.faint(at, y) && at > 2 -> Unit // " Tasks"
                else -> task.find(row)?.let { m ->
                    val mark = when (m.groupValues[2]) {
                        "!" -> Aside.Tasks.Mark.NeedsYou
                        "○" -> Aside.Tasks.Mark.Idle
                        else -> when {
                            current == "Working" -> Aside.Tasks.Mark.Working
                            current == "Needs you" -> Aside.Tasks.Mark.NeedsYou
                            working == 0 && needing == 0 -> Aside.Tasks.Mark.Updated
                            else -> Aside.Tasks.Mark.Lit
                        }
                    }
                    tasks += Aside.Tasks.Task(m.groupValues[3], mark, m.groupValues[1].isNotEmpty())
                } ?: run { if (groups.isEmpty() && tasks.isEmpty()) says = rest }
            }
        }
        close()
        return Aside.Tasks(
            named = "agents",
            filters = filters,
            groups = groups,
            above = above,
            below = below,
            says = says.takeIf { groups.isEmpty() },
            leave = listOf("Escape"),
            up = listOf("Up"),
            down = listOf("Down"),
            open = listOf("Enter"),
            next = listOf("Tab"),
            previous = listOf("BTab"),
            pageUp = listOf("PageUp"),
            pageDown = listOf("PageDown"),
            new = listOf("n"),
        )
    }

    /** The agent command center's heading row, near the top, if it's up. */
    private fun center(s: Screen): Int? = (0 until minOf(3, s.rows - 3)).firstOrNull { text(s, it, 0).trimStart().startsWith("Agent command center") }

    /** The filters on row [y]: each a name, then how many, apart from the next; the one current isn't faint. */
    private fun filters(s: Screen, y: Int): List<Aside.Tasks.Filter> {
        val row = StringBuilder()
        val at = ArrayList<Int>()
        for (x in 0 until s.cols) {
            if (s.spacer(x, y)) continue
            val t = s.text(x, y).ifEmpty { " " }
            row.append(t)
            repeat(t.length) { at += x }
        }
        return filter.findAll(row).map { m -> Aside.Tasks.Filter(m.groupValues[1], m.groupValues[2].toInt(), !s.faint(at[m.range.first], y)) }.toList()
    }

    private val filter = Regex("""(\p{L}[\p{L} ]*?) (\d+)(?= |$)""")

    private val task = Regex("""^\s*(›)?\s*([^\p{L}\p{N}\s])\s(.+?)\s*$""")

    private val keys = Regex("""\besc back\b""")

    private val heading = Regex("""\S.*?\s{2,}\d.*""")

    private fun text(s: Screen, y: Int, from: Int): String {
        val b = StringBuilder()
        for (x in from until s.cols) if (!s.spacer(x, y)) b.append(s.text(x, y).ifEmpty { " " })
        return b.toString().trimEnd()
    }
}
