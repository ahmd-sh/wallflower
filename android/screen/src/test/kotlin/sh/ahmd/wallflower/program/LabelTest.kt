package sh.ahmd.wallflower.program

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelTest {
    @Test
    fun claudesTabIsItsConversationsNameWithoutTheSpinner() {
        assertEquals("wallflower-night", Claude.label("✳ wallflower-night"))
        assertEquals("Fix the tests", Claude.label("⠂ Fix the tests"))
        assertEquals(null, Claude.label("✳"))
        assertEquals(null, Programs.of("zsh").label("~/dev"))
    }
}
