package sh.ahmd.wallflower.ui.files

import org.junit.Test
import org.junit.Assert.assertEquals

class StepsTest {
    @Test
    fun stepsDownFromTheTopWithTheHomeAsTilde() {
        assertEquals(listOf("/" to "/", "~" to "/Users/me", "dev" to "/Users/me/dev"), stepsOf("/Users/me/dev"))
        assertEquals(listOf("/" to "/", "opt" to "/opt", "homebrew" to "/opt/homebrew"), stepsOf("/opt/homebrew"))
        assertEquals(listOf("/" to "/", "Users" to "/Users"), stepsOf("/Users"))
        assertEquals(listOf("/" to "/"), stepsOf("/"))
        assertEquals("~", nameOf("/home/me"))
        assertEquals("~", nameOf("~"))
    }
}
