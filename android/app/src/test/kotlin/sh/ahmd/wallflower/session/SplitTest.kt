package sh.ahmd.wallflower.session

import org.junit.Assert.assertEquals
import org.junit.Test

class SplitTest {
    private val uploads = uploadPaths("/Users/me/Library/Caches/wallflower/uploads")

    @Test
    fun uploadsAreTheirOwnRuns() {
        assertEquals(
            listOf(
                "what's in " to false,
                "/Users/me/Library/Caches/wallflower/uploads/PXL_1.jpg" to true,
                " and " to false,
                "/Users/me/Library/Caches/wallflower/uploads/a-2.png" to true,
                "?\nthanks" to false,
            ),
            split(
                "what's in /Users/me/Library/Caches/wallflower/uploads/PXL_1.jpg and /Users/me/Library/Caches/wallflower/uploads/a-2.png?\nthanks",
                uploads,
            ),
        )
    }

    @Test
    fun otherPathsAreJustText() {
        assertEquals(listOf("cat /Users/me/notes.txt" to false), split("cat /Users/me/notes.txt", uploads))
        assertEquals(false, uploads.containsMatchIn("/Users/me/Library/Caches/wallflower/uploadsX/a.png"))
        assertEquals(
            listOf("look at " to false, "/Users/me/Library/Caches/wallflower/uploads/a.png" to true, "." to false),
            split("look at /Users/me/Library/Caches/wallflower/uploads/a.png.", uploads),
        )
    }
}
