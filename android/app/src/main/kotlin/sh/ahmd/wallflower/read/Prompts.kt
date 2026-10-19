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

    /** [typed] was typed on prompt [rows], after [prompt]. */
    fun learn(rows: List<String>, prompt: String, typed: String) {
        synchronized(byRows) { byRows[key(rows)] = typed }
        val m = markOf(prompt) ?: return
        if (m == mark) return
        mark = m
        prefs.edit().putString(KEY, m).apply()
    }

    /**
     * What was typed on prompt [rows], [cols] wide, where the shell didn't
     * say: what was seen typed on them before, or what follows the mark;
     * empty if nothing was. Null if it can't be told.
     */
    fun typed(rows: List<String>, cols: Int): String? =
        synchronized(byRows) { byRows[key(rows)] } ?: mark?.let { typedAfter(it, rows, cols) }

    private fun key(rows: List<String>) = rows.joinToString("\n") { it.trimEnd() }

    private companion object {
        const val KEY = "prompt_mark"
    }
}

/** The mark a prompt ends with: its last word, as `➜` in `dotfiles ➜`. */
internal fun markOf(prompt: String): String? = prompt.trim().substringAfterLast(' ').takeIf { it.isNotEmpty() }

/**
 * What was typed on prompt [rows] (as a terminal shows them, [cols] wide)
 * after a prompt ending in [mark]: up to a wide gap, where a right prompt
 * sits, or the row's end, going on into the next row when it runs to the
 * edge. Null if no row has the mark.
 */
internal fun typedAfter(mark: String, rows: List<String>, cols: Int): String? {
    var y = -1
    var x = 0
    for ((i, row) in rows.withIndex()) {
        val at = row.indexOf(mark)
        if (at >= 0 && (at + mark.length == row.length || row[at + mark.length] == ' ')) {
            y = i
            x = at + mark.length
            break
        }
    }
    if (y < 0) return null
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
