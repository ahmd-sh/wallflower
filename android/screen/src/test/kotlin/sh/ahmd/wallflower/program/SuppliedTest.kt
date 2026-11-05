package sh.ahmd.wallflower.program

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SuppliedTest {
    @After
    fun forget() = Programs.supply(emptyMap())

    @Test
    fun aPluginsProgramIsWhatItsDataSaysAndCantTakeABuiltinsPlace() {
        val aider = Supplied(KeyDefaults("aider", listOf("Escape")), listOf(Command("/undo", "undo")), listOf("M-Enter"), listOf("C-u"))
        Programs.supply(mapOf("Aider" to aider, "claude" to aider))
        assertSame(aider, Programs.of("aider"))
        assertSame(aider, Programs.supplied("aider"))
        assertSame(Claude, Programs.of("claude"))
        assertNull(Programs.supplied("claude"))
        assertEquals(listOf("M-Enter"), Programs.of("aider").newlineKeys)
        assertEquals(listOf("/undo"), Programs.of("aider").commands.map { it.text })
        Programs.supply(emptyMap())
        assertNull(Programs.supplied("aider"))
    }
}
