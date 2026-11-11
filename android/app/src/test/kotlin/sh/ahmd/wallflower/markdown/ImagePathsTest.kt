package sh.ahmd.wallflower.markdown

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class ImagePathsTest {
    private fun found(text: String) = ImagePaths.find(text).map { text.substring(it) }

    @Test
    fun pathsInWhatsSaid() {
        assertEquals(listOf("/Users/me/shot.png", "~/Desktop/a.JPG"), found("Saved it to /Users/me/shot.png and ~/Desktop/a.JPG."))
        assertEquals(listOf("./out/screen.png", "out/screen-2.webp", "shot.png"), found("see ./out/screen.png, out/screen-2.webp; and (shot.png)"))
        assertEquals(listOf("../up/x.heic"), found("../up/x.heic: done"))
        assertEquals(listOf("logo@2x.png"), found("the logo ✓ logo@2x.png"))
        assertEquals(listOf("café/über.png"), found("in café/über.png"))
    }

    @Test
    fun notPictures() {
        assertEquals(emptyList<String>(), found("https://example.com/a.png and http://x.io/b/c.jpg"))
        assertEquals(emptyList<String>(), found("shot.png.bak, main.go, a.pngx, notes.md"))
        assertEquals(emptyList<String>(), found("png alone, .png alone"))
    }

    @Test
    fun codeThatsAPath() {
        assertTrue(ImagePaths.whole("/Users/me/Screen Shot 1.png"))
        assertTrue(ImagePaths.whole("out/a.gif"))
        assertFalse(ImagePaths.whole("open shot.png now\nplease.png"))
        assertFalse(ImagePaths.whole("https://a.io/x.png"))
        assertFalse(ImagePaths.whole("main.go"))
    }

    @Test
    fun resolvedFromWhereThePaneIs() {
        assertEquals("/tmp/a.png", ImagePaths.resolve("/tmp/a.png", "/Users/me"))
        assertEquals("~/a.png", ImagePaths.resolve("~/a.png", "/Users/me/dev"))
        assertEquals("/Users/me/dev/out/a.png", ImagePaths.resolve("./out/a.png", "/Users/me/dev"))
        assertEquals("/Users/me/a.png", ImagePaths.resolve("../a.png", "/Users/me/dev"))
        assertEquals("~/a.png", ImagePaths.resolve("a.png", ""))
    }
}
