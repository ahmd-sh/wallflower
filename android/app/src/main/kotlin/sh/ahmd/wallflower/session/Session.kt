package sh.ahmd.wallflower.session

import android.os.SystemClock
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sh.ahmd.wallflower.program.Command
import sh.ahmd.wallflower.program.KeyDefaults
import sh.ahmd.wallflower.program.Programs
import sh.ahmd.wallflower.program.Shortcut
import sh.ahmd.wallflower.program.Supplied
import sh.ahmd.wallflower.protocol.Attached
import sh.ahmd.wallflower.protocol.Away
import sh.ahmd.wallflower.protocol.ClientHello
import sh.ahmd.wallflower.protocol.ClientMessage
import sh.ahmd.wallflower.protocol.Detach
import sh.ahmd.wallflower.protocol.Detached
import sh.ahmd.wallflower.protocol.Entries
import sh.ahmd.wallflower.protocol.FetchedFile
import sh.ahmd.wallflower.protocol.Listing
import sh.ahmd.wallflower.protocol.KillSession
import sh.ahmd.wallflower.protocol.NewSession
import sh.ahmd.wallflower.protocol.RenameSession
import sh.ahmd.wallflower.protocol.SessionList
import sh.ahmd.wallflower.protocol.Sessions
import sh.ahmd.wallflower.protocol.Switch
import sh.ahmd.wallflower.protocol.Notification
import sh.ahmd.wallflower.protocol.Pane
import sh.ahmd.wallflower.protocol.Pong
import sh.ahmd.wallflower.protocol.ProgramData
import sh.ahmd.wallflower.protocol.Reset
import sh.ahmd.wallflower.protocol.Resize
import sh.ahmd.wallflower.protocol.Mux
import sh.ahmd.wallflower.protocol.ServerHello
import sh.ahmd.wallflower.protocol.ServerMessage
import sh.ahmd.wallflower.protocol.State
import sh.ahmd.wallflower.protocol.Uploaded
import sh.ahmd.wallflower.protocol.Window
import sh.ahmd.wallflower.read.Reading
import sh.ahmd.wallflower.screen.Screen
import sh.ahmd.wallflower.terminal.Terminal

/** Where the phone is: a session ([session], its id) in a multiplexer ([mux], by name). */
data class Place(val mux: String, val session: String)

/**
 * The phone's connection to the computer's gateway, over one [Link] at a time,
 * reconnecting until [stop], and the session it's in ([place]), in any of
 * the computer's multiplexers, or none: then it's on the computer's sessions
 * ([sessions]), from which it goes into one ([switchTo], [newSession]). It
 * leaves one only when the user does ([detach]), or the session goes: a
 * new connection, after a network change or with the app back, goes back
 * into the one it was in. Its state arrives pushed and is kept here
 * ([Workspace]), with the panes' terminals ([Terminals]) and mobile views
 * ([MobileViews]); presentation is local, and what the user does goes out as
 * intents ([Input], uploads). While the user is [away], it stays connected
 * for what the computer's programs notify, and otherwise out of the way.
 */
class Session(
    transport: Transport,
    private val scope: CoroutineScope,
    /** The session the phone was last in, and the multiplexer it's in, by name; none if it was in none. */
    mux: String? = null,
    session: String? = null,
    clock: () -> Long = SystemClock::elapsedRealtime,
    /** What rewinds took back, kept past the app's life. */
    hidden: Hidden = Hidden.None,
    /** The terminal's size, in cells, as last measured: what a connection says until it's measured again. */
    grid: Pair<Int, Int> = 80 to 24,
) {
    private val link: Link = Link(transport, scope, clock, Events())
    private val panes = Terminals(link)
    private val workspace = Workspace(link, panes)
    private val reads = MobileViews(link, clock, hidden)
    private val input = Input(link)
    private val transfer = Transfer(link)
    private val files = Files(link)

    val status: StateFlow<Status> get() = link.status
    val state: StateFlow<State?> get() = workspace.state
    /** The window on screen; local, so it changes the moment a tab is tapped. */
    val viewing: StateFlow<String?> get() = workspace.viewing
    /** Panes picked on the phone, by window; local, so a swipe shows the pane at once. */
    val chosen: StateFlow<Map<String, PaneChoice>> get() = workspace.chosen
    /** Terminals of open panes, replaced on every reset. */
    val terminals: StateFlow<Map<String, Terminal>> get() = panes.terminals
    /** Mobile views of the panes [read] asked for, as they arrive. */
    val readings: StateFlow<Map<String, Reading>> get() = reads.readings
    val undelivered: SharedFlow<Undelivered> get() = input.undelivered

    private val _notifications = MutableSharedFlow<Notification>(extraBufferCapacity = 16)
    /** Desktop notifications the computer's programs send, from any pane. */
    val notifications: SharedFlow<Notification> = _notifications

    private val _mux = MutableStateFlow<Mux?>(null)
    /** The multiplexer the session is in, as the gateway last said; null while it's in none. */
    val mux: StateFlow<Mux?> = _mux.asStateFlow()

    private val _place = MutableStateFlow(if (mux != null && session != null) Place(mux, session) else null)
    /**
     * The session the phone is in, or going into; null while it's in none.
     * What the user picks is where it is at once; what the gateway says it's
     * attached to moves it only once the gateway has caught up with what the
     * user asked.
     */
    val place: StateFlow<Place?> = _place.asStateFlow()

    private val _back = MutableStateFlow(_place.value)
    /**
     * The session a new connection goes back into: the one the phone's in,
     * or was in when it couldn't be had (its multiplexer restarting, say);
     * none once the user leaves for none.
     */
    val back: StateFlow<Place?> = _back.asStateFlow()

    private val _starting = MutableStateFlow<String?>(null)
    /** The multiplexer a session's being started in, by name, until the phone's in it. */
    val starting: StateFlow<String?> = _starting.asStateFlow()

    private val _left = MutableStateFlow<String?>(null)
    /** Why the phone's in no session, when it didn't choose that: the session or its multiplexer went, or one it asked for couldn't be had. */
    val left: StateFlow<String?> = _left.asStateFlow()

    private val _sessions = MutableStateFlow<SessionList?>(null)
    /** The sessions in every multiplexer on the computer, as last listed: pushed while the phone's in none, or asked for ([listSessions]). */
    val sessions: StateFlow<SessionList?> = _sessions.asStateFlow()

    /** Where the gateway has the phone: the session its last state was of, null while attached to nothing, unknown until it says. */
    private sealed interface Attachment {
        data object Unknown : Attachment
        data object Nowhere : Attachment
        data class At(val place: Place) : Attachment
    }
    /** Guards [attachment], [asked] and where the phone is, between the link's thread and the user's. Taken after the link's lock, never before. */
    private val placing = Any()
    private var attachment: Attachment = Attachment.Unknown
    /** The user's latest ask of where to be, until the gateway's caught up with it. */
    private var asked: CompletableDeferred<Unit>? = null
    /** This connection's had the phone in a session, or in none: another means leaving that one. */
    @Volatile private var placed = false

    private val _machine = MutableStateFlow<String?>(null)
    /** The computer's name, as the gateway last said. */
    val machine: StateFlow<String?> = _machine.asStateFlow()

    private val _canFiles = MutableStateFlow(false)
    /** The gateway shows the computer's files ([list], [fetch]), as it last said. */
    val canFiles: StateFlow<Boolean> = _canFiles.asStateFlow()

    private val _uploadDir = MutableStateFlow<String?>(null)
    /** Where the gateway saves uploads: paths under it in what's sent are uploads, pasted on their own. */
    val uploadDir: StateFlow<String?> = _uploadDir.asStateFlow()

    @Volatile private var grid = grid
    private val _size = MutableStateFlow(grid)
    /** The terminal's size, in cells, as the gateway was last told it. */
    val size: StateFlow<Pair<Int, Int>> = _size.asStateFlow()
    private var started = false
    private var resizing: Job? = null
    @Volatile private var away = false
    private var quieting: Job? = null

    private inner class Events : Link.Events {
        override fun hello() = _back.value.let { ClientHello(cols = grid.first, rows = grid.second, mux = it?.mux, session = it?.session, away = away) }

        override fun linked() {
            panes.linked()
            workspace.linked()
            reads.linked()
            placed = false
            synchronized(placing) {
                attachment = Attachment.Unknown
                asked?.cancel() // the new connection's hello asks for where the user means to be
                asked = null
                _starting.value = null
            }
        }

        override fun up() = reads.up()

        override fun message(m: ServerMessage) {
            when (m) {
                is ServerHello -> {
                    _uploadDir.value = m.uploads
                    _canFiles.value = ServerHello.FILES in m.can
                    m.machine?.let { _machine.value = it }
                    Programs.supply(m.programs.associate { it.id to it.program() })
                }
                is Attached -> {
                    leave()
                    _mux.value = m.mux
                    synchronized(placing) {
                        attachment = Attachment.Unknown // until its state says which session
                        _starting.value = null
                    }
                }
                is Detached -> {
                    leave()
                    _mux.value = null
                    workspace.clear()
                    reads.clear()
                    // The session went under what was sent to it: what it didn't confirm comes back.
                    if (m.reason != null) synchronized(link.lock) { input.down() }
                    synchronized(placing) {
                        attachment = Attachment.Nowhere
                        if (m.reason != null) _left.value = m.reason
                        settle()
                    }
                }
                is State -> {
                    workspace.state(m)
                    synchronized(placing) {
                        _mux.value?.let { attachment = Attachment.At(Place(it.name, m.session)) }
                        settle()
                    }
                }
                is SessionList -> _sessions.value = m
                is Reset -> panes.reset(m)
                is Entries -> reads.entries(m)
                is Pong -> input.arrived(m.id)
                is Uploaded -> transfer.uploaded(m)
                is Listing -> files.listing(m)
                is FetchedFile -> files.fetched(m)
                is Notification -> _notifications.tryEmit(m)
                else -> Unit
            }
        }

        override fun data(pane: String, bytes: ByteArray, offset: Int, length: Int) = panes.write(pane, bytes, offset, length)

        override fun picture(id: String, bytes: ByteArray, offset: Int, length: Int) = transfer.picture(id, bytes, offset, length)

        override fun file(id: String, bytes: ByteArray, offset: Int, length: Int) = files.file(id, bytes, offset, length)

        override fun down() = input.down()
    }

    /** Connects, saying the phone's size; [resize] says when it changes. */
    fun start(cols: Int, rows: Int) {
        if (started) return
        started = true
        grid = cols to rows
        _size.value = grid
        link.start()
    }

    /** Connects at the size last measured, before the terminal's on screen to measure it. */
    fun start() = start(grid.first, grid.second)

    fun stop() {
        started = false
        link.stop()
    }

    /** Tries again now: after the user dealt with a [Status.NeedsUser], or rather than wait for the next try. */
    fun retry() = link.retry()

    /**
     * The app is back in front: a connection that went quiet in the
     * background (frozen, asleep, network changed) is checked now instead of
     * at the next heartbeat, a waiting reconnect goes right away, and the
     * window on screen is the phone's size again.
     */
    fun probe() {
        when {
            link.linked -> {
                link.ping(PROBE_TIMEOUT_MS)
                workspace.reclaim()
            }
            status.value is Status.Disconnected -> link.retry()
        }
    }

    /**
     * The user left the app ([on]) or came back. Away, the phone stays out
     * of sizing the computer's windows at once, and after [QUIET_AFTER_MS] stops
     * its panes and mobile views streaming, keeping state and notifications
     * coming. Back, they stream again, and the phone takes the window it shows.
     */
    fun away(on: Boolean) {
        if (away == on) return
        away = on
        workspace.away = on
        link.send(Away(on))
        quieting?.cancel()
        if (on) {
            quieting = scope.launch {
                delay(QUIET_AFTER_MS)
                quiet(true)
            }
        } else {
            quiet(false)
            probe()
        }
    }

    private fun quiet(on: Boolean) {
        panes.quiet(on)
        reads.quiet(on)
        if (!on) workspace.reopen()
    }

    /** [pane]'s screen as it settles, streamed for the while if it isn't; see [Terminals.peek]. */
    suspend fun peek(pane: String): Screen? = panes.peek(pane)

    /** The pane the phone shows, if any. */
    fun shownPane(): String? {
        val window = state.value?.windows?.firstOrNull { it.id == viewing.value } ?: return null
        return window.shownPane(chosen.value)?.id
    }

    /** Resizes once the size settles (the keyboard animates through every size between), or at once if it's known [ahead]. */
    fun resize(cols: Int, rows: Int, ahead: Boolean = false) {
        if (grid == cols to rows) return
        grid = cols to rows
        resizing?.cancel()
        resizing = scope.launch {
            if (!ahead) delay(RESIZE_SETTLE_MS)
            link.send(Resize(cols, rows))
            _size.value = cols to rows
        }
    }

    fun view(window: Window) = workspace.view(window)

    fun show(window: Window, pane: Pane) = workspace.show(window, pane)

    fun newWindow() = workspace.newWindow()

    fun split(pane: Pane) = workspace.split(pane.id)

    fun kill(window: Window) = workspace.kill(window)

    fun kill(pane: Pane) = workspace.kill(pane)

    fun rename(window: Window, name: String) = workspace.rename(window, name)

    /** Asks for the sessions in every multiplexer, which come in [sessions]. */
    fun listSessions() = link.send(Sessions)

    /** Goes into a session, over this link: the phone's there at once, its state once the gateway's attached. */
    fun switchTo(mux: String, session: String) = ask(Switch(mux, session)) {
        _place.value = Place(mux, session)
        _back.value = _place.value
        _left.value = null
    }

    /** Starts a session, named [name] or by the multiplexer, and goes into it once it's there ([starting] meanwhile). */
    fun newSession(mux: String, name: String?) = ask(NewSession(mux, name?.trim()?.takeIf { it.isNotEmpty() })) {
        _starting.value = mux
        _left.value = null
    }

    /** Leaves the session for none, as the user chose: nothing of it stays, and a new connection doesn't go back into it. */
    fun detach() {
        ask(Detach) {
            _place.value = null
            _back.value = null
            _left.value = null
        }
        workspace.clear()
        reads.clear()
    }

    /**
     * Does what the user asked of where to be ([change], at once), and
     * sends it, then a ping: its pong says the gateway's done it, and said
     * where that left the phone. Not connected, nothing's sent: a new
     * connection's hello asks for where they mean to be.
     */
    private fun ask(m: ClientMessage, change: () -> Unit) {
        val done = CompletableDeferred<Unit>()
        synchronized(placing) {
            asked?.cancel()
            asked = done
            change()
        }
        val on = link.ended()
        if (on == null || !link.send(m, on)) {
            synchronized(placing) {
                if (asked === done) asked = null
                _starting.value = null
            }
            done.cancel()
            return
        }
        link.ping(PROBE_TIMEOUT_MS, done, on)
        scope.launch {
            try {
                done.await()
            } catch (_: Exception) {
                return@launch
            }
            synchronized(placing) {
                if (asked !== done) return@launch
                asked = null
                _starting.value = null // there, or it couldn't be
                settle()
            }
        }
    }

    /**
     * With nothing the user asked still on its way, the phone's where the
     * gateway has it; a session that couldn't be had is still the one to go
     * back into. Under [placing].
     */
    private fun settle() {
        if (asked != null) return
        when (val a = attachment) {
            Attachment.Unknown -> Unit
            Attachment.Nowhere -> _place.value = null
            is Attachment.At -> {
                _place.value = a.place
                _back.value = a.place
            }
        }
    }

    /** What was kept of the session the phone was in goes, as for a new connection, if this one's had it in one. */
    private fun leave() {
        if (placed) synchronized(link.lock) {
            panes.linked()
            workspace.linked()
            reads.linked()
        }
        placed = true
    }

    fun renameSession(mux: String, session: String, name: String) = link.send(RenameSession(mux, session, name))

    /** Ends a session and its programs; not the one the phone is in. */
    fun killSession(mux: String, session: String) = link.send(KillSession(mux, session))

    /** Has the gateway send these panes' mobile views, and no others'. */
    fun read(panes: Set<String>) = reads.read(panes)

    /** [pane]'s conversation was rewound to before the user's message [said] matches. */
    fun rewound(pane: String, said: (String) -> Boolean, held: String? = null) = reads.rewound(pane, said, held)

    /** The user stopped [pane]'s program. */
    fun stopped(pane: String) = reads.stopped(pane)

    /** [pane]'s input line holds [line], which may be the user's latest message, taken back by a stop. */
    fun takenBack(pane: String, line: String?) = reads.takenBack(pane, line)

    fun keys(pane: String, keys: List<String>, bindings: Boolean = false) = input.keys(pane, keys, bindings)

    fun bytes(pane: String, bytes: ByteArray) = input.bytes(pane, bytes)

    /** See [Input.submit]. */
    fun submit(pane: String, text: String, enter: Boolean = true, clear: List<String> = emptyList()) = input.submit(pane, text, enter, clear)

    /** A picture entry of [pane]'s mobile view, its bytes asked of the gateway; null if they're gone. */
    suspend fun picture(pane: String, id: String): ByteArray? = transfer.picture(pane, id)

    /** A directory on the computer, its entries or why it couldn't be read; see [Files.list]. */
    suspend fun list(path: String): Listing = files.list(path)

    /** A file on the computer, fetched into [to]; see [Files.fetch]. */
    suspend fun fetch(path: String, to: File, limit: Long? = null, progress: (Long, Long) -> Unit = { _, _ -> }): FetchedFile =
        files.fetch(path, to, limit, progress)

    /** See [Transfer.upload]. */
    suspend fun upload(id: String, name: String, size: Long, input: InputStream, progress: (Long) -> Unit) =
        transfer.upload(id, name, size, input, progress)

    companion object {
        const val PROBE_TIMEOUT_MS = 5_000L
        const val RESIZE_SETTLE_MS = 120L
        const val QUIET_AFTER_MS = 60_000L
    }
}

/** A plugin's phone half, as the gateway describes it. */
private fun ProgramData.program() = Supplied(
    keyBar = keys?.let { KeyDefaults(it.name, it.keys, it.stop ?: listOf("C-c")) },
    commands = commands.map { Command(it.text, it.about) },
    newlineKeys = newline,
    clearKeys = clear.orEmpty(),
    shortcuts = shortcuts.map { Shortcut(it.keys, it.label) },
)
