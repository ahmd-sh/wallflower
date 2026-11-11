package sh.ahmd.wallflower.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.ahmd.wallflower.screen.captured

/** Codex CLI 0.167's screens, at 64 columns. */
class CodexTest {
    private fun screen(name: String) = captured("codex-$name.ansi", 64)

    @Test
    fun anEmptyComposerHoldsOnlyItsPlaceholder() {
        assertEquals("", Codex.line(screen("idle"))!!.text)
        assertNull(Codex.prompt(screen("idle")))
        assertNull(Codex.status(screen("idle")))
        assertNull(Codex.asked(screen("idle")))
    }

    @Test
    fun aLongLineIsWrappedAtWordsAndALongWordWhereTheRowEnds() {
        val line = Codex.line(screen("typed"))!!
        assertEquals(
            "the quick brown fox jumps over the lazy dog and keeps running far beyond the edge of this narrow pane " +
                "averyveryverylongwordthatcannotfitonarowatallnomatterhowhardittries end\nand a second line",
            line.text,
        )
        assertEquals(5, line.rows)
    }

    @Test
    fun theCommandsPopupIsAboveTheComposerNotInIt() {
        assertEquals("/", Codex.line(screen("slash"))!!.text)
        assertNull(Codex.prompt(screen("slash")))
    }

    @Test
    fun workingItSaysSoAboveTheComposer() {
        assertEquals("Working", Codex.status(screen("working")))
        assertEquals("", Codex.line(screen("working"))!!.text)
        assertNull(Codex.asked(screen("working")))
    }

    @Test
    fun itsQuestionsWaitBelowItsWorkingToBeBroughtUp() {
        val s = screen("working-asks")
        assertEquals("Working", Codex.status(s))
        assertEquals(Asked("1 question", listOf("S-Left")), Codex.asked(s))
        assertNull(Codex.prompt(s))
        assertEquals("a message typed while it works", Codex.line(screen("working-typed"))!!.text)
    }

    @Test
    fun broughtUpAQuestionTakesTheComposersPlace() {
        val s = screen("question")
        assertNull(Codex.line(s))
        assertNull(Codex.asked(s))
        val p = Codex.prompt(s)!!
        assertEquals(listOf("Red", "Blue", "Green", "Other"), p.options.map { it.label })
        assertEquals("Which colour do you pick?", p.lines.lastOrNull { it.text.isNotBlank() }?.text)
        assertEquals(listOf("2"), p.keys(p.options[1])) // a number answers it at once
        assertEquals("Working", Codex.status(s))
    }

    @Test
    fun anApprovalIsAMenu() {
        val s = screen("approval")
        assertNull(Codex.line(s))
        val p = Codex.prompt(s)!!
        assertEquals(3, p.options.size)
        assertEquals("Yes, proceed (y)", p.options[0].label)
        assertEquals("No, and tell Codex what to do differently (esc)", p.options[2].label)
        assertEquals(true, p.options[0].current)
    }

    @Test
    fun itsTitleIsTheConversationsNameThenTheProjects() {
        assertEquals("Approve touch command", Codex.label("Approve touch command | wallflower"))
        assertEquals("Approve touch command", Codex.label("⠴ Approve touch command | wallflower"))
        assertEquals("Approve touch command", Codex.label("[ ! ] Action Required | Approve touch command | wallflower"))
        assertNull(Codex.label("wallflower"))
        assertNull(Codex.label("[ . ] Action Required | wallflower"))
    }

    @Test
    fun itsModeIsPlanWhenItsFooterSaysSo() {
        assertEquals(Mode("plan", ""), Codex.mode(captured("codex-plan.ansi", 80)))
        assertEquals(Mode("default", ""), Codex.mode(screen("idle")))
        assertNull(Codex.mode(screen("question")))
    }

    @Test
    fun itHasItsOwnBarAndStop() {
        assertEquals(Codex, Programs.of("codex"))
        assertEquals(listOf("Escape"), Codex.keyBar.stop)
    }
}
