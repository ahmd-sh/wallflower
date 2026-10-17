package sh.ahmd.wallflower.session

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.ahmd.wallflower.protocol.Entries
import sh.ahmd.wallflower.protocol.Entry

class ConversationTest {
    private fun text(id: String) = Entry(id, "assistant", "text", id)

    @Test
    fun entriesReplaceTheirsByIdOrComeLast() {
        val c = Conversation()
            .apply(Entries("%1", reset = true, more = true, entries = listOf(text("a"), Entry("t", "assistant", "tool", "ls", name = "Bash"))))
            .apply(Entries("%1", entries = listOf(Entry("t", "assistant", "tool", "ls", name = "Bash", output = "x", done = true), text("b"))))
        assertEquals(listOf("a", "t", "b"), c.entries.map { it.id })
        assertEquals("x", c.entries[1].output)
        assertEquals(true, c.more)
        assertEquals(true, c.loaded)
    }

    @Test
    fun aResetStartsOver() {
        val c = Conversation()
            .apply(Entries("%1", reset = true, entries = listOf(text("a"))))
            .apply(Entries("%1", reset = true, error = "the conversation ended"))
        assertEquals(emptyList<Entry>(), c.entries)
        assertEquals("the conversation ended", c.error)
    }

    @Test
    fun theOldestGoPastTheLimit() {
        val c = Conversation()
            .apply(Entries("%1", reset = true, entries = List(Conversation.MAX_ENTRIES) { text("$it") }))
            .apply(Entries("%1", entries = listOf(text("new"))))
        assertEquals(Conversation.MAX_ENTRIES, c.entries.size)
        assertEquals("1", c.entries.first().id)
        assertEquals("new", c.entries.last().id)
        assertEquals(true, c.more)
    }
}
