package sh.ahmd.wallflower.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.ahmd.wallflower.screen.captured

/** Claude Code's views over its prompt box, from captures at 54 columns. */
class AsideTest {
    @Test
    fun itsTranscriptSaysSoBelowItsLastRule() {
        for (name in listOf("claude-transcript.ansi", "claude-transcript-help.ansi", "claude-transcript-search.ansi")) {
            val s = captured(name, 54)
            assertEquals(name, Aside.View("transcript", listOf("C-o")), Claude.aside(s))
            assertNull(name, Claude.line(s))
            assertNull(name, Claude.prompt(s)) // its numbered rows are something said
        }
    }

    @Test
    fun withItsBoxThereNothingsAside() {
        for ((name, cols) in listOf("claude-working.ansi" to 90, "claude-mode-manual.ansi" to 54, "claude-numbered-message.ansi" to 54, "claude-working-queued.ansi" to 90)) {
            assertNull(name, Claude.aside(captured(name, cols)))
        }
    }

    @Test
    fun whatItAsksIsntAside() {
        for ((name, cols) in listOf(
            "claude-bash-permission.ansi" to 54, "claude-plan-approval.ansi" to 54, "claude-question.ansi" to 52, "claude-question-review.ansi" to 52,
            "claude-rewind-list.ansi" to 54, "claude-rewind-confirm.ansi" to 54, "claude-question-write.ansi" to 54, "claude-rewind-list-fullscreen.ansi" to 52,
            "claude-rewind-confirm-fullscreen.ansi" to 52, "claude-question-previews.ansi" to 52,
        )) {
            assertNull(name, Claude.aside(captured(name, cols)))
        }
    }

    @Test
    fun aSideQuestionsAnswerTakesTheBoxsPlace() {
        val s = captured("claude-btw.ansi", 54)
        val answer = Claude.aside(s) as Aside.Answer
        assertEquals("what is 2+2, one word", answer.question)
        assertEquals(listOf("Four"), answer.rows)
        assertEquals(listOf("Escape"), answer.leave)
        assertEquals(46, answer.width)
        assertNull(Claude.line(s))
        assertNull(Claude.prompt(s))
    }

    @Test
    fun whileItsAnsweredItSaysSoAndWhatClaudeIsDoingShowsAboveIt() {
        val s = captured("claude-btw-answering.ansi", 54)
        val answer = Claude.aside(s) as Aside.Answer
        assertEquals("what is 3+3, one word", answer.question) // the bold one, below those asked before
        assertNull(answer.rows)
        assertEquals("Orchestrating", Claude.status(s))
    }

    @Test
    fun theAnsweredOneIsTheBoldOneOfThoseAsked() {
        val answer = Claude.aside(captured("claude-btw-history.ansi", 54)) as Aside.Answer
        assertEquals("what is 3+3, one word", answer.question)
        assertEquals(listOf("Six"), answer.rows)
        assertNull(Claude.status(captured("claude-btw-history.ansi", 54)))
    }

    @Test
    fun anAnswersWrappedRowsJoinAndItsOwnLinesStay() {
        val answer = Claude.aside(captured("claude-btw-markdown.ansi", 54)) as Aside.Answer
        assertEquals("in markdown: a heading, two short bullets,…", answer.question)
        assertEquals(
            listOf(
                "Markdown Basics",
                "",
                "- Headings use hash marks",
                "- Lists use dashes",
                "",
                "echo \"Hello, world\"",
                "",
                "Markdown lets you format plain text with simple symbols that convert into styled HTML. Headings use hash marks and " +
                    "lists use dashes. Because the syntax stays readable even unrendered, it suits notes, documentation, and quick messages shared across teams daily.",
            ).joinToString("\n"),
            answer.text(answer.rows!!),
        )
    }

    @Test
    fun aLongAnswerShowsAsMuchAsItsRoom() {
        val answer = Claude.aside(captured("claude-btw-long.ansi", 54)) as Aside.Answer
        assertEquals((1..25).map { "$it" }, answer.rows)
        assertEquals("1\n2\n3", answer.text(answer.rows!!.take(3)))
    }

    @Test
    fun scrolledThroughItsAnswerAddsUp() {
        val whole = (Claude.aside(captured("claude-btw-markdown.ansi", 54)) as Aside.Answer).rows!!
        var kept = emptyList<String>()
        for (i in 0..3) kept = (Claude.aside(captured("claude-btw-scroll-$i.ansi", 54)) as Aside.Answer).over(kept)
        assertEquals(whole, kept)
        // Scrolled back up, it's still all there.
        assertEquals(whole, (Claude.aside(captured("claude-btw-scroll-0.ansi", 54)) as Aside.Answer).over(kept))
    }

    @Test
    fun rowsThatDontMeetWhatWasReadStandAlone() {
        fun answer(vararg rows: String) = Aside.Answer("q", rows.toList(), 46, listOf("Escape"), listOf("Up"), listOf("Down"), 3)
        assertEquals(listOf("a", "b", "c"), answer("a", "b", "c").over(listOf("a"))) // more of it written
        assertEquals(listOf("x", "", "y"), answer("x", "", "y").over(listOf("a", ""))) // a blank alike isn't meeting
        assertEquals(listOf("z"), answer("z").over(listOf("a", "b")))
        assertEquals(listOf("a", "b"), answer("Answering").copy(rows = null).over(listOf("a", "b")))
        assertEquals(listOf("o", "p", "a", "b", "c"), answer("o", "p", "a", "b").over(listOf("a", "b", "c"))) // scrolled up past it
    }

    @Test
    fun btwIsACommand() {
        assertTrue(Claude.commands.any { it.text == "/btw" })
    }
}
