package sh.ahmd.wallflower.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RenderNode
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.SystemClock
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
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
import sh.ahmd.wallflower.terminal.FrameBuffer.Companion.FLAG_STRIKETHROUGH

/**
 * Draws a [Terminal]. Each row is a RenderNode re-recorded only when the
 * terminal reports it dirty, so a frame costs as much as what changed.
 * Content is pulled once per vsync after [requestFrame].
 *
 * Held, it selects the word there, and the drag that follows takes in
 * more; the selection's ends then have handles to drag. The terminal keeps
 * it on its cells as they scroll. The floating toolbar copies it, pastes
 * ([onPaste]), or selects all that's in view.
 */
class TerminalView(context: Context) : View(context) {
    /** What it shows; while attached, its output brings a frame. */
    var terminal: Terminal? = null
        set(value) {
            if (field === value) return
            if (isAttachedToWindow) field?.removeListener(changed)
            if (selected != null) {
                field?.unselect()
                selected = null
                hideMenu()
            }
            field = value
            if (isAttachedToWindow) value?.addListener(changed)
            forceFull = true
            requestFrame()
        }

    private val changed = Runnable { requestFrame() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        terminal?.addListener(changed)
        requestFrame()
    }

    override fun onDetachedFromWindow() {
        terminal?.removeListener(changed)
        super.onDetachedFromWindow()
    }

    var theme: Theme = Theme.EverforestDarkHard
        set(value) {
            if (field == value) return
            field = value
            background = value.background // until a frame brings the terminal's own, or with no terminal
            foreground = value.foreground
            forceFull = true
            requestFrame()
            invalidate()
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
        if (selected != null) readSelection()
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

    private fun cellFg(i: Int, x: Int): Int =
        if (frame.flags(i, x) and FLAG_INVERSE != 0) frame.bg(i, x).orIfDefault(background) else frame.fg(i, x).orIfDefault(foreground)

    private fun cellBg(i: Int, x: Int): Int {
        val f = frame.flags(i, x)
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
        drawSelection(canvas)
        drawPredictions(canvas)
        // The cursor runs ahead with the predictions, so typing feels local.
        val (cx, cy) = predictions.cursorAfter(cols) ?: (cursorX to cursorY)
        if (cursorVisible && cx < cols && cy < rows) drawCursor(canvas, cx, cy)
        canvas.restore()
        drawHandles(canvas)
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

    /** A tap, not a scroll or a swipe. */
    var onTap: (() -> Unit)? = null

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

        // With text selected, a tap is for the selection: the toolbar again on it, else letting it go.
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val s = selected
            if (s != null) {
                val (col, row) = cell(e.x, e.y)
                if (s.contains(col, row)) showMenu() else clearSelection()
                return true
            }
            onTap?.invoke()
            return onTap != null
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            hideMenu()
            scrollPixels(dy)
            return true
        }

        // Held on a word, it's selected, and the drag that follows takes in more; where there's no word, the toolbar comes up there.
        override fun onLongPress(e: MotionEvent) {
            val t = terminal ?: return
            scroller.forceFinished(true)
            hideMenu()
            onHeld?.invoke()
            val (col, row) = cell(e.x, e.y)
            if (t.selectWord(col, row)) {
                extending = true
                parent?.requestDisallowInterceptTouchEvent(true)
                readSelection()
            } else {
                clearSelection()
                showMenu(PointF(e.x, e.y))
            }
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
                extending = false
                if (grab(event.x, event.y)) {
                    scroller.forceFinished(true)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (grabbed >= 0) {
                    drag(event.x, event.y)
                    return true
                }
                if (extending) {
                    val (col, row) = cell(event.x, event.y)
                    terminal?.selectExtend(col, row)
                    if (readSelection()) onSelectionMoved?.invoke()
                    return true
                }
                val dx = event.x - downX
                if (!swiping && onSwipe != null && abs(dx) > slop * 2 && abs(dx) > abs(event.y - downY) * 1.5f) {
                    swiping = true
                    swipeX = event.x
                    scroller.forceFinished(true)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    clearSelection()
                }
                if (swiping) {
                    onSwipe?.invoke(event.x - swipeX)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (grabbed >= 0 || extending) {
                grabbed = -1
                extending = false
                if (event.actionMasked == MotionEvent.ACTION_UP) showMenu()
                return true
            } else if (swiping) {
                swiping = false
                velocity.computeCurrentVelocity(1000)
                val fling = if (event.actionMasked == MotionEvent.ACTION_UP) velocity.xVelocity else 0f
                onSwipeEnd?.invoke(event.x - swipeX, fling)
                return true
            }
        }
        return gestures.onTouchEvent(event) || super.onTouchEvent(event)
    }

    // Selection

    /** Held: felt, as the word there is selected or the toolbar comes up. */
    var onHeld: (() -> Unit)? = null

    /** A selection's end moved to another cell: felt. */
    var onSelectionMoved: (() -> Unit)? = null

    /** Text pasted from the toolbar, for the pane; null where it can't be typed into, and there's no paste. */
    var onPaste: ((String) -> Unit)? = null

    /** What selected text is marked in, and its handles. */
    var selectionColor = 0xFFA7C080.toInt()
        set(value) {
            if (field == value) return
            field = value
            if (selected != null) invalidate()
        }

    /** The selection made here, its first and last cells in the viewport, while the terminal keeps one. */
    private var selected: Terminal.Selection? = null
    /** A press selected a word, and its drag takes in more. */
    private var extending = false
    /** The handle being dragged (0 the start, 1 the end; -1 none), and how far off its cell's middle the finger took it. */
    private var grabbed = -1
    private var grabX = 0f
    private var grabY = 0f
    private var menu: ActionMode? = null
    private var menuAt: PointF? = null // where a toolbar with no selection was asked for
    private var dropping = false // the toolbar's put away from here, which keeps the selection
    private val density = resources.displayMetrics.density
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)

    /** The viewport cell under a point in the view, kept within the grid. */
    private fun cell(x: Float, y: Float): Pair<Int, Int> {
        val s = fitScale()
        val col = floor(x / (cellWidth * s)).toInt().coerceIn(0, maxOf(cols - 1, 0))
        val row = floor((y + shift()) / (cellHeight * s)).toInt().coerceIn(0, maxOf(rows - 1, 0))
        return col to row
    }

    /** What the terminal has selected now, which output may have moved or cleared; true if that changed. */
    private fun readSelection(): Boolean {
        val now = terminal?.selection()
        if (now == selected) return false
        selected = now
        if (now == null) hideMenu()
        invalidate()
        return true
    }

    /** Lets go of the selection: tapped away, swiped to another pane, or typed over. */
    fun clearSelection() {
        extending = false
        grabbed = -1
        if (selected == null) return
        terminal?.unselect()
        selected = null
        hideMenu()
        invalidate()
    }

    /** Whole rows between the selection's first and last, in the grid's own pixels, as drawn under the scale. */
    private fun drawSelection(canvas: Canvas) {
        val s = selected ?: return
        fill.color = (selectionColor and 0xFFFFFF) or (0x55 shl 24)
        for (y in maxOf(s.startRow, 0)..minOf(s.endRow, rows - 1)) {
            val from = if (y == s.startRow) s.startCol else 0
            val to = if (y == s.endRow) s.endCol + 1 else cols
            canvas.drawRect(from * cellWidth, y * cellHeight, to * cellWidth, (y + 1) * cellHeight, fill)
        }
    }

    /** Where a handle's point is in the view (the start's at its cell's bottom left, the end's at its bottom right), or null out of view. */
    private fun handlePoint(end: Int): PointF? {
        val s = selected ?: return null
        val row = if (end == 0) s.startRow else s.endRow
        if (row < 0 || row >= rows) return null
        val k = fitScale()
        val col = if (end == 0) s.startCol else s.endCol + 1
        return PointF(col * cellWidth * k, (row + 1) * cellHeight * k - shift())
    }

    /** The handles, as Android's text has them: a drop hanging below each end, its corner on the end. */
    private fun drawHandles(canvas: Canvas) {
        if (selected == null) return
        val r = HANDLE_RADIUS * density
        fill.color = opaque(selectionColor)
        for (end in 0..1) {
            val p = handlePoint(end) ?: continue
            val cx = if (end == 0) p.x - r else p.x + r
            canvas.drawCircle(cx, p.y + r, r, fill)
            canvas.drawRect(minOf(cx, p.x), p.y, maxOf(cx, p.x), p.y + r, fill)
        }
    }

    /** A touch at x, y on a handle takes it, its other end held still; false if it isn't near one. */
    private fun grab(x: Float, y: Float): Boolean {
        val t = terminal ?: return false
        if (selected == null) return false
        val r = HANDLE_RADIUS * density
        val reach = r + HANDLE_REACH * density
        var nearest = -1
        var best = Float.MAX_VALUE
        for (end in 0..1) {
            val p = handlePoint(end) ?: continue
            val cx = if (end == 0) p.x - r else p.x + r
            val d = kotlin.math.hypot(x - cx, y - (p.y + r))
            if (d < reach && d < best) {
                nearest = end
                best = d
            }
        }
        if (nearest < 0) return false
        val p = handlePoint(nearest)!!
        val k = fitScale()
        // From the finger to the middle of the cell the handle stands for.
        grabX = (if (nearest == 0) p.x + cellWidth * k / 2 else p.x - cellWidth * k / 2) - x
        grabY = p.y - cellHeight * k / 2 - y
        grabbed = nearest
        hideMenu()
        t.selectHold(last = nearest == 1)
        return true
    }

    /** The grabbed handle's cell follows the finger. */
    private fun drag(x: Float, y: Float) {
        val (col, row) = cell(x + grabX, y + grabY)
        terminal?.selectTo(col, row)
        if (readSelection()) onSelectionMoved?.invoke()
    }

    /** Where the selection is in the view, as far as it's in view. */
    private fun selectionFrame(): Rect? {
        val s = selected ?: return null
        val top = maxOf(s.startRow, 0)
        val bottom = minOf(s.endRow, rows - 1)
        if (top > bottom) return null
        val k = fitScale()
        val left = if (top == bottom) s.startCol * cellWidth * k else 0f
        val right = if (top == bottom) (s.endCol + 1) * cellWidth * k else cols * cellWidth * k
        val r = Rect(left.toInt(), (top * cellHeight * k - shift()).toInt(), ceil(right).toInt(), ceil((bottom + 1) * cellHeight * k - shift()).toInt())
        return if (r.intersect(0, 0, width, height)) r else null
    }

    /** The floating toolbar, over the selection, or at [at] with none. */
    private fun showMenu(at: PointF? = null) {
        menuAt = at
        val m = menu
        if (m != null) {
            m.invalidate()
            m.invalidateContentRect()
        } else {
            menu = startActionMode(menuCallback, ActionMode.TYPE_FLOATING)
        }
    }

    private fun hideMenu() {
        val m = menu ?: return
        menu = null
        dropping = true
        m.finish()
        dropping = false
    }

    private val canPaste get() = onPaste != null && clipboard?.primaryClipDescription?.hasMimeType("text/*") == true

    private val menuCallback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, items: Menu): Boolean = onPrepareActionMode(mode, items)

        override fun onPrepareActionMode(mode: ActionMode, items: Menu): Boolean {
            items.clear()
            if (selected != null) items.add(Menu.NONE, android.R.id.copy, 0, android.R.string.copy)
            if (canPaste) items.add(Menu.NONE, android.R.id.paste, 1, android.R.string.paste)
            items.add(Menu.NONE, android.R.id.selectAll, 2, android.R.string.selectAll)
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                android.R.id.copy -> {
                    val text = terminal?.selectionText()?.trim('\n')
                    if (!text.isNullOrEmpty()) clipboard?.setPrimaryClip(ClipData.newPlainText("terminal", text))
                    clearSelection()
                }
                android.R.id.paste -> {
                    val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    clearSelection()
                    hideMenu()
                    if (!text.isNullOrEmpty()) onPaste?.invoke(text)
                }
                android.R.id.selectAll -> {
                    terminal?.selectView()
                    readSelection()
                    showMenu()
                }
            }
            return true
        }

        // Put away by the user (back): the selection goes with it.
        override fun onDestroyActionMode(mode: ActionMode) {
            if (menu === mode) menu = null
            if (!dropping) clearSelection()
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            val r = selectionFrame()
            val at = menuAt
            when {
                r != null -> outRect.set(r)
                at != null -> outRect.set(at.x.toInt(), at.y.toInt(), at.x.toInt() + 1, at.y.toInt() + 1)
                else -> outRect.set(0, 0, width, height)
            }
        }
    }

    private fun Int.orIfDefault(other: Int) = if (this == DEFAULT) other else this
    private fun opaque(rgb: Int) = rgb or (0xFF shl 24)

    private companion object {
        val STYLE_MASK = FLAG_BOLD or FLAG_ITALIC or FLAG_FAINT or (7 shl 8) or FLAG_STRIKETHROUGH or FLAG_OVERLINE or
            FLAG_INVERSE

        const val FADE_MS = 180L
        const val HANDLE_RADIUS = 10f // dp
        const val HANDLE_REACH = 14f // dp past a handle that still takes it

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
