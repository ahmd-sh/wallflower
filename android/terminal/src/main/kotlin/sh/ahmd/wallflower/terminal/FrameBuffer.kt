package sh.ahmd.wallflower.terminal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

/**
 * A frame packed by the native side (terminal.c), read in place without
 * allocating. Native-order int32s:
 *
 * ```
 * header  dirty cols rows cursorFlags cursorX cursorY cursorStyle
 *         background foreground cursorColor rowCount scrollTotal scrollOffset scrollLen
 * row     y extraCount, then cols × [codepoint fg bg underlineColor flags],
 *         then extraCount × [x n codepoint×n] for multi-codepoint graphemes
 * ```
 *
 * Colors are 0xRRGGBB, or [DEFAULT] for the terminal default. Only rows that
 * changed are present unless [full].
 */
class FrameBuffer {
    var buffer: ByteBuffer = allocate(64 * 1024)
        private set
    private var ints: IntBuffer = buffer.asIntBuffer()
    private var rowOffsets = IntArray(64)

    val full get() = ints[0] == 2
    val cols get() = ints[1]
    val rows get() = ints[2]
    val cursorVisible get() = ints[3] and 1 != 0
    val cursorBlinking get() = ints[3] and 2 != 0
    val cursorWideTail get() = ints[3] and 4 != 0
    val cursorInViewport get() = ints[3] and 8 != 0
    /** A full-screen app has the alternate screen, which keeps no scrollback. */
    val altScreen get() = ints[3] and 32 != 0
    val cursorX get() = ints[4]
    val cursorY get() = ints[5]
    val cursorStyle get() = ints[6]
    val background get() = ints[7]
    val foreground get() = ints[8]
    val cursorColor get() = ints[9]
    val rowCount get() = ints[10]
    val scrollTotal get() = ints[11]
    val scrollOffset get() = ints[12]
    val scrollLength get() = ints[13]

    fun rowY(row: Int) = ints[rowOffsets[row]]

    fun codepoint(row: Int, x: Int) = ints[cell(row, x)]
    fun fg(row: Int, x: Int) = ints[cell(row, x) + 1]
    fun bg(row: Int, x: Int) = ints[cell(row, x) + 2]
    fun underlineColor(row: Int, x: Int) = ints[cell(row, x) + 3]
    fun flags(row: Int, x: Int) = ints[cell(row, x) + 4]

    /** Appends the cell's text (its whole grapheme) to [out]. */
    fun appendText(row: Int, x: Int, out: StringBuilder) {
        val cp = codepoint(row, x)
        if (cp == 0) return
        out.appendCodePoint(cp)
        if (flags(row, x) and FLAG_GRAPHEME == 0) return
        var at = rowOffsets[row] + ROW_HEADER + cols * CELL
        repeat(ints[rowOffsets[row] + 1]) {
            val n = ints[at + 1]
            if (ints[at] == x) {
                for (i in 0 until n) out.appendCodePoint(ints[at + 2 + i])
                return
            }
            at += 2 + n
        }
    }

    private fun cell(row: Int, x: Int) = rowOffsets[row] + ROW_HEADER + x * CELL

    internal fun load(bytes: Int) {
        ints = buffer.asIntBuffer()
        ints.limit(bytes / 4)
        if (bytes == 0) return
        if (rowOffsets.size < rowCount) rowOffsets = IntArray(rowCount)
        var at = HEADER
        for (r in 0 until rowCount) {
            rowOffsets[r] = at
            val extras = ints[at + 1]
            at += ROW_HEADER + cols * CELL
            repeat(extras) { at += 2 + ints[at + 1] }
        }
    }

    internal fun grow(bytes: Int) {
        buffer = allocate(bytes)
        ints = buffer.asIntBuffer()
    }

    companion object {
        const val DEFAULT = -1

        const val FLAG_BOLD = 1 shl 0
        const val FLAG_ITALIC = 1 shl 1
        const val FLAG_FAINT = 1 shl 2
        const val FLAG_BLINK = 1 shl 3
        const val FLAG_INVERSE = 1 shl 4
        const val FLAG_INVISIBLE = 1 shl 5
        const val FLAG_STRIKETHROUGH = 1 shl 6
        const val FLAG_OVERLINE = 1 shl 7
        const val FLAG_SELECTED = 1 shl 13
        const val FLAG_GRAPHEME = 1 shl 14

        /** Underline style: 0 none, 1 single, 2 double, 3 curly, 4 dotted, 5 dashed. */
        fun underline(flags: Int) = (flags shr 8) and 7

        /** Cell width: 0 narrow, 1 wide, 2 spacer after a wide cell, 3 spacer at a line wrap. */
        fun wide(flags: Int) = (flags shr 11) and 3

        const val CURSOR_BAR = 0
        const val CURSOR_BLOCK = 1
        const val CURSOR_UNDERLINE = 2
        const val CURSOR_BLOCK_HOLLOW = 3

        private const val HEADER = 14
        private const val ROW_HEADER = 2
        private const val CELL = 5

        private fun allocate(bytes: Int) = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }
}
