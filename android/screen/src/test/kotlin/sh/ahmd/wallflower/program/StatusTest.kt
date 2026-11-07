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
        val list = Prompt.read(captured("claude-rewind-list.ansi", 54))!! // the cursor on "Use the Write tool to create /private/tmp/c…"
        assertEquals(Walk(on = true, up = listOf("Up"), pick = listOf("Enter")), Claude.walk(list, "Use the Write tool to create /private/tmp/claude-502/x/rw.txt containing hi"))
        assertEquals(false, Claude.walk(list, "say just: two")!!.on)
        assertEquals(false, Claude.walk(list, "create /private/tmp/c")!!.on) // from the start, not somewhere in it
        val multiline = Prompt.read(captured("claude-rewind-list-multiline.ansi", 54))!!
        assertEquals(listOf("Up"), Claude.walk(multiline, "1. reply with just the word ok\n2. nothing else")!!.up)
        assertNull(Claude.walk(Prompt.read(captured("claude-rewind-confirm.ansi", 54))!!, "say just: two")) // the user's to confirm
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
