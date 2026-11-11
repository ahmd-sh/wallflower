package sh.ahmd.wallflower

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.ahmd.wallflower.program.Command
import sh.ahmd.wallflower.program.KeyDefaults
import sh.ahmd.wallflower.program.Programs
import sh.ahmd.wallflower.program.Shortcut
import sh.ahmd.wallflower.program.Supplied
import sh.ahmd.wallflower.ui.ProfileRef
import sh.ahmd.wallflower.ui.shippedFor
import sh.ahmd.wallflower.ui.composer.paged
import sh.ahmd.wallflower.ui.shortcutsSummary

class ShortcutsTest {
    private val bar = KeyBar.defaults

    @Test
    fun claudeShipsAFewToShowWhatTheyCanBe() {
        val claude = bar.profileFor("claude", "claude").shortcutsFor(Programs.of("claude"))
        assertEquals(listOf("transcript", "todos", "/compact ⏎", "/btw"), claude.map { it.label })
        assertEquals(listOf("C-o"), claude.first().keys)
        assertEquals(listOf("/compact", "Enter"), claude.first { it.label == "/compact ⏎" }.keys)
        // A command's label is its keys', so it shows as them.
        assertTrue(claude.first { it.label == "/compact ⏎" }.ownLabel)
        assertFalse(claude.first().ownLabel)
    }

    @Test
    fun shellsShipCommandsThatWorkInAny() {
        val zsh = bar.profileFor("zsh", "zsh").shortcutsFor(Programs.of("zsh"))
        assertEquals(listOf("clear ⏎", "cd - ⏎", "!! ⏎", "suspend"), zsh.map { it.label })
        assertEquals(zsh, bar.profileFor("bash", "shell").shortcutsFor(Programs.of("shell")))
        assertEquals(emptyList<BarKey>(), bar.default.shortcutsFor(Programs.of("hntui")))
    }

    @Test
    fun theUsersComeAfterTheProgramsTheyDidntHide() {
        val mine = BarKey("review", listOf("/review", "Enter"))
        val claude = bar.profileFor("claude", "claude").copy(shortcuts = listOf(mine), hidden = listOf("todos", "/btw"))
        val offered = claude.shortcutsFor(Programs.of("claude"))
        assertEquals(mine, offered.last())
        assertFalse(offered.any { it.label == "todos" || it.label == "/btw" })
        assertEquals(4 - 2 + 1, offered.size)
    }

    @Test
    fun aPluginsShortcutsComeWithIt() {
        val aider = Supplied(KeyDefaults("aider", listOf("Escape", "shortcuts")), listOf(Command("/undo", "")), null, emptyList(), listOf(Shortcut(listOf("/undo", "Enter")), Shortcut(listOf("C-c", "C-c"), "quit")))
        Programs.supply(mapOf("aider" to aider))
        try {
            val withMine = bar.copy(default = bar.default.copy(shortcuts = listOf(BarKey("hi", listOf("hi")))))
            val p = withMine.profileFor("python3", "aider")
            assertTrue(p.keys[1].shortcuts) // the shortcuts key, by name
            // Until it's a profile of its own, what's the default's is its.
            assertEquals(listOf("/undo ⏎", "quit", "hi"), p.shortcutsFor(Programs.of("aider")).map { it.label })
        } finally {
            Programs.supply(emptyMap())
        }
    }

    @Test
    fun theShortcutsKeyOffersThemAndSendsNothing() {
        val key = KeyBar.common.first { it.shortcuts }
        assertEquals(KeyBar.SHORTCUTS, key)
        assertEquals(emptyList<String>(), key.keys)
        assertEquals("shortcuts", KeyBar.nameOf(key))
        assertTrue(key.ownLabel)
    }

    @Test
    fun keysAreTheirNamesAndTheRestIsText() {
        for (k in listOf("Enter", "Escape", "BTab", "C-o", "M-Enter", "C-M-Left", "S-Up", "F5", "F12", "M-.", "C-_", "prefix", "DC")) assertTrue(k, KeyBar.isKey(k))
        for (t in listOf("y", "1", "/compact", ":w", "fg", "cd -", "C-", "F13", "enter", "Ctrl-c")) assertFalse(t, KeyBar.isKey(t))
    }

    @Test
    fun theWordsBetweenKeysAreOneText() {
        assertEquals(listOf("/model opus", "Enter"), KeyBar.keysOf("/model opus Enter"))
        assertEquals(listOf("Escape", ":w", "Enter"), KeyBar.keysOf("Escape :w Enter"))
        assertEquals(listOf("C-x", "C-s"), KeyBar.keysOf("  C-x   C-s "))
        assertEquals(listOf("prefix", "c"), KeyBar.keysOf("prefix c"))
        assertEquals(listOf("/btw"), KeyBar.keysOf("/btw"))
        assertEquals(emptyList<String>(), KeyBar.keysOf("   "))
        // What a shortcut sends comes back as written.
        val keys = listOf("Escape", "git commit -m wip", "Enter")
        assertEquals(keys, KeyBar.keysOf(keys.joinToString(" ")))
    }

    @Test
    fun textGoesWithItsEnterAMomentAfter() {
        assertEquals(listOf(Step.Keys(listOf("C-o"))), KeyBar.steps(listOf("C-o")))
        assertEquals(listOf(Step.Text("/compact", enter = true)), KeyBar.steps(listOf("/compact", "Enter")))
        assertEquals(listOf(Step.Text("/btw", enter = false)), KeyBar.steps(listOf("/btw")))
        assertEquals(
            listOf(Step.Keys(listOf("Escape", "Escape")), Step.Text(":wq", enter = true), Step.Keys(listOf("Enter")), Step.Text("ls", enter = false)),
            KeyBar.steps(listOf("Escape", "Escape", ":wq", "Enter", "Enter", "ls")),
        )
        assertEquals(listOf(Step.Keys(listOf("C-e", "C-u")), Step.Text("fg", enter = true)), KeyBar.steps(listOf("C-e", "C-u", "fg", "Enter")))
    }

    @Test
    fun aProfilesPageListsWhatItsProgramsShip() {
        val claude = bar.profiles.indexOfFirst { "claude" in it.programs }
        val ref = ProfileRef.At(claude)
        assertEquals(4, shippedFor(bar, ref, bar.profiles[claude], null, null).size)
        // zsh's and shell's are the same, once.
        val shell = bar.profiles.indexOfFirst { "zsh" in it.programs }
        assertEquals(4, shippedFor(bar, ProfileRef.At(shell), bar.profiles[shell], null, null).size)
        // A profile by command (bash) gets the focused pane's program's, if the pane's is it.
        val mine = bar.copy(profiles = listOf(KeyProfile("mine", listOf("bash"), emptyList())))
        val at = ProfileRef.At(0)
        assertEquals(4, shippedFor(mine, at, mine.profiles.last(), "bash", "shell").size)
        assertEquals(0, shippedFor(mine, at, mine.profiles.last(), "vim", null).size)
        // The default's are the focused pane's, if it has no profile.
        assertEquals(0, shippedFor(bar, ProfileRef.Default, bar.default, "hntui", null).size)
    }

    @Test
    fun aProfilesShortcutsSayHowManyInALine() {
        val claude = bar.profileFor("claude", "claude")
        val shipped = claude.shortcutsFor(Programs.of("claude"))
        assertEquals("4", shortcutsSummary(claude, shipped))
        assertEquals("3 · 1 yours · 2 hidden", shortcutsSummary(claude.copy(shortcuts = listOf(BarKey("x", listOf("x"))), hidden = listOf("todos", "/btw")), shipped))
        assertEquals("none yet", shortcutsSummary(bar.default, emptyList()))
    }

    @Test
    fun overThreeRowsTheyGoOnPages() {
        // Rows 100 wide, 10 apart: two of 40 fit one, and one of 120 has one to itself.
        assertEquals(
            listOf(listOf(listOf(0, 1), listOf(2), listOf(3, 4)), listOf(listOf(5))),
            paged(listOf(40, 40, 120, 40, 40, 40), 100, 10, 3),
        )
        assertEquals(listOf(listOf(listOf(0))), paged(listOf(40), 100, 10, 3))
    }

    @Test
    fun survivesStorage() {
        val mine = bar.copy(default = bar.default.copy(shortcuts = listOf(BarKey("new window", listOf("C-b", "c"), bindings = true)), hidden = listOf("todos")))
        assertEquals(mine, Json.decodeFromString<KeyBar>(Json.encodeToString(mine)))
        // A bar saved before there were shortcuts loads with none of the user's, and none hidden.
        val old = Json.decodeFromString<KeyBar>("""{"profiles":[{"name":"c","programs":["claude"],"keys":[{"label":"^o","keys":["C-o"]}]}],"default":{"name":"d","keys":[]}}""")
        assertEquals(emptyList<BarKey>(), old.profiles[0].shortcuts)
        assertEquals(emptyList<String>(), old.profiles[0].hidden)
        assertFalse(old.profiles[0].keys[0].shortcuts)
        assertEquals(4, old.profileFor("claude", "claude").shortcutsFor(Programs.of("claude")).size)
    }
}
