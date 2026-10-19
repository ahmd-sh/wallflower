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
 * on the UI thread. A shell that marks its prompts (OSC 133) has its
 * [commands] told apart, and any of its [rows] can be read.
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
        val marks = lock.read {
            if (handle == 0L || length <= 0) return
            nativeWrite(handle, bytes, offset, length)
        }
        if (marks) marked = true
        for (l in listeners) l.run()
    }

    /** A shell has marked a command's prompt here (OSC 133), so its [commands] can be told apart. */
    @Volatile var marked = false
        private set

    /** The commands on the primary screen, or null while a full-screen app has the alternate screen. */
    fun commands(): Commands? = lock.read {
        if (handle == 0L) return@read null
        val a = nativeCommands(handle) ?: return@read null
        if (a[0] == 1) return@read null
        Commands(a[1], a[2], List(a[3]) { i -> (4 + i * 5).let { o -> Command(a[o], a[o + 1], a[o + 2], a[o + 3], a[o + 4] == 1) } })
    }

    /**
     * What was typed at command [id] as it started to run, read then, while
     * its prompt was on the screen as typed (a full-screen app it starts
     * covers it next); null if it wasn't seen run.
     */
    fun typed(id: Int): Typed? = lock.read {
        if (handle == 0L) return@read null
        nativeTyped(handle, id)?.let { Typed(it[0], it[1]) }
    }

    /**
     * Packs [count] rows of the primary screen from row [from] (0 is the top
     * of the scrollback) into [into], as a full frame without a cursor; fewer
     * if the screen ends first. False if there are none to read, as while a
     * full-screen app has the alternate screen.
     */
    fun rows(from: Int, count: Int, into: FrameBuffer): Boolean = lock.read {
        if (handle == 0L) return@read false
        var n = nativeRows(handle, into.buffer, from, count)
        if (n < 0) {
            into.grow(-n)
            n = nativeRows(handle, into.buffer, from, count)
        }
        into.load(maxOf(n, 0))
        n > 0
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
        @JvmStatic external fun nativeWrite(handle: Long, data: ByteArray, offset: Int, length: Int): Boolean
        @JvmStatic external fun nativeResize(handle: Long, cols: Int, rows: Int, cellWidth: Int, cellHeight: Int)
        @JvmStatic external fun nativeScroll(handle: Long, mode: Int, delta: Int)
        @JvmStatic external fun nativeSetColors(handle: Long, fg: Int, bg: Int, cursor: Int, palette: IntArray)
        @JvmStatic external fun nativeWheel(handle: Long, up: Boolean, col: Int, row: Int, out: ByteArray): Int
        @JvmStatic external fun nativeScreen(handle: Long): ByteArray?
        @JvmStatic external fun nativeFrame(handle: Long, buffer: ByteBuffer, force: Boolean): Int
        @JvmStatic external fun nativeCommands(handle: Long): IntArray?
        @JvmStatic external fun nativeRows(handle: Long, buffer: ByteBuffer, from: Int, count: Int): Int
        @JvmStatic external fun nativeTyped(handle: Long, id: Int): Array<String>?
    }
}

/**
 * What a shell marked on the primary screen: its [rows], scrollback
 * included; the [history] above its first command, up to the last row with
 * anything on it; and its commands, oldest first.
 */
class Commands(val rows: Int, val history: Int, val list: List<Command>)

/**
 * A command a shell marked: its prompt from row [start], its output from row
 * [output] (-1 until it runs), up to [end], after its last row with anything
 * on it. Its [id] stays as its rows move. [typed]: the shell marked where
 * typing starts, as it does live; a repaint keeps only where prompts and
 * output start, so whether one ran without output isn't known.
 */
data class Command(val id: Int, val start: Int, val output: Int, val end: Int, val typed: Boolean = true) {
    val ran get() = output >= 0
}

/** What was typed at a command's prompt, [text], after the prompt's own cells, [prompt], as the shell marked them. */
data class Typed(val prompt: String, val text: String)
