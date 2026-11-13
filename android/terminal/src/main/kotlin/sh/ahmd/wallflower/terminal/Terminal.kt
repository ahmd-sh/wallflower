package sh.ahmd.wallflower.terminal

import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import sh.ahmd.wallflower.screen.Screen

/**
 * One pane's terminal, backed by libghostty-vt. Pane output is written from
 * the IO thread, and listeners hear of it there; the renderer pulls frames
 * on the UI thread.
 */
class Terminal(cols: Int, rows: Int, scrollback: Int = 10_000) : AutoCloseable {
    private val lock = ReentrantReadWriteLock() // close vs. everything else
    private var handle = nativeNew(cols, rows, scrollback).also {
        check(it != 0L) { "libghostty-vt: could not create a terminal" }
    }

    private val listeners = CopyOnWriteArraySet<Runnable>()

    /** [listener] runs, on the writing thread, after output changes the terminal. */
    fun addListener(listener: Runnable) = listeners.add(listener)

    fun removeListener(listener: Runnable) = listeners.remove(listener)

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        lock.read {
            if (handle == 0L || length <= 0) return
            nativeWrite(handle, bytes, offset, length)
        }
        for (l in listeners) l.run()
    }

    fun resize(cols: Int, rows: Int, cellWidthPx: Int = 0, cellHeightPx: Int = 0) = lock.read {
        if (handle != 0L) nativeResize(handle, cols, rows, cellWidthPx, cellHeightPx)
    }

    /** Scrolls the viewport by [rows]; negative is towards history. */
    fun scrollBy(rows: Int) = scroll(0, rows)
    fun scrollToTop() = scroll(1, 0)
    fun scrollToBottom() = scroll(2, 0)

    private fun scroll(mode: Int, delta: Int) = lock.read {
        if (handle != 0L) nativeScroll(handle, mode, delta)
    }

    /** How a scroll gesture reaches this terminal. */
    sealed interface Wheel {
        /** The primary screen: scroll the local scrollback. */
        data object Local : Wheel
        /** A full-screen app tracking the mouse: send it these bytes. */
        class Mouse(val bytes: ByteArray) : Wheel
        /** A full-screen app that doesn't track the mouse: send arrow keys. */
        data object Arrows : Wheel
    }

    /** A wheel notch at a cell. */
    fun wheel(up: Boolean, col: Int, row: Int): Wheel = lock.read {
        if (handle == 0L) return@read Wheel.Local
        val out = ByteArray(64)
        when (val n = nativeWheel(handle, up, col, row, out)) {
            -1 -> Wheel.Local
            0 -> Wheel.Arrows
            else -> Wheel.Mouse(out.copyOf(n))
        }
    }

    /** The active screen as it stands, or null if the terminal is closed. */
    fun screen(): Screen? = lock.read {
        if (handle == 0L) null else nativeScreen(handle)?.let(Screen::parse)
    }

    fun setTheme(theme: Theme) = lock.read {
        if (handle != 0L) nativeSetColors(handle, theme.foreground, theme.background, theme.cursor, theme.palette)
    }

    /**
     * Packs what changed since the last call (everything if [force]) into
     * [into]. Returns false if there is nothing to draw, including while the
     * app holds a synchronized update. Changes are taken once, so a [reader]
     * other than the last one gets everything: two views can show one pane.
     */
    fun frame(into: FrameBuffer, force: Boolean = false, reader: Any? = null): Boolean = lock.read {
        if (handle == 0L) return@read false
        val full = force || reader !== lastReader
        var n = nativeFrame(handle, into.buffer, full)
        if (n < 0) {
            into.grow(-n) // nothing was consumed; the retry packs the same changes
            n = nativeFrame(handle, into.buffer, full)
        }
        into.load(maxOf(n, 0))
        if (n > 0) lastReader = reader
        n > 0
    }

    @Volatile private var lastReader: Any? = null

    /** A selection's first and last cells in reading order, in the viewport; rows scrolled out of view are outside it. */
    data class Selection(val startCol: Int, val startRow: Int, val endCol: Int, val endRow: Int) {
        /** Whether a viewport cell is in it. */
        fun contains(col: Int, row: Int): Boolean =
            row in startRow..endRow && !(row == startRow && col < startCol) && !(row == endRow && col > endCol)
    }

    /**
     * Selects the word at a viewport cell, the terminal keeping it on its
     * cells as they scroll; false if there's no word there. A drag from it
     * then extends it ([selectExtend]).
     */
    fun selectWord(col: Int, row: Int): Boolean = lock.read { handle != 0L && nativeSelectWord(handle, col, row) }

    /** The selection from the word [selectWord] picked to a cell, the word kept in it. */
    fun selectExtend(col: Int, row: Int) = lock.read { if (handle != 0L) nativeSelectExtend(handle, col, row) }

    /** Frees one end of the selection, its first in reading order or its [last], for [selectTo] to move; the other stays put. */
    fun selectHold(last: Boolean) = lock.read { if (handle != 0L) nativeSelectHold(handle, last) }

    fun selectTo(col: Int, row: Int) = lock.read { if (handle != 0L) nativeSelectTo(handle, col, row) }

    /** Selects what's in view. */
    fun selectView() = lock.read { if (handle != 0L) nativeSelectView(handle) }

    fun unselect() = lock.read { if (handle != 0L) nativeUnselect(handle) }

    fun selection(): Selection? = lock.read {
        val out = IntArray(4)
        if (handle != 0L && nativeSelection(handle, out)) Selection(out[0], out[1], out[2], out[3]) else null
    }

    /** The selected text as a copy has it: rows the program wrapped joined, trailing blanks gone. */
    fun selectionText(): String? = lock.read {
        if (handle == 0L) null else nativeSelectionText(handle)?.decodeToString()
    }

    override fun close() = lock.write {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
    }

    private companion object {
        init {
            System.loadLibrary("wallflower_terminal")
        }

        @JvmStatic external fun nativeNew(cols: Int, rows: Int, scrollback: Int): Long
        @JvmStatic external fun nativeFree(handle: Long)
        @JvmStatic external fun nativeWrite(handle: Long, data: ByteArray, offset: Int, length: Int)
        @JvmStatic external fun nativeResize(handle: Long, cols: Int, rows: Int, cellWidth: Int, cellHeight: Int)
        @JvmStatic external fun nativeScroll(handle: Long, mode: Int, delta: Int)
        @JvmStatic external fun nativeSetColors(handle: Long, fg: Int, bg: Int, cursor: Int, palette: IntArray)
        @JvmStatic external fun nativeWheel(handle: Long, up: Boolean, col: Int, row: Int, out: ByteArray): Int
        @JvmStatic external fun nativeScreen(handle: Long): ByteArray?
        @JvmStatic external fun nativeFrame(handle: Long, buffer: ByteBuffer, force: Boolean): Int
        @JvmStatic external fun nativeSelectWord(handle: Long, col: Int, row: Int): Boolean
        @JvmStatic external fun nativeSelectExtend(handle: Long, col: Int, row: Int)
        @JvmStatic external fun nativeSelectHold(handle: Long, last: Boolean)
        @JvmStatic external fun nativeSelectTo(handle: Long, col: Int, row: Int)
        @JvmStatic external fun nativeSelectView(handle: Long)
        @JvmStatic external fun nativeUnselect(handle: Long)
        @JvmStatic external fun nativeSelection(handle: Long, out: IntArray): Boolean
        @JvmStatic external fun nativeSelectionText(handle: Long): ByteArray?
    }
}
