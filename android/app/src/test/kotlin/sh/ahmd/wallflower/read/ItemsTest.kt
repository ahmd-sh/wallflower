package sh.ahmd.wallflower.read

import org.junit.Assert.assertEquals
import org.junit.Test

class ItemsTest {
    private fun user(id: String) = Entry.Said(id, Party.User, id)
    private fun reply(id: String) = Entry.Said(id, Party.Program, id)
    private fun step(id: String, output: String = "") = Entry.Step(id, "Bash", "ls $id", output, status = Entry.Step.Status.Done)

    /** Items as short strings: said ids (* for a turn's last reply), steps in brackets. */
    private fun shape(entries: List<Entry>) = items(entries).joinToString(" ") { i ->
        when (i) {
            is Item.Said -> i.entry.id + if (i.last) "*" else ""
            is Item.Steps -> i.entries.joinToString(",", "[", "]") { it.id }
        }
    }

    @Test
    fun stepsBetweenWhatsSaidFoldIntoOne() {
        assertEquals("u1 [t1,th,t2] r1*", shape(listOf(user("u1"), step("t1"), Entry.Thought("th", "hmm"), step("t2"), reply("r1"))))
    }

    @Test
    fun eachTurnsLastReplyIsMarked() {
        assertEquals("u1 r1 [t] r2* u2 r3*", shape(listOf(user("u1"), reply("r1"), step("t"), reply("r2"), user("u2"), reply("r3"))))
    }

    @Test
    fun aNoteOrPictureAfterTheReplyLeavesItTheLastWord() {
        val picture = Entry.Picture("p", Party.Program, "image/png", 2)
        assertEquals("u1 r1* n p", shape(listOf(user("u1"), reply("r1"), Entry.Note("n", "interrupted"), picture)))
    }

    @Test
    fun aTurnStillGoingEndsWithItsSteps() {
        assertEquals("u1 r1* u2 [t]", shape(listOf(user("u1"), reply("r1"), user("u2"), step("t"))))
    }

    @Test
    fun aTraceSaysWhatRanAndWhatCameOfIt() {
        val edit = Entry.Step("e", "Edit", "/x.go", "updated", diff = "@@ -1 +1 @@\n-a\n+b", status = Entry.Step.Status.Done)
        assertEquals(
            "Bash: ls t\nfine\n\nthinking: hmm\n\nEdit: /x.go\n@@ -1 +1 @@\n-a\n+b",
            trace(listOf(step("t", "fine"), Entry.Thought("th", "hmm"), edit)),
        )
    }
}
