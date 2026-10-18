package sh.ahmd.wallflower.session

import android.os.SystemClock
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import sh.ahmd.wallflower.protocol.Bye
import sh.ahmd.wallflower.protocol.ClientHello
import sh.ahmd.wallflower.protocol.ClientMessage
import sh.ahmd.wallflower.protocol.Close
import sh.ahmd.wallflower.protocol.DEFAULT_HISTORY
import sh.ahmd.wallflower.protocol.Entries
import sh.ahmd.wallflower.protocol.ErrorMessage
import sh.ahmd.wallflower.protocol.Focus
import sh.ahmd.wallflower.protocol.Frame
import sh.ahmd.wallflower.protocol.FrameReader
import sh.ahmd.wallflower.protocol.FrameWriter
import sh.ahmd.wallflower.protocol.Keys
import sh.ahmd.wallflower.protocol.New
import sh.ahmd.wallflower.protocol.Open
import sh.ahmd.wallflower.protocol.Pane
import sh.ahmd.wallflower.protocol.Paste
import sh.ahmd.wallflower.protocol.Ping
import sh.ahmd.wallflower.protocol.Pong
import sh.ahmd.wallflower.protocol.Reset
import sh.ahmd.wallflower.protocol.Resize
import sh.ahmd.wallflower.protocol.Select
import sh.ahmd.wallflower.protocol.ServerHello
import sh.ahmd.wallflower.protocol.ServerMessage
import sh.ahmd.wallflower.protocol.State
import sh.ahmd.wallflower.protocol.Transcript
import sh.ahmd.wallflower.protocol.Upload
import sh.ahmd.wallflower.protocol.Uploaded
import sh.ahmd.wallflower.protocol.Window
import sh.ahmd.wallflower.terminal.Terminal

sealed interface Status {
    data object Connecting : Status
    data class Connected(val tmux: String) : Status
    /** Retrying on its own. */
    data class Disconnected(val reason: String) : Status
    /** Waiting for the user, then [Session.retry]. */
    data class NeedsUser(val blocked: Blocked) : Status
}

/** A pane shown instead of its window's active one, until tmux's active pane is no longer [active]. */
data class PaneChoice(val pane: String, val active: String?)

/** Composed text the gateway never confirmed, so it may not have reached tmux. */
data class Undelivered(val pane: String, val text: String)

/**
 * One connection to the gateway, reconnecting until [stop].
 *
 * tmux state arrives pushed and is kept here; presentation is local. The
 * window being viewed is the app's choice and changes instantly, the gateway
 * is told in the background. Every pane is kept open and streaming, so
 * switching to one, or seeing them all, needs no round trip: the panes on
 * screen (each window's shown pane) with their scrollback, the rest screen
 * only until they're looked at.
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

    /** Panes picked on the phone, by window; local, so a swipe shows the pane at once. */
    private val _chosen = MutableStateFlow<Map<String, PaneChoice>>(emptyMap())
    val chosen: StateFlow<Map<String, PaneChoice>> = _chosen.asStateFlow()

    /** Conversations of the panes [follow] asked for, as they arrive. */
    private val _conversations = MutableStateFlow<Map<String, Conversation>>(emptyMap())
    val conversations: StateFlow<Map<String, Conversation>> = _conversations.asStateFlow()

    private val _undelivered = MutableSharedFlow<Undelivered>(extraBufferCapacity = 16)
    val undelivered: SharedFlow<Undelivered> = _undelivered.asSharedFlow()

    /** Called on the IO thread after a pane's terminal changes. */
    @Volatile var onOutput: ((pane: String) -> Unit)? = null

    private val _uploadDir = MutableStateFlow<String?>(null)
    /** Where the gateway saves uploads: paths under it in what's sent are uploads, pasted on their own. */
    val uploadDir: StateFlow<String?> = _uploadDir.asStateFlow()

    /** The live connection's outgoing queue; one writer drains it, so messages go out in order. */
    @Volatile private var queue: Channel<Outgoing>? = null
    /** Completes when the live connection ends. */
    @Volatile private var lost: CompletableDeferred<Unit>? = null
    private val uploaded = ConcurrentHashMap<String, CompletableDeferred<Uploaded>>()
    /** By ping: complete once its pong says everything sent before it arrived. */
    private val arrivals = ConcurrentSkipListMap<Int, CompletableDeferred<Unit>>()
    @Volatile private var lastPing = 0L
    /** When the connection counts as dead unless a pong arrives; 0 if no ping is outstanding. */
    @Volatile private var deadline = 0L
    private val pending = ArrayList<Submitted>() // its lock also guards swapping the queue
    private val opened = LinkedHashMap<String, Int>() // pane -> history lines it has; least recently viewed first
    @Volatile private var awaitingWindow: Set<String>? = null // windows there were when the phone opened one
    @Volatile private var synced = false // this connection's session has the window being viewed current
    private val following = HashSet<String>() // panes this connection asked for conversations of; guarded by pending
    @Volatile private var wanted: Set<String> = emptySet()
    private val pings = AtomicInteger()
    private var grid = 80 to 24
    private var job: Job? = null
    private var resizing: Job? = null
    private val retries = Channel<Unit>(Channel.CONFLATED)

    private sealed interface Outgoing {
        class Message(val message: ClientMessage) : Outgoing
        class Data(val pane: String, val bytes: ByteArray) : Outgoing
        class Upload(val id: String, val bytes: ByteArray, val length: Int) : Outgoing
    }

    private class Submitted(val ping: Int, val pane: String, val text: String)

    fun start(cols: Int, rows: Int) {
        if (job != null) return
        grid = cols to rows
        job = scope.launch(Dispatchers.IO) {
            var backoff = 500L
            while (isActive) {
                _status.value = Status.Connecting
                retries.tryReceive() // a retry asked for before this attempt is this attempt
                val reason = try {
                    runOnce()
                    backoff = 500L
                    "connection closed"
                } catch (b: Blocked) {
                    _status.value = Status.NeedsUser(b)
                    retries.receive()
                    continue
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
                synchronized(opened) { opened.clear() }
                if (_status.value !is Status.Disconnected) _status.value = Status.Disconnected(reason)
                withTimeoutOrNull(backoff) { retries.receive() }
                backoff = (backoff * 2).coerceAtMost(5_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Tries again now, after the user dealt with a [Status.NeedsUser]. */
    fun retry() {
        retries.trySend(Unit)
    }

    /**
     * The app is back in front: a connection that went quiet in the
     * background (frozen, asleep, network changed) is checked now instead of
     * at the next heartbeat, a waiting reconnect goes right away, and the
     * window on screen is the phone's size again.
     */
    fun probe() {
        when {
            queue != null -> {
                ping(PROBE_TIMEOUT_MS)
                // The desktop may have taken the window over meanwhile; showing it again takes it back.
                _viewing.value?.let { send(Select(window = it)) }
            }
            _status.value is Status.Disconnected -> retries.trySend(Unit)
        }
    }

    private fun runOnce() {
        val conn = transport.open()
        conn.use {
            val w = FrameWriter(conn.output)
            w.send(ClientHello(cols = grid.first, rows = grid.second))
            val out = Channel<Outgoing>(Channel.UNLIMITED)
            val writer = scope.launch(Dispatchers.IO) {
                try {
                    for (o in out) when (o) {
                        is Outgoing.Message -> w.send(o.message)
                        is Outgoing.Data -> w.sendData(o.pane, o.bytes)
                        is Outgoing.Upload -> w.sendUpload(o.id, o.bytes, o.length)
                    }
                } catch (e: Exception) {
                    conn.close()
                }
            }
            lastPing = SystemClock.elapsedRealtime()
            deadline = 0
            synced = false
            val ended = CompletableDeferred<Unit>()
            synchronized(pending) {
                queue = out
                lost = ended
                following.clear()
            }
            follow(wanted)
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
                val dropped = synchronized(pending) {
                    queue = null
                    lost = null
                    out.close()
                    pending.toList().also { pending.clear() }
                }
                ended.complete(Unit)
                arrivals.clear()
                writer.cancel()
                dropped.forEach { _undelivered.tryEmit(Undelivered(it.pane, it.text)) }
            }
        }
    }

    /** Mobile connections die silently; an unanswered ping closes the socket so we reconnect. */
    private suspend fun heartbeat(conn: Connection) {
        while (true) {
            delay(TICK_MS)
            val now = SystemClock.elapsedRealtime() // counts time asleep, unlike nanoTime
            val d = deadline
            if (d != 0L && now > d) {
                conn.close()
                return
            }
            if (now - lastPing >= HEARTBEAT_MS) ping(PONG_TIMEOUT_MS)
        }
    }

    private fun ping(timeoutMs: Long, arrived: CompletableDeferred<Unit>? = null): Int {
        val id = pings.incrementAndGet()
        val now = SystemClock.elapsedRealtime()
        lastPing = now
        val d = now + timeoutMs
        if (deadline == 0L || d < deadline) deadline = d
        if (arrived != null) arrivals[id] = arrived
        queue?.trySend(Outgoing.Message(Ping(id)))
        return id
    }

    private fun handle(m: ServerMessage) {
        when (m) {
            is ServerHello -> {
                _uploadDir.value = m.uploads
                _status.value = Status.Connected(m.tmux)
            }
            is Uploaded -> uploaded[m.id]?.complete(m)
            is State -> {
                _state.value = m
                // A pane picked on the phone gives way once tmux's active pane moves on (to it, or elsewhere).
                val chosen = _chosen.value.filter { (w, c) -> m.windows.firstOrNull { it.id == w }?.activePane()?.id == c.active }
                if (chosen.size != _chosen.value.size) _chosen.value = chosen
                if (m.windows.none { it.id == _viewing.value }) _viewing.value = m.windows.firstOrNull { it.active }?.id
                awaitingWindow?.let { known ->
                    m.windows.firstOrNull { it.id !in known }?.let { w ->
                        _viewing.value = w.id
                        awaitingWindow = null
                    }
                }
                // A new connection's session starts wherever the desktop is; what's shown is the phone's choice.
                if (!synced) {
                    synced = true
                    if (m.windows.none { it.id == _viewing.value && it.active }) _viewing.value?.let { send(Select(window = it)) }
                }
                m.windows.firstOrNull { it.id == _viewing.value }?.shownPane(_chosen.value)?.let { open(it.id, viewed = true) }
                for (w in m.windows) w.shownPane(_chosen.value)?.let { open(it.id, viewed = false, full = true) }
                for (w in m.windows) for (p in w.panes.sortedBy { it.index }) open(p.id, viewed = false)
                // Panes that no longer exist close quietly; the gateway forgot them already.
                val live = m.windows.flatMap { w -> w.panes.map { it.id } }.toSet()
                synchronized(opened) { opened.keys.retainAll(live) }
                val gone = _terminals.value.filterKeys { it !in live }
                if (gone.isNotEmpty()) {
                    _terminals.value = _terminals.value - gone.keys
                    gone.values.forEach { it.close() }
                }
            }
            is Entries -> {
                if (m.pane in wanted) _conversations.update { it + (m.pane to (it[m.pane] ?: Conversation()).apply(m)) }
            }
            is Reset -> {
                val term = Terminal(m.cols, m.rows)
                val old = _terminals.value[m.pane]
                _terminals.value = _terminals.value + (m.pane to term)
                old?.close()
            }
            is Pong -> {
                if (m.id == pings.get()) deadline = 0
                arrivals.headMap(m.id, true).apply {
                    values.forEach { it.complete(Unit) }
                    clear()
                }
                // Messages are handled in order: everything sent before this ping arrived.
                synchronized(pending) { pending.removeAll { it.ping <= m.id } }
            }
            is Bye -> _status.value = Status.Disconnected(m.reason)
            is ErrorMessage -> android.util.Log.w("wallflower", "gateway: ${m.message}")
            else -> Unit
        }
    }

    /**
     * Opens a pane, or reopens it with its scrollback if it only had its
     * screen. [full] panes get scrollback; the others only open while there
     * are fewer than [MAX_OPEN], and the least recently viewed go first.
     */
    private fun open(pane: String, viewed: Boolean, full: Boolean = viewed) {
        val history = if (full) DEFAULT_HISTORY else PREVIEW_HISTORY
        val evict = synchronized(opened) {
            val had = opened[pane]
            if (had == null && !full && opened.size >= MAX_OPEN) return
            if (viewed || had == null) opened.remove(pane)
            opened[pane] = maxOf(had ?: 0, history)
            if (had == null || had < history) send(Open(pane, history.takeIf { it != DEFAULT_HISTORY }))
            if (opened.size > MAX_OPEN) opened.keys.first().also { opened.remove(it) } else null
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

    /** Shows a pane of a window now, and has tmux focus it in the background, zoomed so it gets the whole screen. */
    fun show(window: Window, pane: Pane) {
        _chosen.value = _chosen.value + (window.id to PaneChoice(pane.id, window.activePane()?.id))
        open(pane.id, viewed = true)
        send(Focus(pane.id))
    }

    /** Opens a window, which the phone then shows; the desktop stays where it is. */
    fun newWindow() {
        awaitingWindow = _state.value?.windows?.map { it.id }?.toSet().orEmpty()
        send(New)
    }

    /** Has the gateway send these panes' conversations, and no others'. */
    fun follow(panes: Set<String>) {
        wanted = panes
        synchronized(pending) {
            queue?.let { q ->
                for (p in following - panes) q.trySend(Outgoing.Message(Transcript(p, on = false)))
                for (p in panes - following) q.trySend(Outgoing.Message(Transcript(p, on = true)))
                following.clear()
                following += panes
            }
        }
        _conversations.update { c -> if (c.keys.all { it in panes }) c else c.filterKeys { it in panes } }
    }

    fun send(m: ClientMessage) {
        queue?.trySend(Outgoing.Message(m))
    }

    fun type(pane: String, text: String) = input(pane, text.encodeToByteArray())

    fun input(pane: String, bytes: ByteArray) {
        queue?.trySend(Outgoing.Data(pane, bytes))
    }

    /**
     * Sends composed text and presses Enter, unless not [enter]: typed if
     * it's one line, pasted if it has several, so the app doesn't run each
     * line, after [clear] keys (whatever empties the app's line first).
     * Returns false, sending nothing, while not connected. Text the gateway
     * doesn't confirm before the connection drops comes back through
     * [undelivered].
     */
    fun submit(pane: String, text: String, enter: Boolean = true, clear: List<String> = emptyList()): Boolean = synchronized(pending) {
        val q = queue
        if (q == null || _status.value !is Status.Connected) return false
        if (clear.isNotEmpty()) q.trySend(Outgoing.Message(Keys(pane, clear)))
        val uploads = _uploadDir.value?.let { uploadPaths(it) }
        val sent = if (uploads != null && uploads.containsMatchIn(text)) {
            // Each upload is pasted on its own, as a file dropped on a terminal, so the app can take it in as one.
            split(text, uploads).all { (part, upload) ->
                q.trySend(if (upload || '\n' in part) Outgoing.Message(Paste(pane, part)) else Outgoing.Data(pane, part.encodeToByteArray())).isSuccess
            } && (!enter || q.trySend(Outgoing.Message(Keys(pane, listOf("Enter")))).isSuccess)
        } else if ('\n' in text) {
            q.trySend(Outgoing.Message(Paste(pane, text))).isSuccess &&
                (!enter || q.trySend(Outgoing.Message(Keys(pane, listOf("Enter")))).isSuccess)
        } else {
            q.trySend(Outgoing.Data(pane, (if (enter) text + "\r" else text).encodeToByteArray())).isSuccess
        }
        if (sent) pending += Submitted(ping(PONG_TIMEOUT_MS), pane, text)
        sent
    }

    /**
     * Sends [size] bytes from [input] to the Mac as upload [id] and returns
     * where the gateway saved it. It goes a chunk at a time, each followed by
     * a ping, with only [UPLOAD_AHEAD] chunks unanswered: whatever's typed
     * meanwhile waits behind those alone rather than behind the whole file
     * in the network's buffers, and [progress] hears what has truly arrived.
     * Throws if the connection drops or the gateway won't save it.
     */
    suspend fun upload(id: String, name: String, size: Long, input: InputStream, progress: (Long) -> Unit): String {
        val (q, ended) = synchronized(pending) { (queue ?: throw IOException("not connected")) to lost!! }
        val reply = CompletableDeferred<Uploaded>()
        uploaded[id] = reply
        try {
            q.send(Outgoing.Message(Upload(id, name, size)))
            var sent = 0L
            val ahead = ArrayDeque<Pair<Long, CompletableDeferred<Unit>>>() // bytes sent through a chunk, and its arrival
            suspend fun settle(down: Int) {
                while (ahead.size > down) {
                    val (through, arrived) = ahead.removeFirst()
                    arrived.unlessLost(ended)
                    progress(through)
                }
            }
            while (sent < size) {
                val chunk = ByteArray(UPLOAD_CHUNK)
                val n = input.read(chunk, 0, minOf(UPLOAD_CHUNK.toLong(), size - sent).toInt())
                if (n < 0) throw IOException("$name ended early")
                q.send(Outgoing.Upload(id, chunk, n))
                sent += n
                ahead.addLast(sent to CompletableDeferred<Unit>().also { ping(PONG_TIMEOUT_MS, it) })
                settle(UPLOAD_AHEAD)
            }
            settle(0)
            val r = reply.unlessLost(ended)
            return r.path ?: throw IOException(r.error ?: "not saved")
        } catch (_: ClosedSendChannelException) {
            throw IOException("the connection dropped")
        } finally {
            uploaded.remove(id)
        }
    }

    private suspend fun <T> Deferred<T>.unlessLost(ended: Deferred<Unit>): T = select {
        onAwait { it }
        ended.onAwait { throw IOException("the connection dropped") }
    }

    /** Tells tmux the size once it settles: the keyboard animates the view through every size in between. */
    /** Resizes once the size settles, or at once if it's known [ahead] (the keyboard says where it'll stop). */
    fun resize(cols: Int, rows: Int, ahead: Boolean = false) {
        if (grid == cols to rows) return
        grid = cols to rows
        resizing?.cancel()
        resizing = scope.launch {
            if (!ahead) delay(RESIZE_SETTLE_MS)
            send(Resize(cols, rows))
        }
    }

    companion object {
        const val MAX_OPEN = 64
        const val PREVIEW_HISTORY = 1
        const val TICK_MS = 1_000L
        const val HEARTBEAT_MS = 15_000L
        const val PONG_TIMEOUT_MS = 10_000L
        const val PROBE_TIMEOUT_MS = 5_000L
        const val RESIZE_SETTLE_MS = 120L
        const val UPLOAD_CHUNK = 32 * 1024
        const val UPLOAD_AHEAD = 8

        /** Upload paths: under [dir], names of the characters the gateway keeps, which never end in . or -. */
        fun uploadPaths(dir: String) = Regex(Regex.escape(dir) + "/[A-Za-z0-9._-]*[A-Za-z0-9_]")

        /** [text] as runs, each an upload's path or not. */
        fun split(text: String, uploads: Regex): List<Pair<String, Boolean>> {
            val parts = ArrayList<Pair<String, Boolean>>()
            var at = 0
            for (m in uploads.findAll(text)) {
                if (m.range.first > at) parts += text.substring(at, m.range.first) to false
                parts += m.value to true
                at = m.range.last + 1
            }
            if (at < text.length) parts += text.substring(at) to false
            return parts
        }

        fun Window.activePane(): Pane? = panes.firstOrNull { it.active }

        /** The pane the phone shows for a window: one picked on the phone, until tmux moves on. */
        fun Window.shownPane(chosen: Map<String, PaneChoice>): Pane? {
            val active = activePane()
            val choice = chosen[id]?.takeIf { it.active == active?.id }
            return choice?.let { c -> panes.firstOrNull { it.id == c.pane } } ?: active
        }
    }
}
