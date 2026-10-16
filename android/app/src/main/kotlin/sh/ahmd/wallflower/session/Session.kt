package sh.ahmd.wallflower.session

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import sh.ahmd.wallflower.protocol.Bye
import sh.ahmd.wallflower.protocol.ClientHello
import sh.ahmd.wallflower.protocol.ClientMessage
import sh.ahmd.wallflower.protocol.Close
import sh.ahmd.wallflower.protocol.ErrorMessage
import sh.ahmd.wallflower.protocol.Frame
import sh.ahmd.wallflower.protocol.FrameReader
import sh.ahmd.wallflower.protocol.FrameWriter
import sh.ahmd.wallflower.protocol.Open
import sh.ahmd.wallflower.protocol.Pane
import sh.ahmd.wallflower.protocol.Ping
import sh.ahmd.wallflower.protocol.Pong
import sh.ahmd.wallflower.protocol.Reset
import sh.ahmd.wallflower.protocol.Resize
import sh.ahmd.wallflower.protocol.Select
import sh.ahmd.wallflower.protocol.ServerHello
import sh.ahmd.wallflower.protocol.ServerMessage
import sh.ahmd.wallflower.protocol.State
import sh.ahmd.wallflower.protocol.Window
import sh.ahmd.wallflower.terminal.Terminal

sealed interface Status {
    data object Connecting : Status
    data class Connected(val tmux: String) : Status
    data class Disconnected(val reason: String) : Status
}

/**
 * One connection to the gateway, reconnecting until [stop].
 *
 * tmux state arrives pushed and is kept here; presentation is local. The
 * window being viewed is the app's choice and changes instantly, the gateway
 * is told in the background. Every window's active pane is kept open and
 * streaming, so switching to it shows current content without a round trip.
 */
class Session(private val transport: Transport, private val scope: CoroutineScope) {
    private val _status = MutableStateFlow<Status>(Status.Connecting)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    /** The window on screen; local, so it changes the moment a tab is tapped. */
    private val _viewing = MutableStateFlow<String?>(null)
    val viewing: StateFlow<String?> = _viewing.asStateFlow()

    /** Terminals of open panes, replaced on every reset. */
    private val _terminals = MutableStateFlow<Map<String, Terminal>>(emptyMap())
    val terminals: StateFlow<Map<String, Terminal>> = _terminals.asStateFlow()

    /** Called on the IO thread after a pane's terminal changes. */
    @Volatile var onOutput: ((pane: String) -> Unit)? = null

    @Volatile private var writer: FrameWriter? = null
    @Volatile private var lastPong = 0L
    private val opened = LinkedHashSet<String>() // least recently viewed first
    private val pings = AtomicInteger()
    private var grid = 80 to 24
    private var job: Job? = null

    fun start(cols: Int, rows: Int) {
        grid = cols to rows
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            var backoff = 500L
            while (isActive) {
                _status.value = Status.Connecting
                val reason = try {
                    runOnce()
                    backoff = 500L
                    "connection closed"
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
                writer = null
                synchronized(opened) { opened.clear() }
                if (_status.value !is Status.Disconnected) _status.value = Status.Disconnected(reason)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(5_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun runOnce() {
        val conn = transport.open()
        conn.use {
            val w = FrameWriter(conn.output)
            w.send(ClientHello(cols = grid.first, rows = grid.second))
            writer = w
            lastPong = System.nanoTime()
            val heartbeat = scope.launch(Dispatchers.IO) { heartbeat(conn) }
            try {
                val r = FrameReader(conn.input)
                while (true) {
                    when (val f = r.next() ?: return) {
                        is Frame.Data -> {
                            _terminals.value[f.pane]?.write(f.bytes)
                            onOutput?.invoke(f.pane)
                        }
                        is Frame.Message -> handle(f.message)
                        is Frame.Unknown -> Unit
                    }
                }
            } finally {
                heartbeat.cancel()
            }
        }
    }

    /** Mobile connections die silently; a missed pong closes the socket so we reconnect. */
    private suspend fun heartbeat(conn: Connection) {
        while (true) {
            delay(HEARTBEAT_MS)
            if (System.nanoTime() - lastPong > (HEARTBEAT_MS + PONG_TIMEOUT_MS) * 1_000_000) {
                conn.close()
                return
            }
            runCatching { writer?.send(Ping(pings.incrementAndGet())) }
        }
    }

    private fun handle(m: ServerMessage) {
        when (m) {
            is ServerHello -> _status.value = Status.Connected(m.tmux)
            is State -> {
                _state.value = m
                if (m.windows.none { it.id == _viewing.value }) _viewing.value = m.windows.firstOrNull { it.active }?.id
                for (w in m.windows) w.activePane()?.let { open(it.id, viewed = w.id == _viewing.value) }
                // Panes that no longer exist close quietly; the gateway forgot them already.
                val live = m.windows.flatMap { w -> w.panes.map { it.id } }.toSet()
                synchronized(opened) { opened.retainAll(live) }
                val gone = _terminals.value.filterKeys { it !in live }
                if (gone.isNotEmpty()) {
                    _terminals.value = _terminals.value - gone.keys
                    gone.values.forEach { it.close() }
                }
            }
            is Reset -> {
                val term = Terminal(m.cols, m.rows)
                val old = _terminals.value[m.pane]
                _terminals.value = _terminals.value + (m.pane to term)
                old?.close()
            }
            is Pong -> lastPong = System.nanoTime()
            is Bye -> _status.value = Status.Disconnected(m.reason)
            is ErrorMessage -> android.util.Log.w("wallflower", "gateway: ${m.message}")
            else -> Unit
        }
    }

    /** Opens a pane if it isn't already, keeping at most [MAX_OPEN], least recently viewed out first. */
    private fun open(pane: String, viewed: Boolean) {
        val evict = synchronized(opened) {
            val isNew = pane !in opened
            if (viewed || isNew) {
                opened.remove(pane)
                opened.add(pane)
            }
            if (isNew) send(Open(pane))
            if (opened.size > MAX_OPEN) opened.first().also { opened.remove(it) } else null
        }
        if (evict != null) {
            send(Close(evict))
            val term = _terminals.value[evict]
            _terminals.value = _terminals.value - evict
            term?.close()
        }
    }

    /** Shows a window now; tmux follows in the background (it sizes the window for the phone). */
    fun view(window: Window) {
        _viewing.value = window.id
        window.activePane()?.let { open(it.id, viewed = true) }
        send(Select(window = window.id))
    }

    fun send(m: ClientMessage) {
        scope.launch(Dispatchers.IO) { runCatching { writer?.send(m) } }
    }

    fun type(pane: String, text: String) {
        val bytes = text.encodeToByteArray()
        scope.launch(Dispatchers.IO) { runCatching { writer?.sendData(pane, bytes) } }
    }

    fun resize(cols: Int, rows: Int) {
        if (grid == cols to rows) return
        grid = cols to rows
        send(Resize(cols, rows))
    }

    companion object {
        const val MAX_OPEN = 12
        const val HEARTBEAT_MS = 15_000L
        const val PONG_TIMEOUT_MS = 10_000L

        fun Window.activePane(): Pane? = panes.firstOrNull { it.active }
    }
}
