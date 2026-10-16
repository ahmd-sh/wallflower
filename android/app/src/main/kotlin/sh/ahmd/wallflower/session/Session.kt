package sh.ahmd.wallflower.session

import java.util.concurrent.ConcurrentHashMap
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
import sh.ahmd.wallflower.protocol.ErrorMessage
import sh.ahmd.wallflower.protocol.Frame
import sh.ahmd.wallflower.protocol.FrameReader
import sh.ahmd.wallflower.protocol.FrameWriter
import sh.ahmd.wallflower.protocol.Open
import sh.ahmd.wallflower.protocol.Pane
import sh.ahmd.wallflower.protocol.Reset
import sh.ahmd.wallflower.protocol.Resize
import sh.ahmd.wallflower.protocol.ServerHello
import sh.ahmd.wallflower.protocol.State
import sh.ahmd.wallflower.terminal.Terminal

sealed interface Status {
    data object Connecting : Status
    data class Connected(val tmux: String) : Status
    data class Disconnected(val reason: String) : Status
}

/**
 * One connection to the gateway, reconnecting until [stop]. It follows
 * tmux's active pane: that pane is opened and its terminal kept current.
 */
class Session(private val transport: Transport, private val scope: CoroutineScope) {
    private val _status = MutableStateFlow<Status>(Status.Connecting)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    /** Terminals of open panes, replaced on every reset. */
    private val _terminals = MutableStateFlow<Map<String, Terminal>>(emptyMap())
    val terminals: StateFlow<Map<String, Terminal>> = _terminals.asStateFlow()

    /** Called on the IO thread after a pane's terminal changes. */
    @Volatile var onOutput: ((pane: String) -> Unit)? = null

    @Volatile private var writer: FrameWriter? = null
    private val opened = ConcurrentHashMap.newKeySet<String>()
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
                opened.clear()
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
        }
    }

    private fun handle(m: sh.ahmd.wallflower.protocol.ServerMessage) {
        when (m) {
            is ServerHello -> _status.value = Status.Connected(m.tmux)
            is State -> {
                _state.value = m
                activePane(m)?.let { if (opened.add(it.id)) send(Open(it.id)) }
            }
            is Reset -> {
                val term = Terminal(m.cols, m.rows)
                val old = _terminals.value[m.pane]
                _terminals.value = _terminals.value + (m.pane to term)
                old?.close()
            }
            is Bye -> _status.value = Status.Disconnected(m.reason)
            is ErrorMessage -> android.util.Log.w("wallflower", "gateway: ${m.message}")
            else -> Unit
        }
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
        fun activePane(s: State): Pane? =
            s.windows.firstOrNull { it.active }?.panes?.firstOrNull { it.active }
    }
}
