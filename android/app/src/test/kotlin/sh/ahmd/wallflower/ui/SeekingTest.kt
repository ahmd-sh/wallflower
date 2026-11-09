package sh.ahmd.wallflower.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekingTest {
    @Test
    fun aMessageIsLookedForWhereTheConversationHasItThenCloseBy() {
        assertEquals(listOf(5, 4, 6, 3, 7, 2, 8), nearby(5, 10))
        assertEquals(listOf(0, 1, 2, 3), nearby(0, 10)) // nothing newer than the newest
        assertEquals(listOf(4, 3, 2, 1), nearby(4, 4)) // nor older than the oldest
        assertEquals(emptyList<Int>(), nearby(12, 7)) // the list hasn't anything near
    }
}
