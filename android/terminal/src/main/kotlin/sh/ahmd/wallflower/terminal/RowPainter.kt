package sh.ahmd.wallflower.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.util.TypedValue
import kotlin.math.ceil
import sh.ahmd.wallflower.terminal.FrameBuffer.Companion.DEFAULT
import sh.ahmd.wallflower.terminal.FrameBuffer.Companion.FLAG_BOLD
import sh.ahmd.wallflower.terminal.FrameBuffer.Companion.FLAG_FAINT
import sh.ahmd.wallflower.terminal.FrameBuffer.Companion.FLAG_GRAPHEME
import sh.ahmd.wallflower.terminal.FrameBuffer.Companion.FLAG_INVERSE
import sh.ahmd.wallflower.terminal.FrameBuffer.Companion.FLAG_INVISIBLE
import sh.ahmd.wallflower.terminal.FrameBuffer.Companion.FLAG_ITALIC
import sh.ahmd.wallflower.terminal.FrameBuffer.Companion.FLAG_OVERLINE
import sh.ahmd.wallflower.terminal.FrameBuffer.Companion.FLAG_SELECTED
import sh.ahmd.wallflower.terminal.FrameBuffer.Companion.FLAG_STRIKETHROUGH

/**
 * Draws a terminal's rows, one at a time, in its font and cells: what the
 * terminal view shows, and anything else showing rows as it does.
 */
class RowPainter(context: Context, fontSizeSp: Float = 13f) {
    private val resources = context.resources
    internal val regular = typeface(context, R.font.jetbrains_mono_regular)
    private val bold = typeface(context, R.font.jetbrains_mono_bold)
    internal val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = regular }
    internal val fill = Paint()
    internal val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val curl = Path()
    private val run = StringBuilder()

    var cellWidth = 0f
        private set
    var cellHeight = 0f
        private set
    var baseline = 0f
        private set

    var fontSizeSp = fontSizeSp
        set(value) {
            field = value.coerceIn(6f, 32f)
            measure()
        }

    init {
        measure()
    }

    private fun measure() {
        text.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
        text.typeface = regular
        cellWidth = text.measureText("M")
        val fm = text.fontMetrics
        cellHeight = ceil((fm.descent - fm.ascent) * 1.15f)
        baseline = (cellHeight - (fm.descent - fm.ascent)) / 2 - fm.ascent
    }

    /**
     * Draws [frame]'s row [i] with its top at 0, [height] tall; colors left
     * to the terminal are [background] and [foreground].
     */
    fun draw(c: Canvas, frame: FrameBuffer, i: Int, height: Float, background: Int, foreground: Int) {
        val cols = frame.cols
        var x = 0
        while (x < cols) {
            val bg = bg(frame, i, x, foreground)
            var end = x + 1
            while (end < cols && bg(frame, i, end, foreground) == bg) end++
            if (bg != DEFAULT) {
                fill.color = opaque(bg)
                c.drawRect(x * cellWidth, 0f, end * cellWidth, height, fill)
            }
            x = end
        }

        x = 0
        while (x < cols) {
            val flags = frame.flags(i, x)
            val wide = FrameBuffer.wide(flags)
            val cp = frame.codepoint(i, x)
            if (wide >= 2 || cp == 0 || flags and FLAG_INVISIBLE != 0) {
                x++
                continue
            }
            val fg = fg(frame, i, x, background, foreground)
            if (cp < 0x80 && wide == 0) {
                // A run of plain ASCII in one style draws as one string; the
                // font is monospace, so it lands on the grid.
                run.setLength(0)
                var end = x
                while (end < cols && sameRun(frame, i, end, flags, fg, background, foreground)) {
                    val e = frame.codepoint(i, end)
                    run.append(if (e == 0) ' ' else e.toChar())
                    end++
                }
                style(flags, fg)
                c.drawText(run, 0, run.length, x * cellWidth, baseline, text)
                decorate(c, frame, i, x, end, flags)
                x = end
            } else {
                // Anything else (wide, emoji, graphemes, fallback fonts) is
                // drawn alone, centered and shrunk to fit its cells.
                run.setLength(0)
                frame.appendText(i, x, run)
                val span = if (wide == 1) 2 else 1
                style(flags, fg)
                val w = text.measureText(run, 0, run.length)
                val room = span * cellWidth
                val left = x * cellWidth
                if (w > room) {
                    c.save()
                    c.scale(room / w, room / w, left + room / 2, cellHeight / 2)
                    c.drawText(run, 0, run.length, left, baseline, text)
                    c.restore()
                } else {
                    c.drawText(run, 0, run.length, left + (room - w) / 2, baseline, text)
                }
                decorate(c, frame, i, x, x + span, flags)
                x += span
            }
        }
    }

    private fun sameRun(frame: FrameBuffer, i: Int, x: Int, flags: Int, fg: Int, background: Int, foreground: Int): Boolean {
        val cp = frame.codepoint(i, x)
        val f = frame.flags(i, x)
        return cp < 0x80 && FrameBuffer.wide(f) == 0 && f and FLAG_GRAPHEME == 0 &&
            f and STYLE_MASK == flags and STYLE_MASK && fg(frame, i, x, background, foreground) == fg && f and FLAG_INVISIBLE == 0
    }

    private fun fg(frame: FrameBuffer, i: Int, x: Int, background: Int, foreground: Int): Int {
        val f = frame.flags(i, x)
        var fg = if (f and FLAG_INVERSE != 0) frame.bg(i, x).orIfDefault(background) else frame.fg(i, x).orIfDefault(foreground)
        if (f and FLAG_SELECTED != 0) fg = background
        return fg
    }

    private fun bg(frame: FrameBuffer, i: Int, x: Int, foreground: Int): Int {
        val f = frame.flags(i, x)
        if (f and FLAG_SELECTED != 0) return foreground
        return if (f and FLAG_INVERSE != 0) frame.fg(i, x).orIfDefault(foreground) else frame.bg(i, x)
    }

    private fun style(flags: Int, fg: Int) {
        text.typeface = if (flags and FLAG_BOLD != 0) bold else regular
        text.textSkewX = if (flags and FLAG_ITALIC != 0) -0.2f else 0f
        text.color = if (flags and FLAG_FAINT != 0) (fg and 0xFFFFFF) or (0x88 shl 24) else opaque(fg)
    }

    private fun decorate(c: Canvas, frame: FrameBuffer, i: Int, from: Int, to: Int, flags: Int) {
        val underline = FrameBuffer.underline(flags)
        if (underline == 0 && flags and (FLAG_STRIKETHROUGH or FLAG_OVERLINE) == 0) return
        val left = from * cellWidth
        val right = to * cellWidth
        val thickness = maxOf(1f, cellHeight / 16f)
        stroke.strokeWidth = thickness
        stroke.color = text.color
        stroke.pathEffect = null
        if (flags and FLAG_STRIKETHROUGH != 0) c.drawLine(left, cellHeight * 0.55f, right, cellHeight * 0.55f, stroke)
        if (flags and FLAG_OVERLINE != 0) c.drawLine(left, thickness, right, thickness, stroke)
        if (underline == 0) return

        val ul = frame.underlineColor(i, from)
        if (ul != DEFAULT) stroke.color = opaque(ul)
        val y = baseline + thickness * 2
        when (underline) {
            2 -> {
                c.drawLine(left, y - thickness, right, y - thickness, stroke)
                c.drawLine(left, y + thickness, right, y + thickness, stroke)
            }
            3 -> {
                curl.rewind()
                curl.moveTo(left, y)
                var px = left
                var up = true
                val step = cellWidth / 2
                while (px < right) {
                    curl.quadTo(px + step / 2, if (up) y - thickness * 1.5f else y + thickness * 1.5f, px + step, y)
                    px += step
                    up = !up
                }
                c.drawPath(curl, stroke)
            }
            4 -> {
                stroke.pathEffect = DashPathEffect(floatArrayOf(thickness, thickness * 2), 0f)
                c.drawLine(left, y, right, y, stroke)
            }
            5 -> {
                stroke.pathEffect = DashPathEffect(floatArrayOf(cellWidth / 2, cellWidth / 4), 0f)
                c.drawLine(left, y, right, y, stroke)
            }
            else -> c.drawLine(left, y, right, y, stroke)
        }
    }

    private fun Int.orIfDefault(other: Int) = if (this == DEFAULT) other else this

    internal companion object {
        val STYLE_MASK = FLAG_BOLD or FLAG_ITALIC or FLAG_FAINT or (7 shl 8) or FLAG_STRIKETHROUGH or FLAG_OVERLINE or
            FLAG_INVERSE or FLAG_SELECTED

        fun opaque(rgb: Int) = rgb or (0xFF shl 24)

        private val typefaces = HashMap<Int, Typeface>()

        /**
         * The font, then symbols it lacks (TUI spinners, ⏺ ⎿ ⏵ ✻), then the
         * system's fonts. Anything not in the font would otherwise depend on
         * the phone having it. Built once: views come and go as panes do.
         */
        fun typeface(context: Context, font: Int): Typeface = synchronized(typefaces) {
            typefaces.getOrPut(font) {
                val res = context.applicationContext.resources
                fun family(id: Int) = FontFamily.Builder(Font.Builder(res, id).build()).build()
                Typeface.CustomFallbackBuilder(family(font))
                    .addCustomFallback(family(R.font.symbols_fallback))
                    .addCustomFallback(family(R.font.symbols_fallback2))
                    .setSystemFallback("monospace")
                    .build()
            }
        }
    }
}
