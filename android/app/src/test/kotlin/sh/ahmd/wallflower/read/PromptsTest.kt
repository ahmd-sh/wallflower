package sh.ahmd.wallflower.read

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromptsTest {
    @Test
    fun aPromptsMarkIsItsLastWord() {
        assertEquals("➜", markOf("dotfiles ➜ "))
        assertEquals("$", markOf("$"))
        assertNull(markOf("   "))
    }

    @Test
    fun whatFollowsTheMarkUpToTheRightPrompt() {
        val rows = listOf("dotfiles ➜ git status --short               main[⇡]  (region: ca-central-1)")
        assertEquals("git status --short", typedAfter("➜", rows, 80))
    }

    @Test
    fun aPromptNothingWasTypedAtIsEmpty() {
        assertEquals("", typedAfter("➜", listOf("dotfiles ➜                       main "), 40))
        assertEquals("", typedAfter("➜", listOf("dotfiles ➜"), 40))
    }

    @Test
    fun aRightPromptWrappedOntoRowsBelowIsLeftOut() {
        val rows = listOf("dotfiles ➜ echo hi", "                  main[⇡]  (region: ca-", "central-1)")
        assertEquals("echo hi", typedAfter("➜", rows, 40))
    }

    @Test
    fun typingThatRunsToTheEdgeGoesOnIntoTheNextRow() {
        val rows = listOf("~ ➜ echo a long l", "ine of text")
        assertEquals("echo a long line of text", typedAfter("➜", rows, 17))
    }

    @Test
    fun whatAPromptSaysBeforeItsMark() {
        assertEquals("dotfiles/zsh", promptBefore("➜", listOf("dotfiles/zsh ➜ ls     main")))
        assertEquals("dotfiles", withoutMark("dotfiles ➜ ", "➜"))
        assertEquals("dotfiles ➜", withoutMark("dotfiles ➜", null))
    }

    @Test
    fun rowsWithoutTheMarkCantBeRead() {
        assertNull(typedAfter("➜", listOf("$ ls"), 20))
        assertNull(typedAfter("➜", listOf("a➜b"), 20)) // not a prompt's mark: no space after it
    }
}
