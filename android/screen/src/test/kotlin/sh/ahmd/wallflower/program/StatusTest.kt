package sh.ahmd.wallflower.program

import org.junit.Assert.assertEquals
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
}
