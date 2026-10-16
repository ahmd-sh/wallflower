package sh.ahmd.wallflower

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyBarTest {
    private val bar = KeyBar.defaults

    @Test
    fun theProgramPicksTheProfile() {
        assertEquals("claude code", bar.profileFor("claude").name)
        assertEquals("shell", bar.profileFor("ZSH").name)
        assertEquals("default", bar.profileFor("hntui").name)
        assertEquals("default", bar.profileFor("").name)
    }

    @Test
    fun theFirstMatchWins() {
        val both = bar.copy(profiles = listOf(KeyProfile("a", listOf("zsh"), emptyList()), KeyProfile("b", listOf("zsh"), emptyList())))
        assertEquals("a", both.profileFor("zsh").name)
    }

    @Test
    fun zoomedOutWinsOnlyWhenZoomedOutAndSet() {
        assertEquals("shell", bar.profileFor("zsh", zoomedOut = true).name)
        val zoomed = bar.copy(zoomedOut = KeyProfile("overview", keys = emptyList()))
        assertEquals("overview", zoomed.profileFor("zsh", zoomedOut = true).name)
        assertEquals("shell", zoomed.profileFor("zsh").name)
    }

    @Test
    fun claudeStopsWithEscapeAndShellsWithCtrlC() {
        assertEquals(listOf("Escape"), bar.profileFor("claude").stop)
        assertEquals(listOf("C-c"), bar.profileFor("zsh").stop)
    }

    @Test
    fun survivesStorage() {
        assertEquals(bar, Json.decodeFromString<KeyBar>(Json.encodeToString(bar)))
    }
}
