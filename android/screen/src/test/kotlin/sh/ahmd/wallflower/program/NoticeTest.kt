package sh.ahmd.wallflower.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.ahmd.wallflower.screen.Screen
import sh.ahmd.wallflower.screen.captured

class NoticeTest {
    private val input = Notice("Claude Code", "Claude needs your input")

    private fun plain(vararg rows: String): Screen {
        val cols = 40
        val text = Array(cols * rows.size) { rows[it / cols].getOrNull(it % cols)?.takeIf { c -> c != ' ' }?.toString() ?: "" }
        return Screen(cols, rows.size, 2, 2, text, IntArray(cols * rows.size), BooleanArray(rows.size))
    }

    private val rule = "─".repeat(40)

    @Test
    fun claudeAskingAnythingIsOneNotice() {
        val asking = listOf(
            "claude-bash-permission.ansi" to 54,
            "claude-plan-approval.ansi" to 54,
            "claude-question.ansi" to 52,
            "claude-question-review.ansi" to 52,
        )
        for ((capture, cols) in asking) {
            assertEquals(capture, input, Claude.notice("Claude Code", "Claude needs your permission", captured(capture, cols)))
            assertEquals("$capture, by the bell", input, Claude.notice(null, null, captured(capture, cols)))
        }
    }

    @Test
    fun claudeWithItsPromptBoxUpIsNone() {
        val idle = plain("● Done.", rule, "❯ ", rule, "  -- INSERT --")
        for (body in listOf("Claude is waiting for your input", "Explore agent finished", "the build passed", null)) {
            assertNull(body, Claude.notice("Claude Code", body, idle))
        }
    }

    @Test
    fun claudeWhoseScreenCouldntBeReadIsOneNotice() {
        assertEquals(input, Claude.notice("Claude Code", "Claude is waiting for your input", null))
    }

    @Test
    fun onlyClaudeReadsItsScreenAndOthersShowAsTheyAreButForTheBell() {
        val zsh = Programs.of("zsh")
        assertTrue(Claude.readsScreenToNotify)
        assertFalse(zsh.readsScreenToNotify)
        assertEquals(Notice("Tests", "all 41 pass"), zsh.notice("Tests", "all 41 pass", null))
        assertNull(zsh.notice(null, null, null))
    }
}
