package sh.ahmd.wallflower.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.ahmd.wallflower.ui.DiffLine.Kind

class DiffTest {
    @Test
    fun hunksSayWhereTheyAreInTheFileAsItIsNow() {
        val diff = "@@ -3,3 +5,4 @@\n a\n-old\n+new\n+more\n\\ No newline at end of file\n@@ -0,0 +1,1 @@\n+first\n… 12 more lines"
        assertEquals(
            listOf(
                DiffLine(Kind.Hunk, "line 5"),
                DiffLine(Kind.Context, "a"),
                DiffLine(Kind.Removed, "old"),
                DiffLine(Kind.Added, "new"),
                DiffLine(Kind.Added, "more"),
                DiffLine(Kind.Note, "\\ No newline at end of file"),
                DiffLine(Kind.Hunk, "line 1"),
                DiffLine(Kind.Added, "first"),
                DiffLine(Kind.Note, "… 12 more lines"),
            ),
            diffLines(diff),
        )
        assertEquals(3 to 1, diffCounts(diffLines(diff)))
    }
}
