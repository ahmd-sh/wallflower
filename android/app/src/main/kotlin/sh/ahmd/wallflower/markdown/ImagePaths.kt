package sh.ahmd.wallflower.markdown

/**
 * Pictures a program mentions by their paths in what it says: absolute
 * (/Users/me/shot.png), from home (~/a.png), or relative to where it runs
 * (./out/screen.png, out/screen.png, shot.png). Not parts of URLs; spaces
 * only in code that's a path and nothing else.
 */
object ImagePaths {
    private const val EXTENSIONS = "png|jpe?g|gif|webp|heic|heif|bmp|avif"

    private val inText = Regex(
        // Letters and digits of any script, spelled out: Android's regex takes no (?U).
        """(?<![\p{L}\p{N}_./~@+%:\\-])(?:~/|\.{1,2}/|/)?(?:[\p{L}\p{N}_.@+%-]+/)*[\p{L}\p{N}_@+%-][\p{L}\p{N}_.@+%-]*\.(?:$EXTENSIONS)(?![.]?[\p{L}\p{N}_/-])""",
        RegexOption.IGNORE_CASE,
    )

    private val alone = Regex("""^(?:~/|\.{1,2}/|/)?[^\n`]*[^\s/]\.(?:$EXTENSIONS)$""", RegexOption.IGNORE_CASE)

    /** Where in [text] pictures' paths are. */
    fun find(text: String): List<IntRange> = inText.findAll(text).filter { "//" !in it.value }.map { it.range }.toList()

    /** Whether [code] is a picture's path and nothing else (spaces and all, as code can quote one). */
    fun whole(code: String): Boolean = "://" !in code && !code.startsWith(' ') && alone.matches(code)

    /** [path] as the Mac has it: as it is from / or ~, else from [cwd] (the home if there's none). */
    fun resolve(path: String, cwd: String): String {
        if (path.startsWith('/') || path.startsWith('~')) return path
        val parts = if (cwd.isEmpty()) mutableListOf("~") else cwd.split('/').filter { it.isNotEmpty() }.toMutableList()
        for (p in path.split('/').filter { it.isNotEmpty() }) {
            when (p) {
                "." -> Unit
                ".." -> if (parts.isNotEmpty() && parts.last() != "~") parts.removeAt(parts.lastIndex)
                else -> parts += p
            }
        }
        return (if (cwd.startsWith('/')) "/" else "") + parts.joinToString("/")
    }
}
