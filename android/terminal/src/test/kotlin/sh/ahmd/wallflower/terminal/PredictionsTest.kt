package sh.ahmd.wallflower.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionsTest {
    private val p = Predictions()
    private val screen = HashMap<Pair<Int, Int>, Int>()
    private fun cellAt(x: Int, y: Int) = screen[x to y] ?: 0
    private fun echo(text: String, x0: Int, y: Int) = text.forEachIndexed { i, c -> screen[x0 + i to y] = c.code }

    @Test
    fun placesTextAtTheCursorAndStopsAtEnter() {
        p.add("ls -la\r", cursorX = 7, cursorY = 3, cols = 80, rows = 24, now = 0)
        assertEquals(6, p.cells.size)
        assertEquals(7 to 3, p.cells.first().x to p.cells.first().y)
        assertEquals(13 to 3, p.cursorAfter(80))
    }

    @Test
    fun confirmsProgressivelyAsTheEchoArrives() {
        p.add("hello", cursorX = 2, cursorY = 0, cols = 80, rows = 24, now = 0)
        echo("hel", 2, 0)
        assertTrue(p.confirm(::cellAt, now = 20))
        assertEquals("lo", p.cells.joinToString("") { it.text })
        echo("hello", 2, 0)
        assertTrue(p.confirm(::cellAt, now = 40))
        assertTrue(p.isEmpty)
        assertNull(p.cursorAfter(80))
    }

    @Test
    fun aStalledEchoExpires() {
        p.add("secret", cursorX = 10, cursorY = 5, cols = 80, rows = 24, now = 0)
        assertFalse(p.confirm(::cellAt, now = 500))
        assertFalse(p.expire(now = 999))
        assertTrue(p.expire(now = 1_000))
        assertTrue(p.isEmpty)
    }

    @Test
    fun progressKeepsTheRestAlive() {
        p.add("abcd", cursorX = 0, cursorY = 0, cols = 80, rows = 24, now = 0)
        echo("ab", 0, 0)
        p.confirm(::cellAt, now = 900)
        assertFalse("progress at 900ms extends the deadline", p.expire(now = 1_500))
        assertTrue(p.expire(now = 1_901))
    }

    @Test
    fun wrapsAtTheEdgeAndStopsAtTheBottom() {
        p.add("abcdef", cursorX = 3, cursorY = 1, cols = 5, rows = 3, now = 0)
        assertEquals(listOf(3 to 1, 4 to 1, 0 to 2, 1 to 2, 2 to 2, 3 to 2), p.cells.map { it.x to it.y })
        p.clear()
        p.add("abcdefgh", cursorX = 3, cursorY = 2, cols = 5, rows = 3, now = 0)
        assertEquals("only what fits above the bottom edge", 2, p.cells.size)
    }

    @Test
    fun continuesAfterPredictionsInFlight() {
        p.add("git ", cursorX = 0, cursorY = 0, cols = 80, rows = 24, now = 0)
        p.add("status", cursorX = 0, cursorY = 0, cols = 80, rows = 24, now = 10)
        assertEquals(4 to 0, p.cells[4].x to p.cells[4].y)
    }

    @Test
    fun widthsMatchTheTerminal() {
        assertEquals(1, Predictions.width("a"))
        assertEquals(1, Predictions.width("é"))
        assertEquals(2, Predictions.width("日"))
        assertEquals(2, Predictions.width("👩‍💻"))
        assertEquals(2, Predictions.width("❤️"))
        assertEquals(2, Predictions.width("🇨🇦"))
        p.add("日本!", cursorX = 0, cursorY = 0, cols = 80, rows = 24, now = 0)
        assertEquals(listOf(0, 2, 4), p.cells.map { it.x })
    }
}
