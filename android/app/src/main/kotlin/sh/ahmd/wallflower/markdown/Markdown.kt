package sh.ahmd.wallflower.markdown

/**
 * The markdown chat CLIs write, parsed for rendering: headings, paragraphs,
 * fenced code, quotes, lists (nested), tables and rules, with bold, italic,
 * strikethrough, code and links inside. Not CommonMark: a line break in a
 * paragraph stays a line break, as chat replies mean it, unless it's a
 * document's, written to wrap ([breaks] false).
 */
sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class Code(val language: String, val text: String) : Block
    data class Quote(val blocks: List<Block>) : Block
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<List<Block>>) : Block
    data class Table(val header: List<String>, val rows: List<List<String>>) : Block
    data object Rule : Block
}

data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

object Markdown {
    fun parse(text: String, breaks: Boolean = true): List<Block> = blocks(text.replace("\r\n", "\n").split('\n'), breaks)

    private val heading = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val rule = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
    private val item = Regex("""^(\s*)([-*+]|(\d{1,9})[.)])(\s+|$)(.*)$""")
    private val tableSeparator = Regex("""^\s*\|?(\s*:?-+:?\s*\|)+\s*(:?-+:?\s*)?$""")

    private fun blocks(lines: List<String>, breaks: Boolean): List<Block> {
        val out = mutableListOf<Block>()
        val paragraph = mutableListOf<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) out += Block.Paragraph(paragraph.joinToString(if (breaks) "\n" else " "))
            paragraph.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trimStart()
            when {
                t.startsWith("```") || t.startsWith("~~~") -> {
                    flush()
                    val fence = t.takeWhile { it == t[0] }
                    val indent = line.length - t.length
                    val body = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
                        body += lines[i].drop(minOf(indent, indentOf(lines[i])))
                        i++
                    }
                    out += Block.Code(t.drop(fence.length).trim(), body.joinToString("\n"))
                }
                t.isEmpty() -> flush()
                heading.matches(t) -> {
                    flush()
                    val m = heading.find(t)!!
                    out += Block.Heading(m.groupValues[1].length, m.groupValues[2])
                }
                rule.matches(line) -> {
                    flush()
                    out += Block.Rule
                }
                t.startsWith(">") -> {
                    flush()
                    val quoted = mutableListOf<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        quoted += lines[i].trimStart().removePrefix(">").removePrefix(" ")
                        i++
                    }
                    out += Block.Quote(blocks(quoted, breaks))
                    continue
                }
                item.matches(line) -> {
                    flush()
                    i = list(lines, i, out, breaks)
                    continue
                }
                t.startsWith("|") && i + 1 < lines.size && tableSeparator.matches(lines[i + 1]) -> {
                    flush()
                    val header = cells(t)
                    val rows = mutableListOf<List<String>>()
                    i += 2
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                        rows += cells(lines[i]).let { r -> List(header.size) { r.getOrElse(it) { "" } } }
                        i++
                    }
                    out += Block.Table(header, rows)
                    continue
                }
                else -> paragraph += line.trim()
            }
            i++
        }
        flush()
        return out
    }

    /** Parses the list starting at [start] into [out]; returns the line after it. */
    private fun list(lines: List<String>, start: Int, out: MutableList<Block>, breaks: Boolean): Int {
        val first = item.find(lines[start])!!
        val indent = first.groupValues[1].length
        val ordered = first.groupValues[3].isNotEmpty()
        val items = mutableListOf<List<Block>>()
        var i = start
        while (i < lines.size) {
            val m = item.find(lines[i])
            if (m == null || m.groupValues[1].length != indent || m.groupValues[3].isNotEmpty() != ordered) break
            val content = indent + m.groupValues[2].length + maxOf(m.groupValues[4].length, 1)
            val body = mutableListOf(m.groupValues[5])
            i++
            while (i < lines.size) {
                val l = lines[i]
                if (l.isBlank()) {
                    val next = (i + 1 until lines.size).firstOrNull { lines[it].isNotBlank() }
                    if (next == null || indentOf(lines[next]) <= indent) break
                    body += ""
                } else if (indentOf(l) > indent) {
                    body += l.drop(minOf(indentOf(l), content))
                } else {
                    break
                }
                i++
            }
            items += blocks(body, breaks)
        }
        out += Block.ListBlock(ordered, first.groupValues[3].toIntOrNull() ?: 1, items)
        return i
    }

    private fun indentOf(line: String) = line.length - line.trimStart().length

    private fun cells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split(Regex("""(?<!\\)\|""")).map { it.trim().replace("\\|", "|") }

    /** A line of inline markdown as runs of one style each. */
    fun inline(text: String): List<Span> {
        val out = mutableListOf<Span>()
        Inline(text, out).run(0, text.length, Span(""))
        return out
    }

    private class Inline(val s: String, val out: MutableList<Span>) {
        fun run(from: Int, to: Int, style: Span) {
            val buf = StringBuilder()
            fun emit() {
                if (buf.isEmpty()) return
                val last = out.lastOrNull()
                if (last != null && last.copy(text = "") == style) {
                    out[out.size - 1] = last.copy(text = last.text + buf)
                } else {
                    out += style.copy(text = buf.toString())
                }
                buf.clear()
            }
            var i = from
            while (i < to) {
                val c = s[i]
                if (c == '\\' && i + 1 < to && s[i + 1] in PUNCTUATION) {
                    buf.append(s[i + 1])
                    i += 2
                    continue
                }
                if (c == '`') {
                    val ticks = s.substring(i, to).takeWhile { it == '`' }
                    val close = s.indexOf(ticks, i + ticks.length).takeIf { it in 0 until to }
                    if (close != null) {
                        emit()
                        buf.append(s, i + ticks.length, close)
                        val code = buf.toString().let { if (it.length > 2 && it.startsWith(' ') && it.endsWith(' ')) it.substring(1, it.length - 1) else it }
                        buf.clear()
                        out += style.copy(text = code, code = true)
                        i = close + ticks.length
                        continue
                    }
                }
                val delim = when {
                    s.startsWith("**", i) || s.startsWith("__", i) || s.startsWith("~~", i) -> s.substring(i, i + 2)
                    c == '*' || c == '_' -> c.toString()
                    else -> null
                }
                if (delim != null) {
                    val close = closing(delim, i, to)
                    if (close != null) {
                        emit()
                        val inner = when (delim) {
                            "**", "__" -> style.copy(bold = true)
                            "~~" -> style.copy(strike = true)
                            else -> style.copy(italic = true)
                        }
                        run(i + delim.length, close, inner)
                        i = close + delim.length
                        continue
                    }
                    buf.append(delim)
                    i += delim.length
                    continue
                }
                if (c == '[') {
                    val end = s.indexOf("](", i).takeIf { it in i + 1 until to }
                    val close = end?.let { s.indexOf(')', it + 2) }?.takeIf { it in 0 until to }
                    if (end != null && close != null) {
                        emit()
                        run(i + 1, end, style.copy(link = s.substring(end + 2, close).trim()))
                        i = close + 1
                        continue
                    }
                }
                if ((s.startsWith("https://", i) || s.startsWith("http://", i)) && (i == 0 || !s[i - 1].isLetterOrDigit())) {
                    var end = i
                    while (end < to && !s[end].isWhitespace() && s[end] != '<' && s[end] != '>') end++
                    while (end > i && s[end - 1] in ".,:;!?)'\"") end--
                    emit()
                    val url = s.substring(i, end)
                    out += style.copy(text = url, link = url)
                    i = end
                    continue
                }
                buf.append(c)
                i++
            }
            emit()
        }

        /**
         * Where [delim] opened at [at] closes: not right after it, not after
         * a space, and for _ not inside a word (snake_case stays as is).
         */
        fun closing(delim: String, at: Int, to: Int): Int? {
            val start = at + delim.length
            if (start >= to || s[start].isWhitespace()) return null
            if (delim[0] == '_' && at > 0 && s[at - 1].isLetterOrDigit()) return null
            var j = start + 1
            while (j <= to - delim.length) {
                if (s.startsWith(delim, j) && !s[j - 1].isWhitespace()) {
                    if (delim.length == 2) { // ***x***: the double closes last, around the single
                        var run = j
                        while (run < to && s[run] == delim[0]) run++
                        return run - delim.length
                    }
                    val doubled = j + 1 < to && s[j + 1] == delim[0]
                    val wordAfter = delim[0] == '_' && j + delim.length < to && s[j + delim.length].isLetterOrDigit()
                    if (!doubled && !wordAfter) return j
                    if (doubled) j++
                }
                if (s[j] == '`') { // code spans hide delimiters
                    val ticks = s.substring(j, to).takeWhile { it == '`' }
                    val close = s.indexOf(ticks, j + ticks.length)
                    if (close in 0 until to) j = close + ticks.length - 1
                }
                j++
            }
            return null
        }
    }

    private const val PUNCTUATION = "\\`*_{}[]()#+-.!|~>"
}
