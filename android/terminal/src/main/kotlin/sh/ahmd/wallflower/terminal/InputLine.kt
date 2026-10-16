package sh.ahmd.wallflower.terminal

import sh.ahmd.wallflower.terminal.Screen.Companion.INPUT
import sh.ahmd.wallflower.terminal.Screen.Companion.OUTPUT
import sh.ahmd.wallflower.terminal.Screen.Companion.PROMPT

/**
 * What an app's input line holds, read off its screen, and how many rows it
 * takes. Two apps are read: a shell that marks its prompt (OSC 133), and
 * Claude Code's prompt box. Anything else has no line wallflower can see.
 */
data class InputLine(val text: String, val rows: Int) {
    companion object {
        fun read(s: Screen): InputLine? = shell(s) ?: claude(s)

        /**
         * The input cells after the last prompt, without autosuggestion ghost
         * text after the cursor. None while a command runs: its output, not a
         * prompt, holds the cursor.
         */
        internal fun shell(s: Screen): InputLine? {
            if (s.cursorY !in 0 until s.rows) return null
            val prompt = (s.cursorY downTo 0).firstOrNull { y -> (0 until s.cols).any { s.role(it, y) == PROMPT } } ?: return null
            fun holdsLine(y: Int) = (0 until s.cols).any { s.role(it, y) != OUTPUT }
            if ((prompt..s.cursorY).any { !holdsLine(it) }) return null
            var last = s.cursorY
            while (last + 1 < s.rows && s.wrapped(last)) last++

            val out = StringBuilder()
            for (y in prompt..last) {
                val row = StringBuilder()
                for (x in 0 until s.cols) {
                    if (s.role(x, y) != INPUT || s.spacer(x, y)) continue
                    val afterCursor = y > s.cursorY || (y == s.cursorY && x >= s.cursorX)
                    if (afterCursor && (s.grey(x, y) || s.faint(x, y))) continue
                    row.append(s.text(x, y).ifEmpty { " " })
                }
                out.append(if (s.wrapped(y)) row else row.trimEnd())
                if (y < last && !s.wrapped(y)) out.append('\n')
            }
            return InputLine(out.toString().trim('\n'), last - prompt + 1)
        }

        /**
         * Claude Code's prompt box: between two rules, a first row starting
         * `❯ ` (or `! ` in shell mode) and rows indented to match. Claude
         * wraps long lines itself, at words, so a row that couldn't fit the
         * next one's first word was wrapped rather than ended. Faint text is
         * the placeholder or a suggestion, not input.
         */
        internal fun claude(s: Screen): InputLine? {
            for (y in s.rows - 2 downTo 1) {
                val mark = s.text(0, y)
                if ((mark != "❯" && mark != "!") || s.text(1, y).isNotBlank() || !rule(s, y - 1)) continue
                val end = (y + 1 until s.rows).firstOrNull { rule(s, it) } ?: continue
                val width = s.cols - 2
                val out = StringBuilder(if (mark == "!") "!" else "")
                var previous: String? = null
                for (r in y until end) {
                    val row = StringBuilder()
                    for (x in 2 until s.cols) {
                        if (!s.spacer(x, r) && !s.faint(x, r)) row.append(s.text(x, r).ifEmpty { " " })
                    }
                    val line = row.trimEnd().toString()
                    if (previous != null) {
                        val word = line.substringBefore(' ').length
                        out.append(
                            when {
                                previous.length >= width -> ""
                                line.isNotEmpty() && previous.length + 1 + word > width -> " "
                                else -> "\n"
                            },
                        )
                    }
                    out.append(line)
                    previous = line
                }
                return InputLine(out.toString().trimEnd(), end - y)
            }
            return null
        }

        private fun rule(s: Screen, y: Int) =
            s.text(0, y) == "─" && (0 until s.cols).count { s.text(it, y) == "─" } >= s.cols / 2
    }
}
