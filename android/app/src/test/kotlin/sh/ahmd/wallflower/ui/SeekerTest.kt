package sh.ahmd.wallflower.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import sh.ahmd.wallflower.program.Program
import sh.ahmd.wallflower.program.Recall
import sh.ahmd.wallflower.program.Walk
import sh.ahmd.wallflower.read.Entry
import sh.ahmd.wallflower.read.Party
import sh.ahmd.wallflower.read.Reading
import sh.ahmd.wallflower.screen.InputLine
import sh.ahmd.wallflower.screen.Prompt
import sh.ahmd.wallflower.screen.Screen

/** A pane whose program brings a message back with a command, and walks the prompts [walks] says. */
private class Rewinding(private val recall: Recall? = Recall.Command("/rewind")) : PaneHand {
    val walks = HashMap<Prompt, Walk>()
    val pressed = mutableListOf<List<String>>()
    val settled = mutableListOf<Pair<Prompt, String?>>()

    override val program = object : Program {
        override fun line(screen: Screen): InputLine? = null
        override val clearKeys = emptyList<String>()
        override fun walk(prompt: Prompt, said: String): Walk? = walks[prompt]
    }

    override fun keys(keys: List<String>, bindings: Boolean) {
        pressed += keys
    }

    override fun recall(message: Entry.Said, reading: Reading, line: InputLine?): Recall? = recall

    override fun settled(prompt: Prompt, line: InputLine?, held: String?) {
        settled += prompt to held
    }
}

private fun prompt(name: String) = Prompt(listOf(Prompt.Line(name, false)), emptyList(), emptyList())

private fun said(id: String, by: Party = Party.User) = Entry.Said(id, by, "message $id")

/** The list of the user's messages, its cursor [back] of them from the newest, going [last] back. */
private fun listed(back: Int, last: Int = 5) = Walk.Listed(back, last, up = listOf("Up"), down = listOf("Down"), pick = listOf("Enter"))

@OptIn(ExperimentalCoroutinesApi::class)
class SeekerTest {
    /** Three messages of the user's, with the program's answers between. */
    private val reading = Reading(listOf(said("a"), said("r1", Party.Program), said("b"), said("r2", Party.Program), said("c")))

    @Test
    fun aMessageIsLookedForWhereTheConversationHasItThenCloseBy() {
        assertEquals(listOf(5, 4, 6, 3, 7, 2, 8), Seeker.nearby(5, 10))
        assertEquals(listOf(0, 1, 2, 3), Seeker.nearby(0, 10)) // nothing newer than the newest
        assertEquals(listOf(4, 3, 2, 1), Seeker.nearby(4, 4)) // nor older than the oldest
        assertEquals(emptyList<Int>(), Seeker.nearby(12, 7)) // the list hasn't anything near
    }

    @Test
    fun aMessageBroughtBackByKeysIsRecalledButNotLookedFor() = runTest {
        val seeker = Seeker(backgroundScope)
        seeker.edit(said("b"), Rewinding(Recall.Keys(listOf("Up"))), reading, null)
        assertEquals("message b", seeker.recalled?.text)
        assertNull(seeker.seeking)
    }

    @Test
    fun aCommandsListIsWalkedToTheMessageAndItsConfirmationHeld() = runTest {
        val seeker = Seeker(backgroundScope)
        val pane = Rewinding()
        seeker.edit(said("a"), pane, reading, null)
        assertEquals(Seeker.Seeking("a", "message a", newer = 2), seeker.seeking)

        // The list opens with its cursor below the newest: two newer than "a" is three up, then picked.
        val list = prompt("list")
        pane.walks[list] = listed(back = -1)
        seeker.prompt(list, pane)
        assertEquals(listOf(listOf("Up", "Up", "Up", "Enter")), pane.pressed)
        assertEquals(listOf(2), seeker.seeking?.tried)

        // The same list again, before the keys show: nothing more goes.
        seeker.prompt(list, pane)
        assertEquals(1, pane.pressed.size)

        // What picking asks is about "a": that's the one, held to edit.
        val asks = prompt("restore a?")
        pane.walks[asks] = Walk.Picked(on = true, back = listOf("Escape"))
        seeker.prompt(asks, pane)
        assertNull(seeker.seeking)
        assertEquals("a", seeker.editing)
    }

    @Test
    fun anotherPickedGoesBackToTheListAndTriesCloseBy() = runTest {
        val seeker = Seeker(backgroundScope)
        val pane = Rewinding()
        seeker.edit(said("a"), pane, reading, null)
        val list = prompt("list")
        pane.walks[list] = listed(back = -1)
        seeker.prompt(list, pane)

        // It asks about another message: back to the list, keys alone.
        val other = prompt("restore b?")
        pane.walks[other] = Walk.Picked(on = false, back = listOf("Escape"))
        seeker.prompt(other, pane)
        assertEquals(listOf("Escape"), pane.pressed.last())
        assertEquals(true, seeker.seeking?.back)

        // Back in the list, its cursor where it was (on the third newest): one up from there is the next nearby.
        val again = prompt("list again")
        pane.walks[again] = listed(back = 2)
        seeker.prompt(again, pane)
        assertEquals(listOf("Down", "Enter"), pane.pressed.last())
        assertEquals(listOf(2, 1), seeker.seeking?.tried)
    }

    @Test
    fun aListWithoutTheMessageIsLeftToTheUser() = runTest {
        val seeker = Seeker(backgroundScope)
        val pane = Rewinding()
        seeker.edit(said("a"), pane, reading, null)
        val list = prompt("short list")
        pane.walks[list] = listed(back = -1, last = 0)
        seeker.prompt(list, pane) // a list of one: only that one's near enough
        assertEquals(listOf(0), seeker.seeking?.tried)
        val other = prompt("restore c?")
        pane.walks[other] = Walk.Picked(on = false, back = listOf("Escape"))
        seeker.prompt(other, pane)
        assertNull(seeker.seeking) // nothing else nearby
    }

    @Test
    fun theUsersOwnHandStopsTheWalk() = runTest {
        val seeker = Seeker(backgroundScope)
        seeker.edit(said("a"), Rewinding(), reading, null)
        seeker.handled()
        assertNull(seeker.seeking)
    }

    @Test
    fun lookingGivesUpAWhileAfterTheLastKeys() = runTest {
        val seeker = Seeker(backgroundScope)
        val pane = Rewinding()
        seeker.edit(said("a"), pane, reading, null)
        advanceTimeBy(SEEKS_MS - 1)
        runCurrent()
        assertNotNull(seeker.seeking)

        // Keys sent start the wait again.
        val list = prompt("list")
        pane.walks[list] = listed(back = -1)
        seeker.prompt(list, pane)
        advanceTimeBy(SEEKS_MS - 1)
        runCurrent()
        assertNotNull(seeker.seeking)
        advanceTimeBy(2)
        runCurrent()
        assertNull(seeker.seeking)
    }

    @Test
    fun backInTheListItsWaitedForLonger() = runTest {
        val seeker = Seeker(backgroundScope)
        val pane = Rewinding()
        seeker.edit(said("a"), pane, reading, null)
        val list = prompt("list")
        pane.walks[list] = listed(back = -1)
        seeker.prompt(list, pane)
        val other = prompt("restore b?")
        pane.walks[other] = Walk.Picked(on = false, back = listOf("Escape"))
        seeker.prompt(other, pane)
        advanceTimeBy(SEEKS_MS + 1)
        runCurrent()
        assertNotNull(seeker.seeking)
        advanceTimeBy(SEEKS_BACK_MS - SEEKS_MS)
        runCurrent()
        assertNull(seeker.seeking)
    }

    @Test
    fun whatAPromptDidSettlesInTheLineItLeavesSoonAfter() {
        var now = 1_000L
        val seeker = Seeker(CoroutineScope(Dispatchers.Unconfined)) { now }
        val pane = Rewinding()
        val asks = prompt("restore?")
        seeker.settle(asks, held = null, line = null, actions = pane)
        seeker.settle(null, held = null, line = null, actions = pane) // gone, and the line not read yet
        assertEquals(0, pane.settled.size)
        now += SETTLES_MS - 1
        seeker.settle(null, held = "restored", line = InputLine("restored", 1), actions = pane)
        assertEquals(listOf(asks to null), pane.settled)

        // Long after a prompt went, the line's its own.
        seeker.settle(asks, held = null, line = null, actions = pane)
        seeker.settle(null, held = null, line = null, actions = pane)
        now += SETTLES_MS
        seeker.settle(null, held = "typed", line = InputLine("typed", 1), actions = pane)
        assertEquals(1, pane.settled.size)
    }

    @Test
    fun anotherPaneCarriesNothingOver() = runTest {
        val seeker = Seeker(backgroundScope)
        seeker.edit(said("a"), Rewinding(), reading, null)
        seeker.reset()
        assertNull(seeker.seeking)
        assertNull(seeker.recalled)
        assertNull(seeker.editing)
    }
}
