package sh.ahmd.wallflower.session

import java.io.IOException
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import sh.ahmd.wallflower.protocol.Bye
import sh.ahmd.wallflower.protocol.ClientHello
import sh.ahmd.wallflower.protocol.ClientMessage
import sh.ahmd.wallflower.protocol.ErrorMessage
import sh.ahmd.wallflower.protocol.Frame
import sh.ahmd.wallflower.protocol.FrameReader
import sh.ahmd.wallflower.protocol.FrameWriter
import sh.ahmd.wallflower.protocol.Ping
import sh.ahmd.wallflower.protocol.Pong
import sh.ahmd.wallflower.protocol.ServerHello
import sh.ahmd.wallflower.protocol.ServerMessage
import sh.ahmd.wallflower.protocol.VERSION

sealed interface Status {
    data object Connecting : Status
    data object Connected : Status
    /**
     * Retrying on its own: [trying] now, or next at [retryAt]; down since
     * [since], its last try ended for [reason]. Times are the session's
     * clock's.
     */
    data class Disconnected(val reason: String, val since: Long, val retryAt: Long, val trying: Boolean = false) : Status
    /** Waiting for the user, then [Session.retry]. */
    data class NeedsUser(val blocked: Blocked) : Status
}

/**
 * The link to the gateway: one connection at a time, made again whenever it
 * drops, backing off, until [stop]. Messages go out in order on the live
 * connection; with none live, nothing goes. Pings check it's alive, and
 * mark what has arrived: a pong means everything sent before its ping
 * reached the multiplexer.
 *
 * What a connection keeps lives in one [Live], thrown away whole when it
 * ends. What the rest of the session keeps about a connection is reset in
 * [Events.linked], under [lock] with the new one not yet carrying anything,
 * so nothing sent on it is from the last.
 */
internal class Link(
    private val transport: Transport,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val events: Events,
) {
    interface Events {
        /** The first message, saying the phone's size. */
        fun hello(): ClientHello

        /** A connection is about to carry messages: forget what was kept of the last. Under [lock]. */
        fun linked()

        /** It does now: send what a new connection needs. */
        fun up()

        fun message(m: ServerMessage)

        fun data(pane: String, bytes: ByteArray, offset: Int, length: Int)

        fun picture(id: String, bytes: ByteArray, offset: Int, length: Int)

        /** It's gone. Under [lock]. */
        fun down()
    }

    private val _status = MutableStateFlow<Status>(Status.Connecting)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Guards which connection is live, and what the session keeps per connection. */
    val lock = Any()

    @Volatile private var live: Live? = null
    /** The connection being made or live, which [stop] closes: its reads don't heed cancellation. */
    @Volatile private var opened: Connection? = null
    /** The connection being made or live got as far as the gateway's hello. */
    @Volatile private var greeted = false
    private val pings = AtomicInteger()
    private var job: Job? = null
    private val retries = Channel<Unit>(Channel.CONFLATED)

    /** A connection is live, though the gateway may not have said hello yet. */
    val linked: Boolean get() = live != null

    /** Connected, and the gateway said hello. */
    val up: Boolean get() = live != null && _status.value is Status.Connected

    private sealed interface Outgoing {
        class Message(val message: ClientMessage) : Outgoing
        class Data(val pane: String, val bytes: ByteArray) : Outgoing
        class Upload(val id: String, val bytes: ByteArray, val length: Int) : Outgoing
    }

    /** One connection's worth: thrown away whole when it ends. */
    private class Live(val conn: Connection, val out: Channel<Outgoing>) {
        val ended = CompletableDeferred<Unit>()
        /** By ping: complete once its pong says everything sent before it arrived. */
        val arrivals = ConcurrentSkipListMap<Int, CompletableDeferred<Unit>>()
        @Volatile var lastPing = 0L
        /** When the connection counts as dead unless a pong arrives; 0 if no ping is outstanding. */
        @Volatile var deadline = 0L
    }

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            var backoff = 500L
            while (isActive) {
                _status.value = (_status.value as? Status.Disconnected)?.copy(trying = true) ?: Status.Connecting
                retries.tryReceive() // a retry asked for before this attempt is this attempt
                greeted = false
                val reason = try {
                    connect(this)
                    "connection closed"
                } catch (b: Blocked) {
                    _status.value = Status.NeedsUser(b)
                    retries.receive()
                    continue
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
                if (greeted) backoff = 500L // it worked a while; a gateway that won't start doesn't get hammered
                val now = clock()
                _status.value = Status.Disconnected(reason, (_status.value as? Status.Disconnected)?.since ?: now, now + backoff)
                withTimeoutOrNull(backoff) { retries.receive() }
                backoff = (backoff * 2).coerceAtMost(5_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        opened?.close()
    }

    /** Tries again now: after the user dealt with a [Status.NeedsUser], or while it waits to. */
    fun retry() {
        retries.trySend(Unit)
    }

    /** One connection, until it ends; what it starts ends with [within]. */
    private fun connect(within: CoroutineScope) {
        val conn = transport.open()
        conn.use {
            opened = conn
            if (!within.isActive) return // stopped while connecting
            val w = FrameWriter(conn.output)
            w.send(events.hello())
            w.flush()
            val l = Live(conn, Channel(Channel.UNLIMITED))
            val writer = within.launch(Dispatchers.IO) {
                try {
                    // Frames go out as they come, flushed once there are no more for now.
                    for (first in l.out) {
                        var o: Outgoing? = first
                        while (o != null) {
                            when (o) {
                                is Outgoing.Message -> w.send(o.message)
                                is Outgoing.Data -> w.sendData(o.pane, o.bytes)
                                is Outgoing.Upload -> w.sendUpload(o.id, o.bytes, o.length)
                            }
                            o = l.out.tryReceive().getOrNull()
                        }
                        w.flush()
                    }
                } catch (e: Exception) {
                    conn.close()
                }
            }
            l.lastPing = clock()
            synchronized(lock) {
                events.linked()
                live = l
            }
            events.up()
            val heartbeat = within.launch(Dispatchers.IO) { heartbeat(l) }
            try {
                val r = FrameReader(conn.input)
                while (true) {
                    when (val f = r.next() ?: return) {
                        is Frame.Data -> events.data(f.pane, f.bytes, f.offset, f.length)
                        is Frame.Picture -> events.picture(f.id, f.bytes, f.offset, f.length)
                        is Frame.Message -> handle(l, f.message)
                        is Frame.Unknown -> f.problem?.let { android.util.Log.w("wallflower", "unread message ($it): ${f.json.take(200)}") }
                    }
                }
            } finally {
                opened = null
                heartbeat.cancel()
                synchronized(lock) {
                    live = null
                    l.out.close()
                    events.down()
                }
                l.ended.complete(Unit)
                l.arrivals.clear()
                writer.cancel()
            }
        }
    }

    private fun handle(l: Live, m: ServerMessage) {
        when (m) {
            is ServerHello -> {
                if (m.v != VERSION) throw Blocked.Outdated(m.v, VERSION)
                greeted = true
                _status.value = Status.Connected
            }
            is Pong -> {
                // Any pong says the link is alive; only the newest leaves nothing outstanding. (Uploads keep pings in flight.)
                l.deadline = if (m.id == pings.get()) 0 else clock() + PONG_TIMEOUT_MS
                l.arrivals.headMap(m.id, true).apply {
                    values.forEach { it.complete(Unit) }
                    clear()
                }
            }
            is Bye -> throw if (greeted) IOException(m.reason) else refusal(m.reason)
            is ErrorMessage -> android.util.Log.w("wallflower", "gateway: ${m.message}")
            else -> Unit
        }
        events.message(m)
    }

    /** Why the gateway turned the connection down: a protocol version it doesn't speak says which it does. */
    private fun refusal(reason: String): Blocked =
        Regex("""speaks (\d+)""").find(reason)?.let { Blocked.Outdated(it.groupValues[1].toInt(), VERSION) } ?: Blocked.Refused(reason)

    /** Mobile connections die silently; an unanswered ping closes the socket so it reconnects. */
    private suspend fun heartbeat(l: Live) {
        while (true) {
            delay(TICK_MS)
            val now = clock() // counts time asleep, unlike nanoTime
            val d = l.deadline
            if (d != 0L && now > d) {
                l.conn.close()
                return
            }
            if (now - l.lastPing >= HEARTBEAT_MS) ping(PONG_TIMEOUT_MS)
        }
    }

    /**
     * Pings, so the link counts as dead if no pong comes within [timeoutMs];
     * [arrived] completes once one says everything sent before it arrived.
     * Returns its id, which pongs up to it are about. With [on], only on the
     * connection that [ended] says so of.
     */
    fun ping(timeoutMs: Long, arrived: CompletableDeferred<Unit>? = null, on: Deferred<Unit>? = null): Int {
        val l = live(on)
        val id = pings.incrementAndGet()
        if (l == null) return id
        val now = clock()
        l.lastPing = now
        val d = now + timeoutMs
        if (l.deadline == 0L || d < l.deadline) l.deadline = d
        if (arrived != null) l.arrivals[id] = arrived
        l.out.trySend(Outgoing.Message(Ping(id)))
        return id
    }

    /** Sends [m] on the live connection, or only the one [on] is [ended]'s; false if there's none. */
    fun send(m: ClientMessage, on: Deferred<Unit>? = null): Boolean = live(on)?.out?.trySend(Outgoing.Message(m))?.isSuccess == true

    /** Types [bytes] into [pane]. */
    fun sendData(pane: String, bytes: ByteArray): Boolean = live?.out?.trySend(Outgoing.Data(pane, bytes))?.isSuccess == true

    /** The next [length] of upload [id]'s bytes, on the connection [on] is [ended]'s. */
    fun sendUpload(id: String, bytes: ByteArray, length: Int, on: Deferred<Unit>): Boolean =
        live(on)?.out?.trySend(Outgoing.Upload(id, bytes, length))?.isSuccess == true

    /** Completes when the live connection ends; null if none is live. It stands for that connection. */
    fun ended(): Deferred<Unit>? = live?.ended

    private fun live(on: Deferred<Unit>?) = live?.takeIf { on == null || it.ended === on }

    companion object {
        const val TICK_MS = 1_000L
        const val HEARTBEAT_MS = 15_000L
        const val PONG_TIMEOUT_MS = 10_000L
    }
}
