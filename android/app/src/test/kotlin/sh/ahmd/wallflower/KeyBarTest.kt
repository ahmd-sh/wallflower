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
    fun stickyKeysGoWithTheNextKey() {
        val pfx = KeyBar.common.first { it.label == "pfx" }
        val ctrl = KeyBar.common.first { it.label == "ctrl" }
        assertEquals(BarKey("pfx j", listOf("prefix", "j"), bindings = true), pfx.then(BarKey.typed('j')))
        assertEquals(listOf("C-j"), ctrl.then(BarKey.typed('j')).keys)
        assertEquals(listOf("C-Up"), ctrl.then(KeyBar.common.first { it.label == "↑" }).keys)
        assertEquals(listOf("Space"), BarKey.typed(' ').keys)
    }

    @Test
    fun clearKeysFollowTheProgram() {
        assertEquals(listOf("C-u"), bar.profileFor("claude").clearKeys())
        assertEquals(listOf("C-x", "C-u"), bar.profileFor("zsh").clearKeys())
        assertEquals(emptyList<String>(), bar.profileFor("hntui").clearKeys())
        assertEquals(listOf("Escape", "S"), KeyProfile("vi", listOf("zsh"), emptyList(), clear = listOf("Escape", "S")).clearKeys())
    }

    @Test
    fun survivesStorage() {
        assertEquals(bar, Json.decodeFromString<KeyBar>(Json.encodeToString(bar)))
        // Settings saved before keys could be sticky still load.
        val old = """{"profiles":[],"default":{"name":"d","keys":[{"label":"pfx","keys":["C-f"]}]}}"""
        assertEquals(BarKey("pfx", listOf("C-f")), Json.decodeFromString<KeyBar>(old).default.keys[0])
    }
}
