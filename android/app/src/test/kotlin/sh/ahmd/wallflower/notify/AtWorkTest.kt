package sh.ahmd.wallflower.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.ahmd.wallflower.protocol.Pane
import sh.ahmd.wallflower.protocol.State
import sh.ahmd.wallflower.protocol.Window

class AtWorkTest {
    private fun pane(id: String, activity: String = "", since: Long = 0, title: String = "") =
        Pane(id, 0, true, 80, 24, title = title, command = "2.1.319", program = "claude", view = true, activity = activity, since = since)

    private fun state(vararg windows: Window) = State("s", "s", windows.toList())

    @Test
    fun oneWaitingForTheUserComesFirstThenTheOneWorkingLongest() {
        val s = state(
            Window("@1", 0, "tests", true, listOf(pane("%1", Pane.WORKING, since = 2_000))),
            Window("@2", 1, "docs", false, listOf(pane("%2", Pane.WORKING, since = 1_000))),
            Window("@3", 2, "release", false, listOf(pane("%3", Pane.WAITING, since = 3_000))),
        )
        assertEquals(AtWork("%3", "release", waiting = true, since = 3_000), atWork(s))
        assertEquals(AtWork("%2", "docs", waiting = false, since = 1_000), atWork(s.copy(windows = s.windows.take(2))))
        // What Claude calls the conversation, over the window's name.
        val named = state(Window("@1", 0, "zsh", true, listOf(pane("%1", Pane.WORKING, since = 5, title = "✳ Fix the tests"))))
        assertEquals("Fix the tests", atWork(named)?.about)
    }

    @Test
    fun nothingAtWorkIsNothing() {
        assertNull(atWork(state(Window("@1", 0, "tests", true, listOf(pane("%1", Pane.IDLE), pane("%2"))))))
        assertNull(atWork(null))
    }
}
