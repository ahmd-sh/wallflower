package sh.ahmd.wallflower.screen

import sh.ahmd.wallflower.screen.Screen.Companion.FAINT
import sh.ahmd.wallflower.screen.Screen.Companion.HIGHLIGHT
import sh.ahmd.wallflower.screen.Screen.Companion.MUTED

/** A screen from tmux's capture-pane -e: text, with greys (256-color 240-250, or 8) muted, backgrounds highlighted, and dim faint. */
fun captured(name: String, cols: Int): Screen {
    val rows = Screen::class.java.getResource("/$name")!!.readText().trimEnd('\n').split('\n')
    val text = Array(cols * rows.size) { "" }
    val attrs = IntArray(cols * rows.size)
    val sgr = Regex("""\u001b\[([0-9;]*)m""")
    rows.forEachIndexed { y, row ->
        var x = 0
        var muted = false
        var lit = false
        var faint = false
        var i = 0
        while (i < row.length && x < cols) {
            val m = sgr.find(row, i)
            if (m != null && m.range.first == i) {
                val codes = m.groupValues[1].split(';')
                var k = 0
                while (k < codes.size) {
                    when (codes[k]) {
                        "0", "" -> { muted = false; lit = false; faint = false }
                        "2" -> faint = true
                        "22" -> faint = false
                        "39" -> muted = false
                        "49" -> lit = false
                        "7" -> lit = true
                        "90" -> muted = true
                        "38" -> { muted = codes.getOrNull(k + 1) == "5" && (codes[k + 2].toInt() in 240..250 || codes[k + 2] == "8"); k += if (codes.getOrNull(k + 1) == "5") 2 else 4 }
                        "48" -> { lit = true; k += if (codes.getOrNull(k + 1) == "5") 2 else 4 }
                        else -> if (codes[k].toIntOrNull() in 30..37 || codes[k].toIntOrNull() in 91..97) muted = false
                    }
                    k++
                }
                i = m.range.last + 1
                continue
            }
            val cp = row.codePointAt(i)
            text[y * cols + x] = if (cp == ' '.code) "" else String(Character.toChars(cp))
            attrs[y * cols + x] = (if (muted) MUTED else 0) or (if (lit) HIGHLIGHT else 0) or (if (faint) FAINT else 0)
            x++
            i += Character.charCount(cp)
        }
    }
    return Screen(cols, rows.size, 0, 0, text, attrs, BooleanArray(rows.size))
}
