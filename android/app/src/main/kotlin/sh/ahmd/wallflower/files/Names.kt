package sh.ahmd.wallflower.files

import android.webkit.MimeTypeMap

/** How a file shows: as a picture, a PDF's pages, markdown, text, or none of those, to share. */
enum class Kind { Picture, Pdf, Markdown, Text, Other }

/** What a file is, by its name: its extension, or a name text goes by (README, Makefile, .zshrc). */
fun kindOf(name: String): Kind {
    val ext = extension(name)
    return when {
        ext in PICTURES -> Kind.Picture
        ext == "pdf" -> Kind.Pdf
        ext in MARKDOWN -> Kind.Markdown
        ext in TEXT || ext.isEmpty() -> Kind.Text
        else -> Kind.Other
    }
}

/** A name's extension, lowercased; none for a dotfile's (.zshrc) or a name without one. */
fun extension(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot <= 0) "" else name.substring(dot + 1).lowercase()
}

/** Whether [bytes] read as text: no NULs where binary files have them, near the start. */
fun looksLikeText(bytes: ByteArray, length: Int = bytes.size): Boolean = (0 until minOf(length, SNIFF)).none { bytes[it] == 0.toByte() }

/** The type a file goes by when shared, by its name. */
fun mimeOf(name: String): String = when (val ext = extension(name)) {
    "" -> "text/plain"
    in MARKDOWN -> "text/markdown"
    else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: if (ext in TEXT) "text/plain" else "application/octet-stream"
}

/** A file's icon, in the Nerd Font symbols the terminal's font carries. */
fun iconOf(name: String, dir: Boolean): String = when {
    dir -> ""
    else -> when (kindOf(name)) {
        Kind.Picture -> ""
        Kind.Pdf -> ""
        Kind.Markdown -> ""
        Kind.Text -> if (extension(name) in CODE) "" else ""
        Kind.Other -> when (extension(name)) {
            in ARCHIVES -> ""
            in VIDEO -> ""
            in AUDIO -> ""
            else -> ""
        }
    }
}

/**
 * [path] as a word of a message: as it is, or in single quotes where a
 * shell (or an agent reading it as one would) would split it.
 */
fun forMessage(path: String): String =
    if (path.all { it.isLetterOrDigit() && it.code < 128 || it in "._/~+@%:,=-" }) path else "'" + path.replace("'", "'\\''") + "'"

/** A size in bytes as people say it: 812 B, 14 KB, 3.1 MB. */
fun sizeOf(bytes: Long): String = when {
    bytes < 1_000 -> "$bytes B"
    bytes < 1_000_000 -> "${(bytes + 500) / 1_000} KB"
    bytes < 1_000_000_000 -> "%.1f MB".format(bytes / 1e6)
    else -> "%.1f GB".format(bytes / 1e9)
}

/** How long ago [millis] (Unix milliseconds) was, briefly: now, 5m, 3h, 2d, then a date. */
fun ago(millis: Long, now: Long = System.currentTimeMillis()): String {
    val s = (now - millis) / 1000
    return when {
        s < 60 -> "now"
        s < 3600 -> "${s / 60}m"
        s < 86400 -> "${s / 3600}h"
        s < 30 * 86400 -> "${s / 86400}d"
        else -> java.text.SimpleDateFormat(if (s < 300 * 86400) "MMM d" else "MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(millis))
    }
}

private const val SNIFF = 8 * 1024

private val PICTURES = setOf("png", "jpg", "jpeg", "gif", "webp", "heic", "heif", "bmp", "avif", "ico")
private val MARKDOWN = setOf("md", "markdown", "mdx")
private val CODE = setOf(
    "go", "rs", "py", "rb", "java", "kt", "kts", "swift", "c", "h", "cc", "cpp", "hpp", "m", "mm", "cs", "php", "js", "mjs", "cjs",
    "ts", "tsx", "jsx", "sh", "bash", "zsh", "fish", "lua", "sql", "vue", "svelte", "r", "jl", "ex", "exs", "erl", "hs", "ml", "clj",
    "el", "vim", "nix", "tf", "hcl", "dart", "scala", "zig", "proto", "graphql", "gradle", "css", "scss", "html", "htm", "xml",
)
private val TEXT = CODE + setOf(
    "txt", "log", "csv", "tsv", "json", "jsonl", "yaml", "yml", "toml", "ini", "cfg", "conf", "env", "properties", "plist",
    "diff", "patch", "lock", "sum", "mod", "tex", "bib", "rst", "adoc", "org", "svg", "gitignore", "dockerfile", "mk", "srt", "vtt",
)
private val ARCHIVES = setOf("zip", "tar", "gz", "tgz", "bz2", "xz", "zst", "7z", "rar", "dmg", "pkg", "jar", "apk", "ipa")
private val VIDEO = setOf("mp4", "mov", "m4v", "mkv", "webm", "avi")
private val AUDIO = setOf("mp3", "m4a", "wav", "aac", "flac", "ogg", "opus", "aiff")
