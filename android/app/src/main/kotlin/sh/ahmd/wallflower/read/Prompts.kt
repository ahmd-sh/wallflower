package sh.ahmd.wallflower.read

import android.content.SharedPreferences

/**
 * What the phone learns of shells' prompts from commands it sees typed (the
 * shell marks where typing starts, OSC 133), so a repaint's history, which
 * keeps only where prompts and output start, still reads as commands: the
 * mark prompts end with (kept on the phone), and what was typed on which
 * prompt rows (the newest few hundred, while the app runs).
 */
class Prompts(private val prefs: SharedPreferences) {
    @Volatile var mark: String? = prefs.getString(KEY, null)
        private set

    private val byRows = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 500
    }

    /** [typed] was typed after [prompt], on prompt [rows], if they could be read. */
    fun learn(rows: List<String>?, prompt: String, typed: String) {
        if (rows != null) synchronized(byRows) { byRows[key(rows)] = typed }
        val m = markOf(prompt) ?: return
        if (m == mark) return
        mark = m
        prefs.edit().putString(KEY, m).apply()
    }

    /**
     * What was typed on prompt [rows], [cols] wide, where the shell didn't
     * say: what was seen typed on them before, or what follows the mark
     * (empty if nothing was), with what the prompt says before its mark.
     * Null if it can't be told.
     */
    fun read(rows: List<String>, cols: Int): Said? {
        val m = mark
        val prompt = m?.let { promptBefore(it, rows) }.orEmpty()
        synchronized(byRows) { byRows[key(rows)] }?.let { return Said(prompt, it) }
        return m?.let { typedAfter(it, rows, cols) }?.let { Said(prompt, it) }
    }

    private fun key(rows: List<String>) = rows.joinToString("\n") { it.trimEnd() }

    private companion object {
        const val KEY = "prompt_mark"
    }
}

/**
 * What was typed at a prompt, [text], and what the prompt says before its
 * mark, [prompt]: the directory, in most.
 */
data class Said(val prompt: String, val text: String)

/** The mark a prompt ends with: its last word, as `➜` in `dotfiles ➜`. */
internal fun markOf(prompt: String): String? = prompt.trim().substringAfterLast(' ').takeIf { it.isNotEmpty() }

/** [prompt] without its [mark]: `dotfiles` for `dotfiles ➜`. */
internal fun withoutMark(prompt: String, mark: String?): String =
    prompt.trim().let { p -> if (mark != null && p.endsWith(mark)) p.dropLast(mark.length).trim() else p }

/** What a prompt says before [mark], on the first of [rows] that has it; null if none does. */
internal fun promptBefore(mark: String, rows: List<String>): String? {
    val (y, x) = markAt(mark, rows) ?: return null
    return rows[y].substring(0, x - mark.length).trim()
}

/** The row [mark] ends a prompt on, and the column after it. */
private fun markAt(mark: String, rows: List<String>): Pair<Int, Int>? {
    for ((i, row) in rows.withIndex()) {
        val at = row.indexOf(mark)
        if (at >= 0 && (at + mark.length == row.length || row[at + mark.length] == ' ')) return i to at + mark.length
    }
    return null
}

/**
 * What was typed on prompt [rows] (as a terminal shows them, [cols] wide)
 * after a prompt ending in [mark]: up to a wide gap, where a right prompt
 * sits, or the row's end, going on into the next row when it runs to the
 * edge. Null if no row has the mark.
 */
internal fun typedAfter(mark: String, rows: List<String>, cols: Int): String? {
    var (y, x) = markAt(mark, rows) ?: return null
    val out = StringBuilder()
    while (y < rows.size) {
        val rest = rows[y].substring(minOf(x, rows[y].length))
        val gap = rest.indexOf(GAP)
        out.append(if (gap >= 0) rest.substring(0, gap) else rest.trimEnd())
        if (gap >= 0 || rows[y].trimEnd().length < cols) break
        y++
        x = 0
    }
    return out.toString().trim()
}

private const val GAP = "   "
