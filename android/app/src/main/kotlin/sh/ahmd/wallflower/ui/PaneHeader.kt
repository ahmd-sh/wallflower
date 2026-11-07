package sh.ahmd.wallflower.ui

import sh.ahmd.wallflower.protocol.Pane

/**
 * The icon for what a pane runs: by its [program], if that's one wallflower
 * ships or its id names a program below, else by its [command]'s name; a
 * shell's if neither says.
 */
internal fun glyphOf(program: String?, command: String): Glyph =
    program?.lowercase()?.let { PROGRAM_GLYPHS[it] ?: glyphNamed(it) } ?: glyphNamed(command) ?: Glyph.Terminal

/** The icon for a command by its name, without a path or a version on the end (python3.12); null if it isn't one below. */
private fun glyphNamed(command: String): Glyph? {
    val name = command.trim().substringBefore(' ').substringAfterLast('/').lowercase()
    return COMMAND_GLYPHS[name] ?: COMMAND_GLYPHS[name.trimEnd { it.isDigit() || it == '.' }]
}

private val PROGRAM_GLYPHS = mapOf("claude" to Glyph.Spark, "zsh" to Glyph.Terminal, "shell" to Glyph.Terminal)

/** Programs' icons, by the names their commands go by. */
private val COMMAND_GLYPHS: Map<String, Glyph> = listOf(
    Glyph.Spark to "claude codex opencode aider gemini goose crush amp copilot cursor-agent",
    Glyph.Edit to "nvim vim vi view vimdiff hx helix emacs emacsclient nano pico micro kak",
    Glyph.Hexagon to "node npm npx pnpm pnpx yarn bun bunx deno vite next next-server nodemon tsx ts-node tsc turbo nx expo vitest jest",
    Glyph.Snake to "python ipython bpython ptpython uv uvx pip pipx poetry pdm hatch conda mamba jupyter pytest",
    Glyph.Branch to "git lazygit tig gitui gitu gh jj jjui lazyjj",
    Glyph.Pulse to "htop btop top atop bpytop gotop btm glances nvtop",
    Glyph.Globe to "ssh mosh mosh-client et autossh telnet",
    Glyph.Boxes to "docker docker-compose podman lazydocker kubectl k9s helm ctop",
    Glyph.Folder to "yazi ranger lf nnn vifm mc broot xplr",
    Glyph.Database to "psql mysql sqlite3 redis-cli mongosh mongo pgcli mycli litecli duckdb usql",
    Glyph.Book to "less more most man bat glow tldr info",
    Glyph.Hammer to "make gmake cmake ninja cargo rustc go gradle mvn sbt mill bazel xcodebuild swift zig gcc clang just java",
).flatMap { (glyph, names) -> names.split(' ').map { it to glyph } }.toMap()

/** What each of a window's [panes] is called in its header, by id: its directory (see [placeNames]), or its command if it has none. */
internal fun headerNames(panes: List<Pane>): Map<String, String> {
    val places = placeNames(panes.map { it.path })
    return panes.associate { it.id to (places[it.path] ?: it.command) }
}

/**
 * What each of [paths] is called beside the others: its last directory,
 * with as many above it as it takes to tell it from theirs. A home
 * (/Users/name, /home/name) is ~, unless two homes' directories would
 * read the same. Empty paths are left out.
 */
internal fun placeNames(paths: Collection<String>): Map<String, String> {
    val full = paths.filter { it.isNotEmpty() }.associateWith(::partsOf)
    val homed = full.mapValues { homed(it.value) }
    val parts = full.mapValues { (path, p) ->
        val h = homed.getValue(path)
        if (full.any { (other, q) -> q != p && homed[other] == h }) p else h
    }
    val all = parts.values.toSet()
    return parts.mapValues { (_, p) ->
        val k = (1..p.size).firstOrNull { k -> all.none { it != p && it.takeLast(k) == p.takeLast(k) } } ?: p.size
        p.takeLast(k).joinToString("/").ifEmpty { "/" }
    }
}

/** A path's directories from the top, after "" for the root if it starts there. */
private fun partsOf(path: String): List<String> =
    (if (path.startsWith('/')) listOf("") else emptyList()) + path.split('/').filter { it.isNotEmpty() }

/** [parts] with ~ in place of the home they start in, if they do. */
private fun homed(parts: List<String>): List<String> =
    if (parts.size >= 3 && parts[0] == "" && parts[1] in HOMES) listOf("~") + parts.drop(3) else parts

private val HOMES = setOf("Users", "home")
