package sh.ahmd.wallflower.ui

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.ahmd.wallflower.program.Mode
import sh.ahmd.wallflower.program.Program
import sh.ahmd.wallflower.program.Recall
import sh.ahmd.wallflower.read.Entry
import sh.ahmd.wallflower.read.Reading
import sh.ahmd.wallflower.screen.InputLine
import sh.ahmd.wallflower.screen.Prompt
import sh.ahmd.wallflower.screen.Screen

/** A pane whose program goes round [ring] on shift+tab, as its [screen] shows, unless [stuck]. */
private class Moding(val screen: PaneScreen, private val ring: List<String>, private val goesRound: Boolean = true) : PaneHand {
    var pressed = 0
    var stuck = false

    override val program = object : Program {
        override fun line(screen: Screen): InputLine? = null
        override val clearKeys = emptyList<String>()
        override val nextMode = if (goesRound) listOf("BTab") else null
    }

    init {
        screen.line = InputLine("", 1)
        screen.mode = mode(ring[0])
    }

    override fun keys(keys: List<String>, bindings: Boolean) {
        pressed++
        if (stuck) return
        val at = ring.indexOf(screen.mode!!.name)
        screen.mode = mode(ring[(at + 1) % ring.size])
    }

    override fun recall(message: Entry.Said, reading: Reading, line: InputLine?): Recall? = null

    override fun settled(prompt: Prompt, line: InputLine?, held: String?) = Unit
}

private fun mode(name: String) = Mode(name, "[$name]")

class ModeWalkTest {
    private val screen = PaneScreen()

    @Test
    fun aModeIsSteppedToAKeyAtATime() = runTest {
        val pane = Moding(screen, listOf("default", "accept", "plan"))
        var steps = 0
        assertEquals(true, ModeWalk(screen).walk("plan", pane) { steps++ })
        assertEquals(2, pane.pressed)
        assertEquals(1, steps) // accept, on the way
        assertEquals("plan", screen.mode?.name)
    }

    @Test
    fun theWayRoundIsLearnedAsItsWalked() = runTest {
        val pane = Moding(screen, listOf("default", "accept", "plan"))
        val walk = ModeWalk(screen)
        walk.walk("default", pane) // already there
        assertEquals(0, pane.pressed)
        walk.walk("plan", pane)
        walk.walk("default", pane)
        assertEquals(listOf("default", "accept", "plan"), walk.modes(pane).map { it.name })
    }

    @Test
    fun aModeNotRoundIsGivenUpOnceItsBackWhereItStarted() = runTest {
        val pane = Moding(screen, listOf("default", "accept"))
        assertEquals(false, ModeWalk(screen).walk("plan", pane))
        assertEquals(2, pane.pressed)
        assertEquals("default", screen.mode?.name)
    }

    @Test
    fun nothingGoesWhileSomethingsOverItsLine() = runTest {
        val pane = Moding(screen, listOf("default", "plan"))
        screen.line = null // a menu over it, which would take the key
        assertEquals(false, ModeWalk(screen).walk("plan", pane))
        assertEquals(0, pane.pressed)
    }

    @Test
    fun aStepThatDoesntShowEndsTheWalk() = runTest {
        val pane = Moding(screen, listOf("default", "plan")).apply { stuck = true }
        assertFalse(ModeWalk(screen).walk("plan", pane)!!)
        assertEquals(1, pane.pressed)
    }

    @Test
    fun aProgramWithoutModesIsntWalked() = runTest {
        val pane = Moding(screen, listOf("default"), goesRound = false)
        assertNull(ModeWalk(screen).walk("plan", pane))
        assertTrue(ModeWalk(screen).modes(pane).isEmpty())
    }
}
