package sh.ahmd.wallflower.program

import sh.ahmd.wallflower.screen.InputLine
import sh.ahmd.wallflower.screen.Screen
import sh.ahmd.wallflower.screen.Screen.Companion.INPUT
import sh.ahmd.wallflower.screen.Screen.Companion.OUTPUT
import sh.ahmd.wallflower.screen.Screen.Companion.PROMPT

/**
 * A program that marks its prompt (OSC 133), as a shell can: its line is
 * what's typed after the last prompt. [clearKeys] empty a row of it, if
 * anything does.
 */
class Shell(
    override val clearKeys: List<String> = emptyList(),
    override val keyBar: KeyDefaults? = null,
    override val shortcuts: List<Shortcut> = emptyList(),
) : Program {
    /**
     * The input cells after the last prompt, without autosuggestion ghost
     * text after the cursor. None while a command runs: its output, not a
     * prompt, holds the cursor.
     */
    override fun line(screen: Screen): InputLine? {
        val s = screen
        if (s.cursorY !in 0 until s.rows) return null
        val prompt = (s.cursorY downTo 0).firstOrNull { y -> (0 until s.cols).any { s.role(it, y) == PROMPT } } ?: return null
        fun holdsLine(y: Int) = (0 until s.cols).any { s.role(it, y) != OUTPUT }
        if ((prompt..s.cursorY).any { !holdsLine(it) }) return null
        var last = s.cursorY
        while (last + 1 < s.rows && s.wrapped(last)) last++

        val out = StringBuilder()
        for (y in prompt..last) {
            val row = StringBuilder()
            // From its first input cell to its last: a shell can leave a blank it typed undrawn, unmarked.
            val first = (0 until s.cols).firstOrNull { s.role(it, y) == INPUT } ?: -1
            val end = (s.cols - 1 downTo 0).firstOrNull { s.role(it, y) == INPUT } ?: -2
            for (x in maxOf(first, 0)..end) {
                if (s.spacer(x, y)) continue
                val afterCursor = y > s.cursorY || (y == s.cursorY && x >= s.cursorX)
                if (afterCursor && (s.grey(x, y) || s.faint(x, y))) continue
                row.append(s.text(x, y).ifEmpty { " " })
            }
            out.append(if (s.wrapped(y)) row else row.trimEnd())
            if (y < last && !s.wrapped(y)) out.append('\n')
        }
        return InputLine(out.toString().trim('\n'), last - prompt + 1)
    }
}
