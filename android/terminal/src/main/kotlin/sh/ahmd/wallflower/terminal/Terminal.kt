package sh.ahmd.wallflower.terminal

import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * One pane's terminal, backed by libghostty-vt. Pane output is written from
 * the IO thread; the renderer pulls frames on the UI thread.
 */
class Terminal(cols: Int, rows: Int, scrollback: Int = 10_000) : AutoCloseable {
    private val lock = ReentrantReadWriteLock() // close vs. everything else
    private var handle = nativeNew(cols, rows, scrollback).also {
        check(it != 0L) { "libghostty-vt: could not create a terminal" }
    }

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) = lock.read {
        if (handle != 0L && length > 0) nativeWrite(handle, bytes, offset, length)
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
     * app holds a synchronized update.
     */
    fun frame(into: FrameBuffer, force: Boolean = false): Boolean = lock.read {
        if (handle == 0L) return@read false
        var n = nativeFrame(handle, into.buffer, force)
        if (n < 0) {
            into.grow(-n) // nothing was consumed; the retry packs the same changes
            n = nativeFrame(handle, into.buffer, force)
        }
        into.load(maxOf(n, 0))
        n > 0
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
    }
}
