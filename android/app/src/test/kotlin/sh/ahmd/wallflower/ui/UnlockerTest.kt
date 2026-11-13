package sh.ahmd.wallflower.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UnlockerTest {
    private var needed = true
    private val prompts = mutableListOf<(Boolean) -> Unit>()
    private val done = mutableListOf<Boolean>()
    private val unlocker = Unlocker({ needed }) { prompts += it }

    @Test
    fun asksByItselfOnceAtATime() {
        unlocker.ask { done += it }
        unlocker.ask { done += it } // the key locked again, or the screen came back, while it's up
        assertEquals(1, prompts.size)
        prompts.single()(true)
        assertEquals(listOf(true), done)
    }

    @Test
    fun turnedDownItWaitsForTheNextVisitOrATap() {
        unlocker.ask { done += it }
        prompts.last()(false)
        unlocker.ask { done += it } // the prompt going made the app resume
        assertEquals(1, prompts.size)
        unlocker.unlock { done += it } // a tap
        assertEquals(2, prompts.size)
        prompts.last()(false)
        unlocker.left()
        unlocker.ask { done += it }
        assertEquals(3, prompts.size)
        assertEquals(listOf(false, false), done)
    }

    @Test
    fun aKeyThatCanSignNeedsNoAsking() {
        needed = false // the phone was unlocked just now, which counts
        unlocker.ask { done += it }
        assertEquals(0, prompts.size)
        assertEquals(listOf(true), done)
    }
}
