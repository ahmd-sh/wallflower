package sh.ahmd.wallflower.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoticeTest {
    private val input = Notice("Claude Code", "Claude needs your input")

    @Test
    fun claudeAskingAnythingIsOneNotice() {
        for (body in listOf("Claude needs your permission to use Bash", "Claude needs your permission")) {
            assertEquals(input, Claude.notice("Claude Code", body, asking = true))
        }
    }

    @Test
    fun claudeDoneAndWaitingIsNone() {
        assertNull(Claude.notice("Claude Code", "Claude is waiting for your input", asking = false))
        assertNull(Claude.notice("Claude Code", "Claude is waiting for your input", asking = null))
    }

    @Test
    fun claudeAskingWhatItsScreenDoesntShowStillIs() {
        assertEquals(input, Claude.notice("Claude Code", "Claude needs your permission to use Bash", asking = false))
        assertEquals(input, Claude.notice("Claude Code", "Claude is waiting for your input", asking = true))
    }

    @Test
    fun othersShowAsTheyAre() {
        assertEquals(Notice("Tests", "all 41 pass"), Programs.of("zsh").notice("Tests", "all 41 pass", asking = false))
    }
}
