package sh.ahmd.wallflower.terminal

/**
 * Choices an app offers on its screen: a numbered menu with a cursor (❯ or
 * ›) on one option, as CLIs ask for permission, answers and approvals. The
 * rows above it, up to the rule that opens its box, say what it's about;
 * boxed rows (between ╌ rules) are code. A row of ☐ ☒ ✔ chips steps between
 * questions. Rows beneath an option continue it when its last word couldn't
 * fit the row before (the app wrapped it) and they're in its color; others
 * describe it. The bottom-most menu on the screen is the live one.
 */
data class Menu(val lines: List<Line>, val options: List<Option>, val tabs: List<Tab>) {
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
    )

    /** A question's chip, with its mark (☐ ☒ ✔) as shown. */
    data class Tab(val label: String, val current: Boolean)

    /**
     * Keys that pick [option]: its number, which these menus take at once
     * (a multiple choice's toggles), or the cursor moved there and Enter.
     */
    fun keys(option: Option): List<String> {
        val n = option.number
        if (n != null && n in 1..9) return listOf(n.toString())
        val from = options.indexOfFirst { it.current }.coerceAtLeast(0)
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
        private val option = Regex("""^(\s*)([❯›▶]\s*)?(\d{1,2})\.\s+(.*\S)\s*$""")
        private val marker = Regex("""^\s*[❯›▶]\s""")
        private val box = Regex("""^\[(.)]\s+(.*)$""")
        private val chips = Regex("""([☐☒✔☑✓])\s*([^☐☒✔☑✓←→]*)""")
        private const val CONTEXT = 24 // rows above the options at most
        private const val EDGE = 2

        fun read(s: Screen): Menu? {
            val rows = List(s.rows) { Row.of(s, it) }
            for (y in s.rows - 1 downTo 0) {
                val m = option.matchEntire(rows[y].text) ?: continue
                if (m.groupValues[3] != "1") continue
                block(rows, y)?.let { return it }
            }
            return null
        }

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

            companion object {
                fun of(s: Screen, y: Int): Row {
                    val text = StringBuilder()
                    val cells = ArrayList<Int>()
                    for (x in 0 until s.cols) {
                        if (s.spacer(x, y)) continue
                        for (c in s.text(x, y).ifEmpty { " " }) {
                            text.append(c)
                            cells += x
                        }
                    }
                    return Row(text.toString(), cells.toIntArray(), BooleanArray(s.cols) { s.muted(it, y) }, BooleanArray(s.cols) { s.highlighted(it, y) })
                }
            }
        }

        /** The menu whose first option is on row [top], if it is one. */
        private fun block(rows: List<Row>, top: Int): Menu? {
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
                        options += Option(next, label, detail, checked, m.groups[2] != null, labelMuted)
                        next++
                    }
                    rule(row) -> {
                        // A rule can split a menu (Chat about this, below the others); otherwise it ends it.
                        val after = (y + 1 until rows.size).firstOrNull { rows[it].text.isNotBlank() }
                        if (after == null || option.matchEntire(rows[after].text)?.groupValues?.get(3) != next.toString()) break
                    }
                    row.isBlank() -> break
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

            // What it's about: up to the rule that opens its box.
            val above = mutableListOf<Int>()
            var r = top - 1
            while (r >= 0 && top - r <= CONTEXT && !rule(rows[r].text)) {
                if (rows[r].text.isNotBlank()) above += r
                r--
            }
            val lines = mutableListOf<Line>()
            val tabs = mutableListOf<Tab>()
            var code = false
            var previous: String? = null // the row before, if it was words
            for (row in above.reversed()) {
                val text = rows[row].text
                when {
                    dashed(text) -> code = !code
                    text.trim().first() in "☐☒✔☑✓←" && chips.containsMatchIn(text) -> tabs += tabs(rows[row])
                    code -> lines += Line(text.trimEnd(), true)
                    // Words the app wrapped onto this row join the row before.
                    previous != null && wraps(previous, text.trim()) ->
                        lines[lines.lastIndex] = Line(lines.last().text + " " + text.trim(), false)
                    else -> lines += Line(text.trim(), false)
                }
                previous = text.takeIf { !code && !dashed(it) }
            }
            return Menu(dedent(lines), options, tabs)
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

        private fun rule(row: String) = row.count { it == '─' } >= maxOf(8, row.trimEnd().length * 3 / 4)

        private fun dashed(row: String) = row.count { it == '╌' } >= maxOf(8, row.trimEnd().length * 3 / 4)
    }
}
