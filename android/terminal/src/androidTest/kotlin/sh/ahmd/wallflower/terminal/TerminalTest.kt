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

    @Test
    fun selectsTheWordUnderACell() = Terminal(40, 4).use { t ->
        t.write("see src/main.go:12 now\r\n(https://x.dev/a?b=1)".encodeToByteArray())
        assertTrue(t.selectWord(6, 0))
        assertEquals("a path keeps its slashes and colons", "src/main.go:12", t.selectionText())
        assertEquals(Terminal.Selection(4, 0, 17, 0), t.selection())
        assertTrue(t.selectWord(10, 1))
        assertEquals("brackets end it", "https://x.dev/a?b=1", t.selectionText())
        assertFalse("nothing to select in blank cells", t.selectWord(30, 0))
    }

    @Test
    fun dragExtendsFromTheWord() = Terminal(40, 4).use { t ->
        t.write("one two three\r\nfour five".encodeToByteArray())
        assertTrue(t.selectWord(5, 0)) // two
        t.selectExtend(6, 1) // into five
        assertEquals("two three\nfour fi", t.selectionText())
        t.selectExtend(1, 0) // back before it: the word stays in
        assertEquals("ne two", t.selectionText())
    }

    @Test
    fun handlesMoveOneEnd() = Terminal(40, 4).use { t ->
        t.write("alpha beta gamma".encodeToByteArray())
        assertTrue(t.selectWord(7, 0)) // beta
        t.selectHold(last = true)
        t.selectTo(15, 0)
        assertEquals("beta gamma", t.selectionText())
        t.selectHold(last = false)
        t.selectTo(0, 0)
        assertEquals("alpha beta gamma", t.selectionText())
        t.unselect()
        assertEquals(null, t.selection())
    }

    @Test
    fun selectionStaysOnItsTextAsItScrolls() = Terminal(20, 3).use { t ->
        t.write("first\r\nsecond".encodeToByteArray())
        assertTrue(t.selectWord(1, 1))
        t.write("\r\nthird\r\nfourth".encodeToByteArray())
        assertEquals(Terminal.Selection(0, 0, 5, 0), t.selection())
        t.write("\r\nfifth".encodeToByteArray())
        assertEquals("above the view, still kept", -1, t.selection()?.startRow)
        assertEquals("second", t.selectionText())
    }

    @Test
    fun copyJoinsWrappedRowsAndTrims() = Terminal(10, 4).use { t ->
        t.write("abcdefghijklmn   \r\nxy   ".encodeToByteArray())
        t.selectView()
        assertEquals("abcdefghijklmn\nxy", t.selectionText()?.trim('\n'))
    }
}
