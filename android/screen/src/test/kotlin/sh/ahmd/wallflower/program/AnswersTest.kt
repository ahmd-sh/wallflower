package sh.ahmd.wallflower.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.ahmd.wallflower.screen.Prompt
import sh.ahmd.wallflower.screen.Prompt.Line
import sh.ahmd.wallflower.screen.Prompt.Option
import sh.ahmd.wallflower.screen.Prompt.Tab

class AnswersTest {
    private fun option(n: Int?, label: String, checked: Boolean? = null, muted: Boolean = false, scroll: Int = 0, current: Boolean = false) =
        Option(n, label, "", checked, current, muted, scroll)

    private fun prompt(vararg options: Option, tabs: List<Tab> = emptyList()) =
        Prompt(listOf(Line("Bash command", false), Line("touch /tmp/x", true)), options.toList(), tabs)

    private val shell = Programs.of("zsh")

    @Test
    fun aPermissionsOptionsAllFit() {
        val p = prompt(option(1, "Yes"), option(2, "Yes, and always allow access to /tmp"), option(3, "No"))
        assertEquals(listOf("Yes", "Yes, and always allow access to /tmp", "No"), Claude.answers(p).map { it.label })
    }

    @Test
    fun moreThanFitKeepTheFirstAndTheLast() {
        val p = prompt(option(1, "Yes"), option(2, "Yes, and allow access to /tmp"), option(3, "Yes, and switch to auto mode"), option(4, "No"))
        assertEquals(listOf("Yes", "Yes, and allow access to /tmp", "No"), Claude.answers(p).map { it.label })
    }

    @Test
    fun onlyWhatAKeyPicksOutright() {
        val p = prompt(
            option(null, "↑ 1 more", scroll = -1),
            option(1, "Red"),
            option(2, "Blue"),
            option(3, "Type something", muted = true),
            option(null, "Next"),
        )
        assertEquals(listOf("Red", "Blue"), shell.answers(p).map { it.label })
        assertEquals(emptyList<Option>(), shell.answers(prompt(option(1, "Apples", checked = false), option(2, "Pears", checked = true))))
    }

    @Test
    fun noneForSeveralQuestionsAtOnce() {
        val p = prompt(option(1, "Fullscreen"), option(2, "Inline"), tabs = listOf(Tab("☐ Mode", true), Tab("☐ Hook", false), Tab("✔ Submit", false)))
        assertEquals(emptyList<Option>(), Claude.answers(p))
        assertEquals(2, Claude.answers(p.copy(tabs = listOf(Tab("☐ Mode", true)))).size)
    }

    @Test
    fun claudeLeavesOutWhatGoesOnToTyping() {
        val p = prompt(option(1, "Yes, and use auto mode"), option(2, "Yes, manually approve edits"), option(3, "Tell Claude what to change"))
        assertEquals(listOf("Yes, and use auto mode", "Yes, manually approve edits"), Claude.answers(p).map { it.label })
    }

    @Test
    fun theSameQuestionWhereverTheCursorIs() {
        val asked = prompt(option(1, "Yes", current = true), option(2, "No"))
        assertTrue(asked.same(prompt(option(1, "Yes"), option(2, "No", current = true))))
        assertFalse(asked.same(prompt(option(1, "Yes"), option(2, "No, and tell Claude"))))
        assertFalse(asked.same(asked.copy(lines = listOf(Line("touch /tmp/y", true)))))
    }
}
