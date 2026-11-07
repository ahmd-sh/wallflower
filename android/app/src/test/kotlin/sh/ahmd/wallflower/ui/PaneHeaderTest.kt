package sh.ahmd.wallflower.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.ahmd.wallflower.protocol.Pane

class PaneHeaderTest {
    @Test
    fun aDirectoryGoesByItsNameAloneWhenNoOtherSharesIt() {
        assertEquals(
            mapOf("/Users/me/dev/wallflower" to "wallflower", "/Users/me/dev/api" to "api", "/tmp" to "tmp"),
            placeNames(listOf("/Users/me/dev/wallflower", "/Users/me/dev/api", "/tmp")),
        )
    }

    @Test
    fun directoriesOfTheSameNameTakeAsManyAboveThemAsTellThemApart() {
        assertEquals(
            mapOf("/Users/me/dev/api" to "dev/api", "/Users/me/work/api" to "work/api"),
            placeNames(listOf("/Users/me/dev/api", "/Users/me/work/api")),
        )
        assertEquals(
            mapOf("/a/x/api" to "a/x/api", "/b/x/api" to "b/x/api", "/c/api" to "c/api"),
            placeNames(listOf("/a/x/api", "/b/x/api", "/c/api")),
        )
        assertEquals(
            mapOf("/srv/api" to "srv/api", "/srv/api/api" to "api/api"),
            placeNames(listOf("/srv/api", "/srv/api/api")),
        )
    }

    @Test
    fun panesInOneDirectoryShareItsName() {
        assertEquals(
            mapOf("/Users/me/dev/api" to "api", "/Users/me/dev/api/" to "api"),
            placeNames(listOf("/Users/me/dev/api", "/Users/me/dev/api", "/Users/me/dev/api/")),
        )
    }

    @Test
    fun theHomeIsTildeAndTheRootASlash() {
        assertEquals(mapOf("/Users/me" to "~", "/" to "/"), placeNames(listOf("/Users/me", "/")))
        assertEquals(mapOf("/home/me/api" to "~/api", "/home/me/dev/api" to "dev/api"), placeNames(listOf("/home/me/api", "/home/me/dev/api")))
        assertEquals(
            mapOf("/Users/me/api" to "~/api", "/Users/me/dev/api" to "dev/api"),
            placeNames(listOf("/Users/me/api", "/Users/me/dev/api")),
        )
        assertEquals(mapOf("/api" to "/api", "/srv/api" to "srv/api"), placeNames(listOf("/api", "/srv/api")))
        assertEquals(mapOf("/Users" to "Users", "/home" to "home"), placeNames(listOf("/Users", "/home")))
    }

    @Test
    fun twoHomesThatWouldReadTheSameKeepTheirNames() {
        assertEquals(
            mapOf("/Users/me/notes" to "me/notes", "/Users/Shared/notes" to "Shared/notes"),
            placeNames(listOf("/Users/me/notes", "/Users/Shared/notes")),
        )
        assertEquals(mapOf("/Users/me" to "Users/me", "/home/me" to "home/me"), placeNames(listOf("/Users/me", "/home/me")))
    }

    @Test
    fun aPaneWithNoDirectoryGoesByItsCommand() {
        val panes = listOf(pane("%1", "/Users/me/dev/api", "zsh"), pane("%2", "", "htop"), pane("%3", "/Users/me/work/api", "nvim"))
        assertEquals(mapOf("%1" to "dev/api", "%2" to "htop", "%3" to "work/api"), headerNames(panes))
    }

    @Test
    fun theProgramWallflowerKnowsPicksTheIcon() {
        assertEquals(Glyph.Spark, glyphOf("claude", "2.1.319"))
        assertEquals(Glyph.Spark, glyphOf("CLAUDE", "claude"))
        assertEquals(Glyph.Terminal, glyphOf("zsh", "zsh"))
        assertEquals(Glyph.Terminal, glyphOf("shell", "bash"))
    }

    @Test
    fun aProgramWallflowerDoesNotShipGoesByItsIdThenItsCommand() {
        assertEquals(Glyph.Branch, glyphOf("lazygit", "2.40.1"))
        assertEquals(Glyph.Hexagon, glyphOf("mytool", "node"))
        assertEquals(Glyph.Terminal, glyphOf("mytool", "mytool"))
    }

    @Test
    fun commandsGoByTheirFamilies() {
        val cases = mapOf(
            "nvim" to Glyph.Edit, "hx" to Glyph.Edit, "emacs" to Glyph.Edit,
            "node" to Glyph.Hexagon, "pnpm" to Glyph.Hexagon, "bun" to Glyph.Hexagon,
            "python3" to Glyph.Snake, "python3.12" to Glyph.Snake, "Python" to Glyph.Snake, "uv" to Glyph.Snake,
            "git" to Glyph.Branch, "lazygit" to Glyph.Branch, "tig" to Glyph.Branch,
            "htop" to Glyph.Pulse, "btop" to Glyph.Pulse, "top" to Glyph.Pulse,
            "ssh" to Glyph.Globe, "mosh-client" to Glyph.Globe, "et" to Glyph.Globe,
            "docker" to Glyph.Boxes, "k9s" to Glyph.Boxes, "kubectl" to Glyph.Boxes,
            "yazi" to Glyph.Folder, "lf" to Glyph.Folder,
            "psql" to Glyph.Database, "sqlite3" to Glyph.Database, "redis-cli" to Glyph.Database,
            "less" to Glyph.Book, "man" to Glyph.Book, "bat" to Glyph.Book,
            "make" to Glyph.Hammer, "cargo" to Glyph.Hammer, "go" to Glyph.Hammer, "gradle" to Glyph.Hammer,
            "codex" to Glyph.Spark, "opencode" to Glyph.Spark,
        )
        for ((command, glyph) in cases) assertEquals(command, glyph, glyphOf(null, command))
    }

    @Test
    fun aCommandGoesByItsNameAlone() {
        assertEquals(Glyph.Edit, glyphOf(null, "/opt/homebrew/bin/nvim"))
        assertEquals(Glyph.Hexagon, glyphOf(null, "next-server (v15.0.3)"))
    }

    @Test
    fun anythingElseIsAShell() {
        assertEquals(Glyph.Terminal, glyphOf(null, "zsh"))
        assertEquals(Glyph.Terminal, glyphOf(null, "hntui"))
        assertEquals(Glyph.Terminal, glyphOf(null, "2.1.319"))
        assertEquals(Glyph.Terminal, glyphOf(null, ""))
    }

    private fun pane(id: String, path: String, command: String) = Pane(id, 0, false, 80, 24, path = path, command = command)
}
