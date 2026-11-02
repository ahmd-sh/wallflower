package sh.ahmd.wallflower.screen

/**
 * Choices a program offers on its screen, to answer by touch: a numbered
 * menu with a cursor (❯ or ›) on one option, as CLIs ask for permission,
 * answers and approvals. The rows above it, up to the rule that opens its
 * box, say what it's about; boxed rows (between ╌ rules) are code. A row of
 * ☐ ☒ ✔ chips steps between questions. Rows beneath an option continue it
 * when its last word couldn't fit the row before (the program wrapped it)
 * and they're in its color; others describe it. The bottom-most menu on the
 * screen is the live one.
 *
 * Or a list picked from with the cursor alone, as Claude Code's /rewind:
 * below its rule what it's about, then options indented past the cursor's
 * ❯, each a row, what the rows under it say, and a blank row; ↑ / ↓ N more
 * say it scrolls. Its foot ("Enter to continue · Esc to cancel") may be
 * past the bottom of a short pane. [quote] is what either quotes (│ rows),
 * a message it's about.
 *
 * A menu can have a box beside it previewing the option under the cursor,
 * as Claude Code's questions do: the menu is what's left of it, and a
 * number only moves the cursor ([previewed]).
 */
data class Prompt(
    val lines: List<Line>,
    val options: List<Option>,
    val tabs: List<Tab>,
    val quote: String = "",
    val previewed: Boolean = false,
) {
    data class Line(val text: String, val code: Boolean)

    data class Option(
        /** As shown; null for an item without one, like a multiple choice's Next. */
        val number: Int?,
        val label: String,
        val detail: String,
        /** A multiple choice's box; null if it has none. */
        val checked: Boolean?,
        /** The cursor's on it. */
        val current: Boolean,
        /** Drawn in grey, as a lesser option is (Type something). */
        val muted: Boolean,
        /** Not an option but more of them, above (-1) or below (1) those shown. */
        val scroll: Int = 0,
    )

    /** A question's chip, with its mark (☐ ☒ ✔) as shown. */
    data class Tab(val label: String, val current: Boolean)

    /** Whether [other] asks the same as this, wherever its cursor is. */
    fun same(other: Prompt): Boolean =
        lines == other.lines && quote == other.quote && options.map { it.label } == other.options.map { it.label } && tabs.map { it.label } == other.tabs.map { it.label }

    /**
     * Keys that pick [option]: its number, which these menus take at once
     * (a multiple choice's toggles) unless [previewed], or the cursor moved
     * there and Enter.
     */
    fun keys(option: Option): List<String> {
        val n = option.number
        if (n != null && n in 1..9) return if (previewed) listOf(n.toString(), "Enter") else listOf(n.toString())
        val from = options.indexOfFirst { it.current }.coerceAtLeast(0)
        if (option.scroll != 0) {
            // One past the last shown that way, which scrolls the list.
            val shown = options.indices.filter { options[it].scroll == 0 }
            val edge = if (option.scroll < 0) shown.first() else shown.last()
            return List(kotlin.math.abs(edge - from) + 1) { if (option.scroll < 0) "Up" else "Down" }
        }
        val to = options.indexOf(option)
        return List(kotlin.math.abs(to - from)) { if (to > from) "Down" else "Up" } + "Enter"
    }

    /** Keys that move to [tab]'s question. */
    fun keys(tab: Tab): List<String> {
        val from = tabs.indexOfFirst { it.current }.coerceAtLeast(0)
        val to = tabs.indexOf(tab)
        return List(kotlin.math.abs(to - from)) { if (to > from) "Right" else "Left" }
    }

    companion object {
        private val option = Regex("""^(\s*)([❯›▶]\s*|[↑↓]\s*)?(\d{1,2})\.\s+(.*\S)\s*$""")
        private val more = Regex("""^([↑↓])\s*\d+ more$""")
        private val marker = Regex("""^\s*[❯›▶]\s""")
        private val ago = Regex("""^\(.*\bago\)$""")
        private val box = Regex("""^\[(.)]\s+(.*)$""")
        private val chips = Regex("""([☐☒✔☑✓])\s*([^☐☒✔☑✓←→]*)""")
        private val bar = Regex("""^\s*│(.*)$""")
        private const val CONTEXT = 24 // rows above the options at most
        private const val EDGE = 2

        fun read(s: Screen): Prompt? {
            val rows = List(s.rows) { Row.of(s, it) }
            for (y in s.rows - 1 downTo 0) {
                val m = option.matchEntire(rows[y].text) ?: continue
                if (m.groupValues[3] != "1") continue
                block(rows, y)?.let { return it }
            }
            return picker(rows)
        }

        private fun cursor(m: MatchResult) = m.groups[2]?.value?.trim() in setOf("❯", "›", "▶")

        /** A row's text, and the cell under each of its characters, with what the cells are. */
        private class Row(val text: String, private val cells: IntArray, private val muted: BooleanArray, private val lit: BooleanArray) {
            fun highlighted(i: Int) = i < text.length && text[i] != ' ' && lit[cells[i]]

            /** Most of its characters from [from] (up to [to]) are grey. */
            fun mutedFrom(from: Int, to: Int = text.length): Boolean {
                var grey = 0
                var all = 0
                for (i in from until to) {
                    if (text[i].isWhitespace()) continue
                    all++
                    if (muted[cells[i]]) grey++
                }
                return all > 0 && grey * 2 > all
            }

            /** Where grey words start that run to its end, after words from [from] that aren't: a description beside a label. */
            fun greyTail(from: Int): Int? {
                var at: Int? = null
                for (i in text.length - 1 downTo from) {
                    if (text[i].isWhitespace()) continue
                    if (!muted[cells[i]]) break
                    at = i
                }
                return at?.takeIf { a -> (from until a).any { !text[it].isWhitespace() && !muted[cells[it]] } }
            }

            /** The character drawn from cell [x], if one starts there. */
            fun at(x: Int): Char? = text.indices.firstOrNull { cells[it] == x }?.let { text[it] }

            /** The cell [i]'s character is drawn from. */
            fun cell(i: Int) = cells[i]

            /** What's left of cell [x]. */
            fun before(x: Int) = Row(text.substring(0, text.indices.firstOrNull { cells[it] >= x } ?: text.length), cells, muted, lit)

            companion object {
                fun of(s: Screen, y: Int): Row {
                    val text = StringBuilder(s.cols)
                    var cells = IntArray(s.cols)
                    for (x in 0 until s.cols) {
                        if (s.spacer(x, y)) continue
                        for (c in s.text(x, y).ifEmpty { " " }) {
                            if (text.length == cells.size) cells = cells.copyOf(cells.size * 2)
                            cells[text.length] = x
                            text.append(c)
                        }
                    }
                    return Row(text.toString(), cells, BooleanArray(s.cols) { s.muted(it, y) }, BooleanArray(s.cols) { s.highlighted(it, y) })
                }
            }
        }

        /** The menu whose first option is on row [top], if it is one. */
        private fun block(screen: List<Row>, top: Int): Prompt? {
            val beside = preview(screen, top)
            val rows = if (beside == null) screen else screen.mapIndexed { y, row -> if (y >= top) row.before(beside) else row }
            val first = option.matchEntire(rows[top].text)!!
            val numberAt = first.groups[3]!!.range.first
            val options = mutableListOf<Option>()
            var labelAt = 0
            var labelMuted = false
            var next = 1
            var y = top
            while (y < rows.size) {
                val row = rows[y].text
                val m = option.matchEntire(row)
                when {
                    m != null && m.groupValues[3] == next.toString() && m.groups[3]!!.range.first == numberAt -> {
                        var label = m.groupValues[4]
                        labelAt = m.groups[4]!!.range.first
                        var checked: Boolean? = null
                        box.matchEntire(label)?.let { b ->
                            checked = b.groupValues[1].isNotBlank()
                            labelAt += label.length - b.groupValues[2].length
                            label = b.groupValues[2]
                        }
                        // A description can follow the label on its row, in grey after a ·.
                        var detail = ""
                        rows[y].greyTail(labelAt)?.let { at ->
                            detail = row.substring(at).trim().removePrefix("·").trim()
                            label = row.substring(labelAt, at).trim().removeSuffix("·").trim()
                        }
                        labelMuted = rows[y].mutedFrom(labelAt, labelAt + label.length)
                        options += Option(next, label, detail, checked, cursor(m), labelMuted)
                        next++
                    }
                    rule(row) -> {
                        // A rule can split a menu (Chat about this, below the others); otherwise it ends it.
                        val after = (y + 1 until rows.size).firstOrNull { rows[it].text.isNotBlank() }
                        if (after == null || option.matchEntire(rows[after].text)?.groupValues?.get(3) != next.toString()) break
                    }
                    row.isBlank() -> break
                    // Beside a preview, options say nothing more: rows under one are its label, wrapped.
                    beside != null && indent(row) > numberAt && options.isNotEmpty() ->
                        options[options.lastIndex] = options.last().let { it.copy(label = it.label + " " + row.trim()) }
                    indent(row) >= labelAt && options.isNotEmpty() -> {
                        val text = row.trim()
                        val last = options.last()
                        val wrapped = wraps(rows[y - 1].text, text)
                        options[options.lastIndex] = if (wrapped && last.detail.isEmpty() && rows[y].mutedFrom(indent(row)) == labelMuted) {
                            last.copy(label = last.label + " " + text)
                        } else {
                            last.copy(detail = if (last.detail.isEmpty()) text else last.detail + (if (wrapped) " " else "\n") + text)
                        }
                    }
                    indent(row) > numberAt && options.isNotEmpty() -> {
                        val current = marker.containsMatchIn(row)
                        val label = row.trim().removePrefix("❯").removePrefix("›").removePrefix("▶").trim()
                        options += Option(null, label, "", null, current, rows[y].mutedFrom(indent(row)))
                    }
                    else -> break
                }
                y++
            }
            if (options.count { it.number != null } < 2 || options.none { it.current }) return null
            val (lines, tabs, quote) = about(rows, top)
            return Prompt(lines, options, tabs, quote, previewed = beside != null)
        }

        /** The cell a box beside the menu on row [top] starts at, if there's one: ┌ on its first option's row, └ under it. */
        private fun preview(rows: List<Row>, top: Int): Int? {
            val at = rows[top].text.indexOf(" ┌").takeIf { it >= 0 } ?: return null
            val x = rows[top].cell(at + 1)
            return x.takeIf { (top + 1 until rows.size).any { rows[it].at(x) == '└' } }
        }

        /** A list picked from with the cursor, below the last rule. */
        private fun picker(rows: List<Row>): Prompt? {
            val rule = rows.indexOfLast { rule(it.text) }
            if (rule < 0) return null
            val foot = (rule + 1 until rows.size).firstOrNull { rows[it].text.trim().startsWith("Enter to ") } ?: rows.size
            val cursor = (rule + 1 until foot).firstOrNull { marker.containsMatchIn(rows[it].text) } ?: return null
            val at = indent(rows[cursor].text) + 2 // where options' words start
            val top = (rule + 1..cursor).first { it == cursor || rows[it].text.isNotBlank() && indent(rows[it].text) >= at }
            if (top == rule + 1) return null // nothing said above it: a prompt's suggestions, say
            val options = mutableListOf<Option>()
            var fresh = true // the next row starts an option
            for (y in top until foot) {
                val row = rows[y].text
                val text = row.trim()
                val scroll = more.matchEntire(text)
                val current = marker.containsMatchIn(row)
                when {
                    text.isEmpty() -> fresh = true
                    !current && indent(row) < at -> break
                    scroll != null -> {
                        options += Option(null, text, "", null, current = false, muted = true, scroll = if (scroll.groupValues[1] == "↑") -1 else 1)
                        fresh = true
                    }
                    fresh || current -> {
                        val label = text.removePrefix("❯").removePrefix("›").removePrefix("▶").trim()
                        options += Option(null, label, "", null, current, rows[y].mutedFrom(indent(row)))
                        fresh = false
                    }
                    else -> options[options.lastIndex] = options.last().let { it.copy(detail = (it.detail + "\n" + text).trim()) }
                }
            }
            if (options.count { it.scroll == 0 } < 2 || options.none { it.current }) return null
            val (lines, tabs, quote) = about(rows, top)
            return Prompt(lines, options, tabs, quote)
        }

        /** What a menu whose options start on row [top] is about: up to the rule that opens its box. */
        private fun about(rows: List<Row>, top: Int): Triple<List<Line>, List<Tab>, String> {
            var r = top - 1
            while (r >= 0 && top - r <= CONTEXT && !rule(rows[r].text)) r--
            val lines = mutableListOf<Line>()
            val tabs = mutableListOf<Tab>()
            val quoted = mutableListOf<String>()
            var code = false
            var previous: String? = null // the row before, if it was words; a quoted (│) one without its bar
            var previousQuoted = false
            for (row in r + 1 until top) {
                val text = rows[row].text
                val quote = bar.matchEntire(text)?.groupValues?.get(1)
                val words = quote ?: text
                val chipped = text.trimStart().firstOrNull()?.let { it in "☐☒✔☑✓←" } == true && chips.containsMatchIn(text)
                when {
                    text.isBlank() -> {}
                    dashed(text) -> code = !code
                    chipped -> tabs += tabs(rows[row])
                    code -> lines += Line(text.trimEnd(), true)
                    // Words the app wrapped onto this row join the row before, quoted or not as it was.
                    previous != null && previousQuoted == (quote != null) && wraps(previous, words.trim()) ->
                        lines[lines.lastIndex] = Line(lines.last().text + " " + words.trim(), false)
                    else -> lines += Line(words.trim(), false)
                }
                if (quote != null) quoted += quote.trim()
                previous = words.takeIf { text.isNotBlank() && !dashed(text) && !chipped && !code }
                previousQuoted = quote != null
            }
            return Triple(dedent(lines), tabs, quoted.filterNot { ago.matches(it) }.joinToString(" "))
        }

        private fun tabs(row: Row) = chips.findAll(row.text).map { m ->
            Tab((m.groupValues[1] + " " + m.groupValues[2].trim()).trim(), m.range.any(row::highlighted))
        }.toList()

        /** Code keeps its indent relative to itself. */
        private fun dedent(lines: List<Line>): List<Line> {
            val common = lines.filter { it.code && it.text.isNotBlank() }.minOfOrNull { indent(it.text) } ?: 0
            return lines.map { if (it.code) it.copy(text = it.text.drop(common)) else it }
        }

        private fun indent(row: String) = row.length - row.trimStart().length

        /**
         * The app wrapped [next]'s words off [row]: its first word wouldn't
         * have fit. Apps keep a column or two clear at the edge.
         */
        private fun wraps(row: String, next: String) = row.trimEnd().length + 1 + next.substringBefore(' ').length > row.length - EDGE

        /** A rule across: ─, or ▔ in Claude Code's fullscreen mode. */
        private fun rule(row: String) = row.count { it == '─' || it == '▔' } >= maxOf(8, row.trimEnd().length * 3 / 4)

        private fun dashed(row: String) = row.count { it == '╌' } >= maxOf(8, row.trimEnd().length * 3 / 4)
    }
}
