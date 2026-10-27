package sh.ahmd.wallflower

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import sh.ahmd.wallflower.program.Programs

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
    fun inTheTerminalReturnIsANewLineWhereTheProgramTakesSeveral() {
        val claude = bar.profileFor("claude")
        assertEquals(listOf("C-j"), claude.returnKeys(Programs.of("claude")))
        assertEquals(listOf("Enter"), claude.sendKeys())
        assertEquals(listOf("Enter"), bar.profileFor("zsh").returnKeys(Programs.of("zsh")))
        // What the user picked wins, and a bar saved before there was a choice leaves it to the program.
        assertEquals(listOf("Enter"), claude.copy(returnKey = listOf("Enter")).returnKeys(Programs.of("claude")))
        val saved = Json.decodeFromString<KeyBar>("""{"profiles":[{"name":"c","programs":["claude"],"keys":[]}],"default":{"name":"d","keys":[]}}""")
        assertEquals(listOf("C-j"), saved.profileFor("claude").returnKeys(Programs.of("claude")))
    }

    @Test
    fun survivesStorage() {
        assertEquals(bar, Json.decodeFromString<KeyBar>(Json.encodeToString(bar)))
        // Settings saved before keys could be sticky, or with the old zoomed-out profile, still load.
        val old = """{"profiles":[],"default":{"name":"d","keys":[{"label":"pfx","keys":["C-f"]}]},"zoomedOut":null}"""
        assertEquals(BarKey("pfx", listOf("C-f")), Json { ignoreUnknownKeys = true }.decodeFromString<KeyBar>(old).default.keys[0])
    }
}
