package sh.ahmd.wallflower.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import sh.ahmd.wallflower.terminal.FrameBuffer.Companion.FLAG_BOLD
import sh.ahmd.wallflower.terminal.FrameBuffer.Companion.FLAG_GRAPHEME

@RunWith(AndroidJUnit4::class)
class TerminalTest {
    private val term = Terminal(20, 5)
    private val frame = FrameBuffer()

    @After
    fun close() = term.close()

    private fun write(s: String) = term.write(s.encodeToByteArray())

    private fun text(row: Int): String {
        val sb = StringBuilder()
        for (x in 0 until frame.cols) {
            if (FrameBuffer.wide(frame.flags(row, x)) == 2) continue
            val before = sb.length
            frame.appendText(row, x, sb)
            if (sb.length == before) sb.append(' ')
        }
        return sb.toString().trimEnd()
    }

    /** Index into the frame's rows of viewport row [y]. */
    private fun row(y: Int) = (0 until frame.rowCount).first { frame.rowY(it) == y }

    @Test
    fun packsTextStyleAndCursor() {
        term.setTheme(Theme.EverforestDarkHard)
        write("hello \u001b[1;31mred\u001b[0m\r\nline two")
        assertTrue(term.frame(frame, force = true))

        assertEquals(20, frame.cols)
        assertEquals(5, frame.rows)
        assertTrue(frame.full)
        assertEquals(5, frame.rowCount)
        assertEquals("hello red", text(row(0)))
        assertEquals("line two", text(row(1)))

        val r = row(0)
        assertTrue(frame.flags(r, 6) and FLAG_BOLD != 0)
        assertEquals(0xE67E80, frame.fg(r, 6))
        assertEquals(FrameBuffer.DEFAULT, frame.fg(r, 0))
        assertEquals(0x1E2326, frame.background)

        assertTrue(frame.cursorVisible)
        assertEquals(8, frame.cursorX)
        assertEquals(1, frame.cursorY)
    }

    @Test
    fun onlyChangedRowsArePacked() {
        write("one\r\ntwo\r\nthree")
        term.frame(frame, force = true)
        assertFalse("nothing changed", term.frame(frame))

        write("\u001b[2;1Hchanged")
        assertTrue(term.frame(frame))
        assertFalse(frame.full)
        val ys = (0 until frame.rowCount).map { frame.rowY(it) }
        assertTrue("row 1 dirty in $ys", 1 in ys)
        assertFalse("row 0 not dirty in $ys", 0 in ys)
        assertEquals("changed", text(row(1)))
    }

    // Widths must match tmux 3.8, which clusters graphemes: a ZWJ sequence,
    // a flag or a VS16 emoji is one wide cell.
    @Test
    fun wideCellsAndGraphemes() {
        write("日本 👩‍💻!") // 日本, space, woman technologist (ZWJ), !
        term.frame(frame, force = true)
        assertEquals("cursor after the ! like tmux", 8, frame.cursorX)
        val r = row(0)
        assertEquals(1, FrameBuffer.wide(frame.flags(r, 0)))
        assertEquals(2, FrameBuffer.wide(frame.flags(r, 1)))
        assertEquals('日'.code, frame.codepoint(r, 0))

        val emoji = 5
        assertTrue(frame.flags(r, emoji) and FLAG_GRAPHEME != 0)
        val sb = StringBuilder()
        frame.appendText(r, emoji, sb)
        assertEquals("👩‍💻", sb.toString())
        assertEquals("日本 👩‍💻!", text(r))

        // Still clustered after an app resets the terminal (RIS).
        write("\u001bc🇨🇦!❤️!") // flag, !, heart + VS16, !
        term.frame(frame, force = true)
        assertEquals(6, frame.cursorX)
    }

    @Test
    fun synchronizedOutputHoldsFrames() {
        term.frame(frame, force = true)
        write("\u001b[?2026hhalf-drawn")
        assertFalse("held during a synchronized update", term.frame(frame))
        write(" done\u001b[?2026l")
        assertTrue(term.frame(frame))
        assertEquals("half-drawn done", text(row(0)))
    }

    @Test
    fun scrollbackAndResize() {
        write((1..30).joinToString("\r\n") { "line $it" })
        term.frame(frame, force = true)
        assertEquals("line 30", text(row(4)))
        assertTrue(frame.scrollTotal >= 30)

        term.scrollBy(-10)
        term.frame(frame, force = true)
        assertEquals("line 20", text(row(4)))
        term.scrollToBottom()

        term.resize(10, 3)
        term.frame(frame, force = true)
        assertEquals(10, frame.cols)
        assertEquals(3, frame.rows)
    }

    @Test
    fun growsTheBufferForBigFrames() {
        val big = Terminal(300, 200)
        try {
            big.write("x".repeat(300 * 200).encodeToByteArray())
            assertTrue(big.frame(frame, force = true))
            assertEquals(200, frame.rowCount)
        } finally {
            big.close()
        }
    }
}
