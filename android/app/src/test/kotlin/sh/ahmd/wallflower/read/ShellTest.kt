package sh.ahmd.wallflower.read

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.ahmd.wallflower.terminal.Command
import sh.ahmd.wallflower.terminal.Commands

class ShellTest {
    private fun keys(items: List<ShellItem>) = items.map { it.key }

    @Test
    fun historyThenEachCommandsTitleAndOutputWithoutTheWaitingPrompt() {
        val commands = Commands(
            rows = 40,
            history = 5,
            list = listOf(
                Command(1, start = 6, output = 7, end = 9),
                Command(2, start = 9, output = 10, end = 10), // printed nothing
                Command(3, start = 10, output = -1, end = 11), // the prompt waiting
            ),
        )
        val items = shellItems(commands, fullscreen = false)
        assertEquals(listOf("h.0", "gap", "t1", "o1.0", "t2", "o2.0"), keys(items))
        assertEquals(ShellItem.Title("t1", 6, 7), items[2])
        assertEquals(ShellItem.Rows("o1.0", 7, 9, first = true, last = true, live = false), items[3])
        assertEquals(ShellItem.Rows("o2.0", 10, 10, first = true, last = true, live = false), items[5]) // an empty card
    }

    @Test
    fun aLineLeftUnrunIsLeftOut() {
        val commands = Commands(20, 0, listOf(Command(1, 0, -1, 1), Command(2, 1, 2, 4), Command(3, 4, -1, 5)))
        val items = shellItems(commands, fullscreen = false)
        assertEquals(listOf("t2", "o2.0"), keys(items))
        assertEquals(false, (items[1] as ShellItem.Rows).live) // done: a prompt came after it
    }

    @Test
    fun repaintedPromptsThatMayHaveRunShow() {
        val commands = Commands(20, 0, listOf(Command(1, 0, -1, 1, typed = false), Command(2, 1, 2, 4, typed = false), Command(3, 4, -1, 5, typed = false)))
        assertEquals(listOf("t1", "o1.0", "t2", "o2.0"), keys(shellItems(commands, fullscreen = false)))
    }

    @Test
    fun theNewestCommandRunningIsLive() {
        val commands = Commands(20, 0, listOf(Command(1, 0, 1, 3)))
        assertEquals(true, (shellItems(commands, fullscreen = false)[1] as ShellItem.Rows).live)
    }

    @Test
    fun aCommandStartedButSilentHasAnEmptyLiveCard() {
        val commands = Commands(20, 0, listOf(Command(1, 0, 1, 1)))
        assertEquals(ShellItem.Rows("o1.0", 1, 1, first = true, last = true, live = true), shellItems(commands, fullscreen = false)[1])
    }

    @Test
    fun longOutputComesInPieces() {
        val commands = Commands(100, 0, listOf(Command(1, 0, 1, 71)))
        val rows = shellItems(commands, fullscreen = false, piece = 32).filterIsInstance<ShellItem.Rows>()
        assertEquals(listOf(1 to 33, 33 to 65, 65 to 71), rows.map { it.from to it.to })
        assertEquals(listOf(true, false, false), rows.map { it.first })
        assertEquals(listOf(false, false, true), rows.map { it.last })
    }

    @Test
    fun aFullScreenAppShowsItsScreenUnderItsTitle() {
        val commands = Commands(20, 0, listOf(Command(1, 0, 1, 3), Command(2, 3, 4, 4)))
        assertEquals(listOf("t1", "o1.0", "t2", "f2"), keys(shellItems(commands, fullscreen = true)))
        assertEquals(listOf("f"), keys(shellItems(Commands(20, 0, emptyList()), fullscreen = true)))
    }
}
