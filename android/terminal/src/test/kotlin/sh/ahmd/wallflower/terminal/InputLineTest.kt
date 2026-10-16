package sh.ahmd.wallflower.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.ahmd.wallflower.terminal.Screen.Companion.FAINT
import sh.ahmd.wallflower.terminal.Screen.Companion.GREY
import sh.ahmd.wallflower.terminal.Screen.Companion.INPUT
import sh.ahmd.wallflower.terminal.Screen.Companion.PROMPT

class InputLineTest {
    /**
     * A screen from rows of text. [marks] gives each cell's attributes by
     * row, one character per cell: p prompt, i input, g input in
     * autosuggestion grey, f faint; anything else is plain output.
     */
    private fun screen(rows: List<String>, marks: List<String> = emptyList(), cursor: Pair<Int, Int>, wrapped: Set<Int> = emptySet()): Screen {
        val cols = rows.maxOf { it.length }
        val text = Array(cols * rows.size) { "" }
        val attrs = IntArray(cols * rows.size)
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, c -> text[y * cols + x] = if (c == ' ') "" else c.toString() }
            marks.getOrNull(y)?.forEachIndexed { x, m ->
                attrs[y * cols + x] = when (m) {
                    'p' -> PROMPT
                    'i' -> INPUT
                    'g' -> INPUT or GREY
                    'f' -> FAINT
                    else -> 0
                }
            }
        }
        return Screen(cols, rows.size, cursor.first, cursor.second, text, attrs, BooleanArray(rows.size) { it in wrapped })
    }

    @Test
    fun shellInputAfterThePromptWithoutTheRightPromptOrSuggestion() {
        val s = screen(
            listOf(
                "dotfiles ➜ git status --short      main ",
                "                                        ",
            ),
            listOf(
                "p".repeat(11) + "i".repeat(10) + "g".repeat(8) + "i".repeat(6) + "pppp ",
            ),
            cursor = 21 to 0,
        )
        assertEquals(InputLine("git status", 1), InputLine.read(s))
    }

    @Test
    fun anEmptyPromptIsAnEmptyLine() {
        val s = screen(listOf("dotfiles ➜           main "), listOf("p".repeat(11) + "i".repeat(10) + "pppp"), cursor = 11 to 0)
        assertEquals(InputLine("", 1), InputLine.read(s))
    }

    @Test
    fun aRunningCommandHasNoLine() {
        val s = screen(
            listOf("dotfiles ➜ sleep 10 ", "                    "),
            listOf("p".repeat(11) + "i".repeat(9), ""),
            cursor = 0 to 1,
        )
        assertNull(InputLine.read(s))
    }

    @Test
    fun wrappedShellInputJoinsWithoutANewline() {
        val s = screen(
            listOf("$ echo a long li", "ne of text      "),
            listOf("ppiiiiiiiiiiiiii", "iiiiiiiiiii     "),
            cursor = 10 to 1,
            wrapped = setOf(0),
        )
        assertEquals(InputLine("echo a long line of text", 2), InputLine.read(s))
    }

    @Test
    fun outputWithoutPromptMarksHasNoLine() {
        assertNull(InputLine.read(screen(listOf("$ ls", "a b c"), cursor = 0 to 1)))
    }

    private val rule = "─".repeat(24)

    @Test
    fun claudeBoxIgnoresThePlaceholder() {
        val s = screen(
            listOf("● Done.", rule, "❯ Try \"fix the tests\"", rule, "  -- INSERT --"),
            listOf("", "", "  fffffffffffffffffff"),
            cursor = 2 to 2,
        )
        assertEquals(InputLine("", 1), InputLine.read(s))
    }

    @Test
    fun claudeBoxJoinsItsOwnWrapsAndKeepsNewlines() {
        // 24 columns: 22 for text after the two-column margin.
        val s = screen(
            listOf(
                rule,
                "❯ make a profile for",
                "  hntui with j k",
                "  second line",
                rule,
            ),
            cursor = 15 to 3,
        )
        assertEquals(InputLine("make a profile for hntui with j k\nsecond line", 3), InputLine.read(s))
    }

    @Test
    fun claudeShellModeKeepsItsBang() {
        val s = screen(listOf(rule, "! seq 1 300", rule), cursor = 11 to 1)
        assertEquals(InputLine("!seq 1 300", 1), InputLine.read(s))
    }
}
