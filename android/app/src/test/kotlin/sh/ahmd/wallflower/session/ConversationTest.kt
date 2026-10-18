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
    fun rewoundItHidesTheMessageQuotedAndWhatFollowedUntilAReset() {
        val said = Entry("u2", "user", "text", "Use the Write tool to create /private/tmp/claude-502/rw.txt containing hi")
        var c = Conversation().apply(Entries("%1", reset = true, entries = listOf(Entry("u1", "user", "text", "one"), text("a1"), said, text("a2"))))
        c = c.rewoundTo("Use the Write tool to create /private/tmp/claude -502/rw.txt containing hi")
        assertEquals(listOf("u1", "a1"), c.shown.map { it.id })
        assertEquals(listOf("u1", "a1"), c.apply(Entries("%1", state = Entries.IDLE)).shown.map { it.id })
        assertEquals(listOf("u1", "a1"), c.rewoundTo("…create /private/tmp/claude…").shown.map { it.id })
        assertEquals(listOf("u1", "a1", "u2", "a2"), c.apply(Entries("%1", reset = true, entries = c.entries)).shown.map { it.id })
        assertEquals(4, Conversation().apply(Entries("%1", reset = true, entries = c.entries)).rewoundTo("something else").shown.size)
    }

    @Test
    fun aRepeatedMessageRewoundHidesFromItsLatestSoNothingStillSaidIsHidden() {
        // The quote can't tell two "yes"es apart: hiding from the later one may leave some of what was
        // taken back showing until the transcript says (the next message), but never hides what's still there.
        val entries = listOf(Entry("u1", "user", "text", "yes"), text("a1"), Entry("u2", "user", "text", "yes"), text("a2"))
        val c = Conversation().apply(Entries("%1", reset = true, entries = entries)).rewoundTo("yes")
        assertEquals(listOf("u1", "a1"), c.shown.map { it.id })
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

    @Test
    fun theStateKeepsUntilItChanges() {
        val c = Conversation()
            .apply(Entries("%1", reset = true, entries = listOf(text("a")), state = Entries.WORKING, since = 5))
            .apply(Entries("%1", entries = listOf(text("b"))))
        assertEquals(true, c.working)
        assertEquals(5L, c.since)
        val done = c.apply(Entries("%1", state = Entries.IDLE, since = 9))
        assertEquals(Entries.IDLE, done.state)
        assertEquals(9L, done.since)
        assertEquals(listOf("a", "b"), done.entries.map { it.id })
    }

    @Test
    fun theContextKeepsUntilItChangesAndGoesWithANewConversation() {
        val c = Conversation()
            .apply(Entries("%1", reset = true, entries = listOf(text("a")), context = 1000))
            .apply(Entries("%1", entries = listOf(text("b"))))
        assertEquals(1000, c.context)
        val more = c.apply(Entries("%1", context = 2500))
        assertEquals(2500, more.context)
        assertEquals(listOf("a", "b"), more.entries.map { it.id })
        assertEquals(0, more.apply(Entries("%1", context = 0)).context)
        assertEquals(0, more.apply(Entries("%1", reset = true)).context)
    }
}
