package sh.ahmd.wallflower.files

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class NamesTest {
    @Test
    fun kindsByName() {
        assertEquals(Kind.Picture, kindOf("Screen Shot.PNG"))
        assertEquals(Kind.Pdf, kindOf("paper.pdf"))
        assertEquals(Kind.Markdown, kindOf("README.md"))
        assertEquals(Kind.Text, kindOf("main.go"))
        assertEquals(Kind.Text, kindOf("Makefile"))
        assertEquals(Kind.Text, kindOf(".zshrc"))
        assertEquals(Kind.Other, kindOf("movie.mov"))
        assertEquals(Kind.Other, kindOf("archive.tar.gz"))
    }

    @Test
    fun pathsAsWordsOfAMessage() {
        assertEquals("/Users/me/dev/a_b-c.png", forMessage("/Users/me/dev/a_b-c.png"))
        assertEquals("~/notes.md", forMessage("~/notes.md"))
        assertEquals("'/Users/me/Screen Shot 1.png'", forMessage("/Users/me/Screen Shot 1.png"))
        assertEquals("'/tmp/it'\\''s.txt'", forMessage("/tmp/it's.txt"))
        assertEquals("'/tmp/café.txt'", forMessage("/tmp/café.txt"))
    }

    @Test
    fun sizesAndAges() {
        assertEquals("812 B", sizeOf(812))
        assertEquals("14 KB", sizeOf(14_300))
        assertEquals("3.1 MB", sizeOf(3_140_000))
        val now = 1_800_000_000_000
        assertEquals("now", ago(now - 5_000, now))
        assertEquals("5m", ago(now - 300_000, now))
        assertEquals("3h", ago(now - 3 * 3_600_000, now))
        assertEquals("2d", ago(now - 2 * 86_400_000, now))
    }

    @Test
    fun textIsToldFromBinary() {
        assertTrue(looksLikeText("hello ✓\n".encodeToByteArray()))
        assertFalse(looksLikeText(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0, 0)))
    }
}
