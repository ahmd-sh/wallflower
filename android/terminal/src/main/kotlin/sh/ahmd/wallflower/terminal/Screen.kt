package sh.ahmd.wallflower.terminal

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The active screen's text, with what each cell is: enough to read what an
 * app's input line holds. Cells are row-major; a wide character's second
 * cell is an empty spacer.
 */
class Screen(
    val cols: Int,
    val rows: Int,
    val cursorX: Int,
    val cursorY: Int,
    private val text: Array<String>,
    private val attrs: IntArray,
    private val wrapped: BooleanArray,
) {
    fun text(x: Int, y: Int): String = text[y * cols + x]

    /** [OUTPUT], [INPUT] or [PROMPT], as the shell marked it (OSC 133); output if it doesn't. */
    fun role(x: Int, y: Int): Int = attrs[y * cols + x] and 3

    fun faint(x: Int, y: Int) = attrs[y * cols + x] and FAINT != 0

    /** In palette color 8, which shells use for autosuggestions. */
    fun grey(x: Int, y: Int) = attrs[y * cols + x] and GREY != 0

    fun spacer(x: Int, y: Int) = attrs[y * cols + x] and SPACER != 0

    /** The row continues on the next one (soft wrap). */
    fun wrapped(y: Int) = wrapped[y]

    companion object {
        const val OUTPUT = 0
        const val INPUT = 1
        const val PROMPT = 2
        const val FAINT = 4
        const val GREY = 8
        const val SPACER = 16

        /** Reads the native snapshot layout (see terminal.c). */
        fun parse(bytes: ByteArray): Screen {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val cols = b.short.toInt() and 0xFFFF
            val rows = b.short.toInt() and 0xFFFF
            val cx = b.short.toInt() and 0xFFFF
            val cy = b.short.toInt() and 0xFFFF
            val text = Array(cols * rows) { "" }
            val attrs = IntArray(cols * rows)
            val wrapped = BooleanArray(rows)
            val sb = StringBuilder()
            for (y in 0 until rows) {
                wrapped[y] = b.get().toInt() and 1 != 0
                for (x in 0 until cols) {
                    attrs[y * cols + x] = b.get().toInt() and 0xFF
                    val n = b.get().toInt() and 0xFF
                    sb.setLength(0)
                    repeat(n) { sb.appendCodePoint(b.int) }
                    text[y * cols + x] = sb.toString()
                }
            }
            return Screen(cols, rows, cx, cy, text, attrs, wrapped)
        }
    }
}
