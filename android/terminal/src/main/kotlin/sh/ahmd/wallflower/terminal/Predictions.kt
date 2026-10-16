package sh.ahmd.wallflower.terminal

import java.text.BreakIterator

/**
 * Text sent to a pane, drawn ahead of its echo. Each grapheme is predicted at
 * the cell it should land in, from the cursor on. A prediction is confirmed
 * when the terminal shows that grapheme in that cell, which happens left to
 * right as the echo streams in; whatever is still unconfirmed when the echo
 * stops coming (a password prompt, an app that doesn't echo) is dropped.
 */
class Predictions {
    class Cell(val x: Int, val y: Int, val text: String, val width: Int, internal val codepoint: Int)

    /** Unconfirmed predictions, in typing order. */
    val cells = ArrayList<Cell>()
    private var deadline = 0L

    val isEmpty get() = cells.isEmpty()

    /** Where the cursor will be once the predictions land. */
    fun cursorAfter(cols: Int): Pair<Int, Int>? {
        val last = cells.lastOrNull() ?: return null
        val x = last.x + last.width
        return if (x >= cols) 0 to last.y + 1 else x to last.y
    }

    /**
     * Predicts [text] typed at the cursor, continuing after any predictions
     * still in flight. Stops at the first control character (Enter, Tab, ...):
     * where those lead is up to the app.
     */
    fun add(text: String, cursorX: Int, cursorY: Int, cols: Int, rows: Int, now: Long) {
        var (x, y) = cursorAfter(cols) ?: (cursorX to cursorY)
        val graphemes = BreakIterator.getCharacterInstance()
        graphemes.setText(text)
        var start = graphemes.first()
        var end = graphemes.next()
        while (end != BreakIterator.DONE) {
            val g = text.substring(start, end)
            start = end
            end = graphemes.next()
            val cp = g.codePointAt(0)
            if (cp < 0x20 || cp == 0x7F) break
            val w = width(g)
            if (x + w > cols) {
                x = 0
                y++
            }
            if (y >= rows) break // would scroll; the echo will show it
            cells += Cell(x, y, g, w, cp)
            x += w
        }
        deadline = now + TIMEOUT_MS
    }

    /**
     * Drops predictions the terminal now shows, given its cells' first
     * codepoints. Returns true if anything changed. Progress keeps the rest
     * alive; a stalled echo expires them.
     */
    fun confirm(cellAt: (x: Int, y: Int) -> Int, now: Long): Boolean {
        val before = cells.size
        cells.removeAll { cellAt(it.x, it.y) == it.codepoint }
        if (cells.size != before) deadline = now + TIMEOUT_MS
        return cells.size != before || expire(now)
    }

    /** Drops everything once the echo has stalled. Returns true if it did. */
    fun expire(now: Long): Boolean {
        if (cells.isEmpty() || now < deadline) return false
        cells.clear()
        return true
    }

    fun clear() = cells.clear()

    companion object {
        const val TIMEOUT_MS = 1_000L

        /** Cell width, close enough to the terminal's to place a prediction. */
        fun width(g: String): Int {
            val cp = g.codePointAt(0)
            if (g.length > Character.charCount(cp)) {
                // Emoji sequences (ZWJ, VS16, skin tones) and flags are one wide cell.
                if ('‍' in g || '️' in g || cp in 0x1F1E6..0x1F1FF || cp in 0x1F000..0x1FAFF) return 2
            }
            return when (cp) {
                in 0x1100..0x115F, in 0x2E80..0x303E, in 0x3041..0x33FF, in 0x3400..0x4DBF,
                in 0x4E00..0x9FFF, in 0xA000..0xA4CF, in 0xAC00..0xD7A3, in 0xF900..0xFAFF,
                in 0xFE30..0xFE4F, in 0xFF00..0xFF60, in 0xFFE0..0xFFE6,
                in 0x1F300..0x1F64F, in 0x1F900..0x1F9FF, in 0x1FA70..0x1FAFF, in 0x20000..0x3FFFD -> 2
                else -> 1
            }
        }
    }
}
