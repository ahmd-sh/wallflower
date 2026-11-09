package sh.ahmd.wallflower.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.ahmd.wallflower.screen.InputLine
import sh.ahmd.wallflower.screen.Prompt
import sh.ahmd.wallflower.screen.captured

class StatusTest {
    @Test
    fun claudeSaysWhatItsDoingAboveItsBox() {
        assertEquals("Hyperspacing", Claude.status(captured("claude-working.ansi", 90)))
        assertEquals("Compacting conversation", Claude.status(captured("claude-compacting.ansi", 90)))
    }

    @Test
    fun whatItQueuedComesBetween() {
        assertEquals("Hyperspacing", Claude.status(captured("claude-working-queued.ansi", 90)))
    }

    @Test
    fun doneItSaysNothing() {
        assertNull(Claude.status(captured("claude-rewind-confirm.ansi", 54)))
    }

    @Test
    fun theQueuedPlaceholderIsNoSuggestion() {
        val line = Claude.line(captured("claude-working-queued.ansi", 90))!!
        assertEquals("", line.text)
        assertEquals("", line.suggestion)
    }

    @Test
    fun aRewindRestoredAtTheMacLeavesItsMessageInTheBox() {
        val confirm = Prompt.read(captured("claude-rewind-confirm.ansi", 54))!!
        val message = "Use the Write tool to create /private/tmp/claude-502/-Users-ahmedshaikh-dev-dotfiles/9fb53435-c5d1-4202-adc7-69292faff226/scratchpad/rw.txt containing hi, then say just: done"
        val rewound = Claude.settled(confirm, InputLine(message, 3)) as Effect.Rewound
        assertTrue(rewound.said(message))
        assertNull(Claude.settled(confirm, InputLine("", 1))) // never mind
        assertNull(Claude.settled(confirm, null))
        assertNull(Claude.settled(confirm.copy(options = confirm.options.filter { it.label.startsWith("Summarize") }), InputLine(message, 3)))
    }

    @Test
    fun editingAMessageWalksRewindsListToIt() {
        // ↑ 1 more, "say just: two", the cursor on the newest, ↓ 1 more: (current).
        val list = Prompt.read(captured("claude-rewind-list.ansi", 54))!!
        assertEquals(Walk.Listed(back = 0, last = 2, up = listOf("Up"), down = listOf("Down"), pick = listOf("Enter")), Claude.walk(list, "say just: two"))
        // ↑ 7 more, a message, the cursor on (current).
        val multiline = Claude.walk(Prompt.read(captured("claude-rewind-list-multiline.ansi", 54))!!, "x") as Walk.Listed
        assertEquals(-1 to 7, multiline.back to multiline.last)
        // At the top: the oldest, the cursor on the next, ↓ 11 more.
        val top = Claude.walk(Prompt.read(captured("claude-rewind-list-top.ansi", 54))!!, "x") as Walk.Listed
        assertEquals(10 to 11, top.back to top.last)
    }

    @Test
    fun whatPickingOneAsksSaysWhetherItsTheMessage() {
        // Its foot (Never mind) below a short pane.
        val confirm = Prompt.read(captured("claude-rewind-confirm.ansi", 54))!!
        val message = "Use the Write tool to create /private/tmp/claude-502/-Users-ahmedshaikh-dev-dotfiles/9fb53435-c5d1-4202-adc7-69292faff226/scratchpad/rw.txt containing hi, then say just: done"
        assertEquals(Walk.Picked(on = true, back = listOf("Escape")), Claude.walk(confirm, message))
        assertEquals(false, (Claude.walk(confirm, "say just: two") as Walk.Picked).on)
        assertEquals(false, (Claude.walk(confirm, "create /private/tmp/claude-502") as Walk.Picked).on) // from the start, not somewhere in it
    }

    @Test
    fun aQuoteIsTheMessageFromItsStart() {
        fun restore(quote: String): (String) -> Boolean {
            val option = Prompt.Option(1, "Restore conversation", "", null, current = true, muted = false)
            return (Claude.picked(Prompt(emptyList(), listOf(option), emptyList(), quote), option) as Effect.Rewound).said
        }
        assertTrue(restore("yes")("yes"))
        assertFalse(restore("yes")("yes, and commit"))
        assertFalse(restore("commit")("let's commit as wip"))
        assertTrue(restore("let's commit…")("let's commit as wip"))
        assertTrue(restore("say just: two, then … done")("say just: two, then think, then say done"))
    }

    @Test
    fun aMessageStartingOneIsNoMenuWhileTheBoxIsThere() {
        assertNull(Claude.prompt(captured("claude-numbered-message.ansi", 54)))
        // Every menu Claude asks with takes the box's place, so each still reads.
        for ((name, cols) in listOf(
            "claude-bash-permission.ansi" to 54, "claude-plan-approval.ansi" to 54, "claude-question.ansi" to 52, "claude-question-review.ansi" to 52,
            "claude-rewind-list.ansi" to 54, "claude-rewind-confirm.ansi" to 54, "claude-rewind-confirm-nocode.ansi" to 54, "claude-question-write.ansi" to 54,
        )) {
            assertTrue(name, Claude.prompt(captured(name, cols)) != null)
        }
    }
}
