package sh.ahmd.wallflower.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.ahmd.wallflower.screen.InputLine
import sh.ahmd.wallflower.screen.Prompt
import sh.ahmd.wallflower.screen.Screen
import sh.ahmd.wallflower.screen.Screen.Companion.FAINT
import sh.ahmd.wallflower.screen.Screen.Companion.GREY
import sh.ahmd.wallflower.screen.Screen.Companion.INPUT
import sh.ahmd.wallflower.screen.Screen.Companion.PROMPT

class LineTest {
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
        assertEquals(InputLine("git status", 1), Shell().line(s))
    }

    @Test
    fun aBlankTheShellLeftUndrawnStaysInTheLine() {
        val s = screen(listOf("dotfiles ➜ ls -G     main "), listOf("p".repeat(11) + "ii.ii" + "     pppp"), cursor = 16 to 0)
        assertEquals(InputLine("ls -G", 1), Shell().line(s))
    }

    @Test
    fun anEmptyPromptIsAnEmptyLine() {
        val s = screen(listOf("dotfiles ➜           main "), listOf("p".repeat(11) + "i".repeat(10) + "pppp"), cursor = 11 to 0)
        assertEquals(InputLine("", 1), Shell().line(s))
    }

    @Test
    fun aRunningCommandHasNoLine() {
        val s = screen(
            listOf("dotfiles ➜ sleep 10 ", "                    "),
            listOf("p".repeat(11) + "i".repeat(9), ""),
            cursor = 0 to 1,
        )
        assertNull(Shell().line(s))
    }

    @Test
    fun wrappedShellInputJoinsWithoutANewline() {
        val s = screen(
            listOf("$ echo a long li", "ne of text      "),
            listOf("ppiiiiiiiiiiiiii", "iiiiiiiiiii     "),
            cursor = 10 to 1,
            wrapped = setOf(0),
        )
        assertEquals(InputLine("echo a long line of text", 2), Shell().line(s))
    }

    @Test
    fun outputWithoutPromptMarksHasNoLine() {
        assertNull(Shell().line(screen(listOf("$ ls", "a b c"), cursor = 0 to 1)))
    }

    private val rule = "─".repeat(24)

    @Test
    fun claudeBoxSuggestsWhatItsPlaceholderQuotes() {
        val s = screen(
            listOf("● Done.", rule, "❯ Try \"fix the tests\"", rule, "  -- INSERT --"),
            listOf("", "", "  fff ffff fff ffffff"),
            cursor = 2 to 2,
        )
        assertEquals(InputLine("", 1, "fix the tests"), Claude.line(s))
    }

    @Test
    fun claudeBoxSuggestsWhatsNextAcrossRows() {
        val s = screen(
            listOf(rule, "❯ ask the fruit and size", "  questions again", rule),
            listOf("", "  ffffffffffffffffffffff", "  ffffffffffffffff"),
            cursor = 2 to 1,
        )
        assertEquals(InputLine("", 2, "ask the fruit and size questions again"), Claude.line(s))
    }

    @Test
    fun claudeBoxSuggestsNothingOnceTyped() {
        val s = screen(
            listOf(rule, "❯ fix the tests", rule),
            listOf("", "  iiiiiiiifffff"),
            cursor = 11 to 1,
        )
        assertEquals(InputLine("fix the", 1), Claude.line(s))
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
        assertEquals(InputLine("make a profile for hntui with j k\nsecond line", 3), Claude.line(s))
    }

    @Test
    fun claudeShellModeKeepsItsBang() {
        val s = screen(listOf(rule, "! seq 1 300", rule), cursor = 11 to 1)
        assertEquals(InputLine("!seq 1 300", 1), Claude.line(s))
    }
}

class ProgramTest {
    private val line = InputLine("two\nrows", rows = 2)

    @Test
    fun theCommandPicksTheProgram() {
        assertEquals(Claude, Programs.of("claude"))
        assertEquals(listOf("C-x", "C-u"), Programs.of("ZSH").clearKeys)
        assertEquals(emptyList<String>(), Programs.of("bash").clearKeys)
        assertEquals(emptyList<String>(), Programs.of("hntui").clearKeys)
    }

    @Test
    fun aLineIsClearedARowAtATimeAndOnceMore() {
        assertEquals(List(5) { listOf("C-x", "C-u") }.flatten(), Programs.of("zsh").clear(line, unseen = true))
        assertEquals(List(5) { listOf("Escape", "S") }.flatten(), Programs.of("zsh").clear(line, listOf("Escape", "S")))
    }

    @Test
    fun claudeIsInInsertModeBeforeItsLineIsClearedUnseen() {
        assertEquals(List(5) { "C-u" }, Claude.clear(line))
        assertEquals(listOf("A") + List(5) { "C-u" }, Claude.clear(line, unseen = true))
        assertEquals(List(5) { "C-k" }, Claude.clear(line, listOf("C-k"), unseen = true)) // the user's own keys, as they are
    }

    @Test
    fun restoringFromRewindTakesTheConversationBackToTheQuotedMessage() {
        fun option(label: String) = Prompt.Option(1, label, "", null, current = true, muted = false)
        val prompt = Prompt(emptyList(), listOf(option("Restore conversation"), option("Restore code")), emptyList(), quote = "say just: two, then … done")
        val rewound = Claude.picked(prompt, prompt.options[0]) as Effect.Rewound
        assertTrue(rewound.said("say just: two, then think, then say done"))
        assertFalse(rewound.said("say just: three"))
        assertNull(Claude.picked(prompt, prompt.options[1]))
        assertNull(Claude.picked(prompt.copy(quote = ""), prompt.options[0]))
    }
}
