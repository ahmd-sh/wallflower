package sh.ahmd.wallflower.read

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.ahmd.wallflower.program.Claude
import sh.ahmd.wallflower.program.Effect
import sh.ahmd.wallflower.protocol.Entries
import sh.ahmd.wallflower.protocol.Entry as WireEntry
import sh.ahmd.wallflower.protocol.Pane
import sh.ahmd.wallflower.screen.Prompt

class ReadingTest {
    private fun text(id: String) = WireEntry(id, "program", "said", id)

    /** Whether a message is the one Claude's /rewind quotes as [quote], as restoring it says. */
    private fun quoting(quote: String): (String) -> Boolean {
        val restore = Prompt.Option(1, "Restore conversation", "", null, current = true, muted = false)
        return (Claude.picked(Prompt(emptyList(), listOf(restore), emptyList(), quote), restore) as Effect.Rewound).said
    }

    @Test
    fun theLatestMessageBackInTheProgramsLineIsTakenBack() {
        val r = Reading().apply(
            Entries("%1", reset = true, entries = listOf(WireEntry("u1", "user", "said", "fix it"), text("a"), WireEntry("u2", "user", "said", "now write\nthe tests"))),
        )
        assertEquals(listOf("u1", "a"), r.takenBack("now write the tests").shown.map { it.id })
        assertEquals(r, r.takenBack("fix it")) // a short earlier one typed again is just typed
        assertEquals(r, r.takenBack(""))
        val long = Reading().apply(
            Entries("%1", reset = true, entries = listOf(WireEntry("u1", "user", "said", "rename the gateway's options to settings"), text("a"), WireEntry("u2", "user", "said", "go on"))),
        )
        assertEquals(emptyList<String>(), long.takenBack("rename the gateway's options to settings").shown.map { it.id }) // rewound to on the Mac
    }

    @Test
    fun aUsersStepIsACommandANotesOutputItsDetailAndTheTitleStays() {
        val r = Reading()
            .apply(
                Entries(
                    "%1", reset = true, title = "fix the tests",
                    entries = listOf(
                        WireEntry("c", "user", "step", "~/x", name = "/add-dir", status = "done", output = "Added ~/x"),
                        WireEntry("n", "user", "note", "Conversation compacted", output = "the summary"),
                    ),
                ),
            )
            .apply(Entries("%1", context = 5))
        assertEquals(listOf(Entry.Command("c", "/add-dir", "~/x", "Added ~/x"), Entry.Note("n", "Conversation compacted", "the summary")), r.entries)
        assertEquals("fix the tests", r.title)
        assertEquals("tests", r.apply(Entries("%1", title = "tests")).title)
        assertEquals(null, r.apply(Entries("%1", reset = true)).title)
    }

    @Test
    fun entriesReplaceTheirsByIdOrComeLast() {
        val r = Reading()
            .apply(Entries("%1", reset = true, more = true, entries = listOf(text("a"), WireEntry("t", "program", "step", "ls", name = "Bash", status = "running"))))
            .apply(Entries("%1", entries = listOf(WireEntry("t", "program", "step", "ls", name = "Bash", status = "done", output = "x", outputCut = 3), text("b"))))
        assertEquals(listOf("a", "t", "b"), r.entries.map { it.id })
        assertEquals(Entry.Step("t", "Bash", "ls", "x", status = Entry.Step.Status.Done, outputCut = 3), r.entries[1])
        assertEquals(true, r.more)
        assertEquals(true, r.loaded)
    }

    @Test
    fun theWiresKindsBecomeTheReadModels() {
        val r = Reading().apply(
            Entries(
                "%1", reset = true,
                entries = listOf(
                    WireEntry("u", "user", "said", "hi"),
                    WireEntry("th", "program", "thought", "hmm"),
                    WireEntry("f", "program", "step", "rm x", name = "Bash", status = "failed", diff = "@@", diffCut = 2),
                    WireEntry("i", "user", "picture", mime = "image/png", size = 2),
                    WireEntry("n", "user", "note", "compacted"),
                    WireEntry("z", "program", "something newer", "shown as a note"),
                ),
            ),
        )
        assertEquals(
            listOf(
                Entry.Said("u", Party.User, "hi"),
                Entry.Thought("th", "hmm"),
                Entry.Step("f", "Bash", "rm x", diff = "@@", status = Entry.Step.Status.Failed, diffCut = 2),
                Entry.Picture("i", Party.User, "image/png", 2),
                Entry.Note("n", "compacted"),
                Entry.Note("z", "shown as a note"),
            ),
            r.entries,
        )
    }

    @Test
    fun rewoundItHidesTheMessageQuotedAndWhatFollowedUntilAReset() {
        val said = WireEntry("u2", "user", "said", "Use the Write tool to create /private/tmp/claude-502/rw.txt containing hi")
        var r = Reading().apply(Entries("%1", reset = true, entries = listOf(WireEntry("u1", "user", "said", "one"), text("a1"), said, text("a2"))))
        val all = r
        r = r.rewoundTo(quoting("Use the Write tool to create /private/tmp/claude -502/rw.txt containing hi"))
        assertEquals(listOf("u1", "a1"), r.shown.map { it.id })
        assertEquals(listOf("u1", "a1"), r.apply(Entries("%1", context = 5)).shown.map { it.id })
        assertEquals(listOf("u1", "a1"), r.rewoundTo(quoting("…create /private/tmp/claude…")).shown.map { it.id })
        assertEquals(listOf("u1", "a1", "u2", "a2"), r.apply(Entries("%1", reset = true, entries = listOf(WireEntry("u1", "user", "said", "one"), text("a1"), said, text("a2")))).shown.map { it.id })
        assertEquals(4, all.rewoundTo(quoting("something else")).shown.size)
    }

    @Test
    fun aRepeatedMessageRewoundHidesFromItsLatestSoNothingStillSaidIsHidden() {
        // The quote can't tell two "yes"es apart: hiding from the later one may leave some of what was
        // taken back showing until the transcript says (the next message), but never hides what's still there.
        val entries = listOf(WireEntry("u1", "user", "said", "yes"), text("a1"), WireEntry("u2", "user", "said", "yes"), text("a2"))
        val r = Reading().apply(Entries("%1", reset = true, entries = entries)).rewoundTo(quoting("yes"))
        assertEquals(listOf("u1", "a1"), r.shown.map { it.id })
    }

    @Test
    fun aResetStartsOver() {
        val r = Reading()
            .apply(Entries("%1", reset = true, entries = listOf(text("a"))))
            .apply(Entries("%1", reset = true, error = "the conversation ended"))
        assertEquals(emptyList<Entry>(), r.entries)
        assertEquals("the conversation ended", r.problem)
    }

    @Test
    fun theOldestGoPastTheLimit() {
        val r = Reading()
            .apply(Entries("%1", reset = true, entries = List(Reading.MAX_ENTRIES) { text("$it") }))
            .apply(Entries("%1", entries = listOf(text("new"))))
        assertEquals(Reading.MAX_ENTRIES, r.entries.size)
        assertEquals("1", r.entries.first().id)
        assertEquals("new", r.entries.last().id)
        assertEquals(true, r.more)
    }

    @Test
    fun whatTheProgramIsDoingIsItsPanes() {
        assertEquals(Activity.Working, activityOf(Pane("%1", 0, true, 56, 20, command = "claude", activity = Pane.WORKING)))
        assertEquals(null, activityOf(Pane("%1", 0, true, 56, 20, command = "zsh")))
    }

    @Test
    fun theContextKeepsUntilItChangesAndGoesWithANewConversation() {
        val r = Reading()
            .apply(Entries("%1", reset = true, entries = listOf(text("a")), context = 1000))
            .apply(Entries("%1", entries = listOf(text("b"))))
        assertEquals(1000, r.context)
        val more = r.apply(Entries("%1", context = 2500))
        assertEquals(2500, more.context)
        assertEquals(listOf("a", "b"), more.entries.map { it.id })
        assertEquals(0, more.apply(Entries("%1", context = 0)).context)
        assertEquals(0, more.apply(Entries("%1", reset = true)).context)
    }
}
