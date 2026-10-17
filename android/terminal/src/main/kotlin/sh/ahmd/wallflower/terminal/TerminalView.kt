package sh.ahmd.wallflower.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderNode
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.SystemClock
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
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
 * Draws a [Terminal]. Each row is a RenderNode re-recorded only when the
 * terminal reports it dirty, so a frame costs as much as what changed.
 * Content is pulled once per vsync after [requestFrame].
 */
class TerminalView(context: Context) : View(context) {
    var terminal: Terminal? = null
        set(value) {
            if (field === value) return
            field = value
            forceFull = true
            requestFrame()
        }

    var theme: Theme = Theme.EverforestDarkHard
        set(value) {
            field = value
            forceFull = true
            requestFrame()
        }

    /**
     * The grid that fits this view at the current font size; [ahead] when
     * it's for [settleHeight], a size the view will have but doesn't yet.
     */
    var onGridSize: ((cols: Int, rows: Int, ahead: Boolean) -> Unit)? = null

    /**
     * The height the view is about to settle at while it changes (the
     * keyboard sliding), or 0. Its grid is reported at once, so tmux resizes
     * while the keyboard moves, and rows laid out for it are drawn hanging
     * from the view's moving bottom edge, which is where they'll end up.
     */
    var settleHeight = 0
        set(value) {
            if (field == value) return
            if (value > 0) {
                settling = value
                settleFrom = height.toFloat()
            }
            field = value
            reportGrid()
            invalidate()
        }
    private var settling = 0 // the height last settled towards, held after the keyboard stops until the grid fits it
    private var settleFrom = 0f

    var fontSizeSp = 13f
        set(value) {
            field = value.coerceIn(6f, 32f)
            updateMetrics()
        }

    private val regular = typeface(context, R.font.jetbrains_mono_regular)
    private val bold = typeface(context, R.font.jetbrains_mono_bold)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = regular }
    private val fill = Paint()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val curl = Path()
    private val run = StringBuilder()

    private var cellWidth = 0f
    private var cellHeight = 0f
    private var baseline = 0f

    private val frame = FrameBuffer()
    private var cols = 0
    private var rows = 0
    private var nodes = emptyArray<RenderNode>()
    private var codepoints = IntArray(0) // per cell, for drawing the glyph under a block cursor
    private var cellFg = IntArray(0)
    private var background = theme.background
    private var foreground = theme.foreground
    private var cursorVisible = false
    private var cursorX = 0
    private var cursorY = 0
    private var cursorStyle = FrameBuffer.CURSOR_BLOCK
    private var cursorColor = DEFAULT
    private var altScreen = false
    private var scrollback = 0 // rows of history above the screen
    private var restHeight = 0f // the height the grid fills; see shift

    // The frame before the grid changed size, fading into the new one, and how it was laid out.
    private var fading: Array<RenderNode>? = null
    private var fadingRows = 0
    private var fadingCursorRows = 0
    private var fadingScrollback = 0
    private var fadingAlt = false
    private var fadingScale = 1f
    private var fadeStart = 0L

    private var forceFull = true
    private val frameRequested = AtomicBoolean()
    private val pull = Runnable { pullFrame() }

    private val predictions = Predictions()
    private val expirePredictions = Runnable { if (predictions.expire(SystemClock.uptimeMillis())) invalidate() }

    init {
        updateMetrics()
    }

    /**
     * Shows [text] at the cursor right away, in grey, until the pane echoes
     * it. Call on the UI thread just before sending it.
     */
    fun predict(text: String) {
        if (!cursorVisible || cols == 0) return
        predictions.add(text, cursorX, cursorY, cols, rows, SystemClock.uptimeMillis())
        scheduleExpiry()
        invalidate()
    }

    private fun scheduleExpiry() {
        removeCallbacks(expirePredictions)
        if (!predictions.isEmpty) postDelayed(expirePredictions, Predictions.TIMEOUT_MS + 16)
    }

    /** Safe from any thread; coalesces to one pull per vsync. */
    fun requestFrame() {
        if (!frameRequested.getAndSet(true)) postOnAnimation(pull)
    }

    /** Paused, it stops pulling frames (it's covered); it catches up when resumed. */
    var paused = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) requestFrame()
        }

    private fun pullFrame() {
        frameRequested.set(false)
        val t = terminal ?: return
        if (paused) return
        if (forceFull) t.setTheme(theme)
        if (!t.frame(frame, forceFull, reader = this)) return
        forceFull = false

        if (frame.cols != cols || frame.rows != rows) {
            if (cols > 0) {
                fading = nodes
                fadingRows = rows
                fadingCursorRows = cursorRows()
                fadingScrollback = scrollback
                fadingAlt = altScreen
                fadingScale = fitScale()
                fadeStart = SystemClock.uptimeMillis()
            }
            cols = frame.cols
            rows = frame.rows
            nodes = Array(rows) { RenderNode("row$it") }
            codepoints = IntArray(cols * rows)
            cellFg = IntArray(cols * rows)
            predictions.clear()
            if (!frame.full) {
                forceFull = true
                requestFrame()
                return
            }
        }
        background = frame.background
        foreground = frame.foreground
        for (i in 0 until frame.rowCount) record(i)
        cursorVisible = frame.cursorVisible && frame.cursorInViewport
        cursorX = frame.cursorX
        cursorY = frame.cursorY
        cursorStyle = frame.cursorStyle
        cursorColor = frame.cursorColor
        altScreen = frame.altScreen
        scrollback = (frame.scrollTotal - frame.scrollLength).coerceAtLeast(0)
        rest()
        if (!predictions.isEmpty && predictions.confirm(::codepointAt, SystemClock.uptimeMillis())) scheduleExpiry()
        invalidate()
    }

    private fun codepointAt(x: Int, y: Int) = if (x < cols && y < rows) codepoints[y * cols + x] else 0

    private fun record(i: Int) {
        val y = frame.rowY(i)
        if (y >= rows) return
        val node = nodes[y]
        node.setPosition(0, (y * cellHeight).roundToInt(), ceil(cols * cellWidth).toInt(), ((y + 1) * cellHeight).roundToInt())
        val c = node.beginRecording()

        var x = 0
        while (x < cols) {
            val bg = cellBg(i, x)
            var end = x + 1
            while (end < cols && cellBg(i, end) == bg) end++
            if (bg != DEFAULT) {
                fill.color = opaque(bg)
                c.drawRect(x * cellWidth, 0f, end * cellWidth, node.height.toFloat(), fill)
            }
            x = end
        }

        x = 0
        while (x < cols) {
            val flags = frame.flags(i, x)
            val wide = FrameBuffer.wide(flags)
            val cp = frame.codepoint(i, x)
            codepoints[y * cols + x] = cp
            cellFg[y * cols + x] = cellFg(i, x)
            if (wide >= 2 || cp == 0 || flags and FLAG_INVISIBLE != 0) {
                x++
                continue
            }
            val fg = cellFg(i, x)
            if (cp < 0x80 && wide == 0) {
                // A run of plain ASCII in one style draws as one string; the
                // font is monospace, so it lands on the grid.
                run.setLength(0)
                var end = x
                while (end < cols && sameRun(i, end, flags, fg)) {
                    val e = frame.codepoint(i, end)
                    run.append(if (e == 0) ' ' else e.toChar())
                    codepoints[y * cols + end] = e
                    cellFg[y * cols + end] = fg
                    end++
                }
                style(flags, fg)
                c.drawText(run, 0, run.length, x * cellWidth, baseline, text)
                decorate(c, i, x, end, flags)
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
                decorate(c, i, x, x + span, flags)
                x += span
            }
        }
        node.endRecording()
    }

    private fun sameRun(i: Int, x: Int, flags: Int, fg: Int): Boolean {
        val cp = frame.codepoint(i, x)
        val f = frame.flags(i, x)
        return cp < 0x80 && FrameBuffer.wide(f) == 0 && f and FLAG_GRAPHEME == 0 &&
            f and STYLE_MASK == flags and STYLE_MASK && cellFg(i, x) == fg && f and FLAG_INVISIBLE == 0
    }

    private fun cellFg(i: Int, x: Int): Int {
        val f = frame.flags(i, x)
        var fg = if (f and FLAG_INVERSE != 0) frame.bg(i, x).orIfDefault(background) else frame.fg(i, x).orIfDefault(foreground)
        if (f and FLAG_SELECTED != 0) fg = background
        return fg
    }

    private fun cellBg(i: Int, x: Int): Int {
        val f = frame.flags(i, x)
        if (f and FLAG_SELECTED != 0) return foreground
        return if (f and FLAG_INVERSE != 0) frame.fg(i, x).orIfDefault(foreground) else frame.bg(i, x)
    }

    private fun style(flags: Int, fg: Int) {
        text.typeface = if (flags and FLAG_BOLD != 0) bold else regular
        text.textSkewX = if (flags and FLAG_ITALIC != 0) -0.2f else 0f
        text.color = if (flags and FLAG_FAINT != 0) (fg and 0xFFFFFF) or (0x88 shl 24) else opaque(fg)
    }

    private fun decorate(c: Canvas, i: Int, from: Int, to: Int, flags: Int) {
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

    override fun onDraw(canvas: Canvas) {
        // Not drawColor: hosted in Compose the canvas isn't clipped to this view.
        fill.color = opaque(background)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        if (cols == 0) return
        val old = fading
        val t = (SystemClock.uptimeMillis() - fadeStart) / FADE_MS.toFloat()
        val oldAlpha = if (old != null && t < 1f) (1f - t) * (1f - t) else 0f
        if (old != null && oldAlpha == 0f) {
            fading = null
            for (node in nodes) node.alpha = 1f
        }
        settled()
        val scale = fitScale()
        canvas.save()
        canvas.translate(0f, -shift())
        canvas.scale(scale, scale)
        for (node in nodes) {
            if (!node.hasDisplayList()) continue
            if (fading != null) node.alpha = 1f - oldAlpha
            canvas.drawRenderNode(node)
        }
        drawPredictions(canvas)
        // The cursor runs ahead with the predictions, so typing feels local.
        val (cx, cy) = predictions.cursorAfter(cols) ?: (cursorX to cursorY)
        if (cursorVisible && cx < cols && cy < rows) drawCursor(canvas, cx, cy)
        canvas.restore()
        if (fading != null && old != null) {
            canvas.save()
            canvas.translate(0f, -shift(fadingRows, fadingCursorRows, fadingScrollback, fadingAlt, fadingScale))
            canvas.scale(fadingScale, fadingScale)
            for (node in old) {
                if (!node.hasDisplayList()) continue
                node.alpha = oldAlpha
                canvas.drawRenderNode(node)
            }
            canvas.restore()
            postInvalidateOnAnimation()
        }
    }

    /**
     * How far up the rows are drawn, in pixels; negative is down. The view
     * changes height first (the keyboard sliding), tmux changes the grid
     * after; meanwhile rows go where tmux will put them. Shorter: the cursor
     * stays in view, as tmux keeps it. Taller: rows move down as far as tmux
     * will pull history back in above them, all the way for full-screen
     * apps, which redraw against the bottom. With [settleHeight] known, rows
     * travel there in step with the view, and the grid tmux sends for it
     * hangs from the bottom edge, so the two line up as one fades into the
     * other.
     */
    private fun shift() = shift(rows, cursorRows(), scrollback, altScreen, fitScale())

    private fun shift(rows: Int, cursorRows: Int, scrollback: Int, alt: Boolean, scale: Float): Float {
        if (rows == 0) return 0f
        val cell = cellHeight * scale
        val h = height.toFloat()
        if (settling > 0) {
            val target = settling.toFloat()
            val targetRows = floor(target / cell).toInt()
            if (targetRows == rows) return target - h
            // Each row heads for where tmux will put it once the grid fits.
            val moved = when {
                targetRows > rows -> if (alt) targetRows - rows else minOf(scrollback, targetRows - rows)
                else -> -maxOf(0, cursorRows - targetRows)
            }
            val progress = if (settleFrom == target) 1f else ((settleFrom - h) / (settleFrom - target)).coerceIn(0f, 1f)
            return -moved * cell * progress
        }
        val content = rows * cell
        if (content > h) return (cursorRows * cell - h).coerceIn(0f, content - h)
        val room = h - restHeight
        if (restHeight == 0f || room <= 0f) return 0f
        return -(if (alt) room else minOf(room, scrollback * cell))
    }

    /** Where the cursor's row ends as drawn, in pixels from the top: what to keep in view when showing part of this. */
    fun cursorBottom(): Float = if (rows == 0) height.toFloat() else cursorRows() * cellHeight * fitScale() - shift()

    /** How wide the grid is drawn, in pixels; 0 before the first frame. */
    fun gridWidth(): Float = cols * cellWidth * fitScale()

    /** Rows down to the cursor's, which tmux keeps in view; all of them if it's hidden. */
    private fun cursorRows() = if (cursorVisible) cursorY + 1 else rows

    /** Stops settling once the view stopped where it said and the grid fits, or stopped elsewhere. */
    private fun settled() {
        if (settling == 0 || settleHeight != 0) return
        val target = settling.toFloat()
        if (height.toFloat() != target || floor(target / (cellHeight * fitScale())).toInt() == rows) settling = 0
    }

    /** Notes the height the grid fills, while it does. */
    private fun rest() {
        val cell = cellHeight * fitScale()
        if (rows > 0 && cell > 0f && floor(height / cell).toInt() == rows) restHeight = height.toFloat()
    }

    private fun drawPredictions(canvas: Canvas) {
        if (predictions.isEmpty) return
        text.typeface = regular
        text.textSkewX = 0f
        text.color = (foreground and 0xFFFFFF) or (0x70 shl 24)
        fill.color = opaque(background)
        for (p in predictions.cells) {
            val left = p.x * cellWidth
            val top = p.y * cellHeight
            // Cover whatever is there now, e.g. a shell's autosuggestion.
            canvas.drawRect(left, top, left + p.width * cellWidth, top + cellHeight, fill)
            canvas.drawText(p.text, left, top + baseline, text)
        }
    }

    private fun drawCursor(canvas: Canvas, cursorX: Int, cursorY: Int) {
        val left = cursorX * cellWidth
        val top = cursorY * cellHeight
        val color = opaque(cursorColor.orIfDefault(theme.cursor))
        fill.color = color
        when (cursorStyle) {
            FrameBuffer.CURSOR_BAR -> canvas.drawRect(left, top, left + maxOf(2f, cellWidth / 8), top + cellHeight, fill)
            FrameBuffer.CURSOR_UNDERLINE -> canvas.drawRect(left, top + cellHeight - maxOf(2f, cellHeight / 10), left + cellWidth, top + cellHeight, fill)
            FrameBuffer.CURSOR_BLOCK_HOLLOW -> {
                stroke.color = color
                stroke.strokeWidth = maxOf(1f, cellWidth / 10)
                stroke.pathEffect = null
                canvas.drawRect(left, top, left + cellWidth, top + cellHeight, stroke)
            }
            else -> {
                canvas.drawRect(left, top, left + cellWidth, top + cellHeight, fill)
                val cp = codepointAt(cursorX, cursorY)
                if (cp > 0x20) {
                    run.setLength(0)
                    run.appendCodePoint(cp)
                    text.typeface = regular
                    text.textSkewX = 0f
                    text.color = opaque(background)
                    canvas.drawText(run, 0, run.length, left, top + baseline, text)
                }
            }
        }
    }

    /** A pane wider than the view (the desktop owns the size) shrinks to fit. */
    private fun fitScale(): Float {
        val w = cols * cellWidth
        return if (w > width && width > 0) width / w else 1f
    }


    private fun updateMetrics() {
        text.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
        text.typeface = regular
        cellWidth = text.measureText("M")
        val fm = text.fontMetrics
        cellHeight = ceil((fm.descent - fm.ascent) * 1.15f)
        baseline = (cellHeight - (fm.descent - fm.ascent)) / 2 - fm.ascent
        forceFull = true
        reportGrid()
        requestFrame()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rest()
        reportGrid()
    }

    private fun reportGrid() {
        if (width == 0 || height == 0 || cellWidth == 0f) return
        val h = if (settleHeight > 0) settleHeight else height
        onGridSize?.invoke(floor(width / cellWidth).toInt(), floor(h / cellHeight).toInt(), settleHeight > 0)
    }

    /**
     * Full-screen apps own their scrolling: a scroll gesture over one sends
     * a mouse wheel notch per row ([wheel] holds its bytes), or an arrow key
     * if the app doesn't track the mouse ([wheel] is null).
     */
    var onAppScroll: ((up: Boolean, wheel: ByteArray?) -> Unit)? = null

    /** A sideways drag, for moving between panes: how far it has gone, as it goes. */
    var onSwipe: ((dx: Float) -> Unit)? = null

    /** The sideways drag let go: how far it went, and how fast (px/s; negative is leftwards). */
    var onSwipeEnd: ((dx: Float, velocity: Float) -> Unit)? = null

    // On the primary screen, scrolling moves the terminal's own viewport
    // through its scrollback and never sends anything to the pane.
    private val scroller = OverScroller(context)
    private var scrollRemainder = 0f
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollPixels(dy)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(0, 0, 0, -vy.toInt(), 0, 0, Int.MIN_VALUE, Int.MAX_VALUE)
            lastFlingY = 0
            postOnAnimation(fling)
            return true
        }
    })
    private var lastFlingY = 0
    private val fling = object : Runnable {
        override fun run() {
            if (!scroller.computeScrollOffset()) return
            scrollPixels((scroller.currY - lastFlingY).toFloat())
            lastFlingY = scroller.currY
            postOnAnimation(this)
        }
    }

    private fun scrollPixels(dy: Float) {
        val t = terminal ?: return
        val cell = cellHeight * fitScale()
        scrollRemainder += dy / cell
        val rows = scrollRemainder.toInt()
        if (rows == 0) return
        scrollRemainder -= rows
        val up = rows < 0 // towards history
        val col = (touchX / (cellWidth * fitScale())).toInt().coerceIn(0, maxOf(cols - 1, 0))
        val row = ((touchY + shift()) / cell).toInt().coerceIn(0, maxOf(this.rows - 1, 0))
        when (val wheel = t.wheel(up, col, row)) {
            Terminal.Wheel.Local -> {
                t.scrollBy(rows)
                requestFrame()
            }
            is Terminal.Wheel.Mouse -> repeat(abs(rows)) { onAppScroll?.invoke(up, wheel.bytes) }
            Terminal.Wheel.Arrows -> repeat(abs(rows)) { onAppScroll?.invoke(up, null) }
        }
    }

    fun scrollToBottom() {
        scroller.forceFinished(true)
        terminal?.scrollToBottom()
        requestFrame()
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var touchX = 0f
    private var touchY = 0f
    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private var swipeX = 0f
    private val velocity = VelocityTracker.obtain()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        touchX = event.x
        touchY = event.y
        if (event.actionMasked == MotionEvent.ACTION_DOWN) velocity.clear()
        velocity.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                swiping = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                if (!swiping && onSwipe != null && abs(dx) > slop * 2 && abs(dx) > abs(event.y - downY) * 1.5f) {
                    swiping = true
                    swipeX = event.x
                    scroller.forceFinished(true)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (swiping) {
                    onSwipe?.invoke(event.x - swipeX)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (swiping) {
                swiping = false
                velocity.computeCurrentVelocity(1000)
                val fling = if (event.actionMasked == MotionEvent.ACTION_UP) velocity.xVelocity else 0f
                onSwipeEnd?.invoke(event.x - swipeX, fling)
                return true
            }
        }
        return gestures.onTouchEvent(event) || super.onTouchEvent(event)
    }

    private fun Int.orIfDefault(other: Int) = if (this == DEFAULT) other else this
    private fun opaque(rgb: Int) = rgb or (0xFF shl 24)

    private companion object {
        val STYLE_MASK = FLAG_BOLD or FLAG_ITALIC or FLAG_FAINT or (7 shl 8) or FLAG_STRIKETHROUGH or FLAG_OVERLINE or
            FLAG_INVERSE or FLAG_SELECTED

        const val FADE_MS = 180L

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
