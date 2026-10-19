package sh.ahmd.wallflower.session

import android.os.SystemClock
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sh.ahmd.wallflower.protocol.ClientHello
import sh.ahmd.wallflower.protocol.Entries
import sh.ahmd.wallflower.protocol.Pane
import sh.ahmd.wallflower.protocol.Pong
import sh.ahmd.wallflower.protocol.Quit
import sh.ahmd.wallflower.protocol.Reset
import sh.ahmd.wallflower.protocol.Resize
import sh.ahmd.wallflower.protocol.ServerHello
import sh.ahmd.wallflower.protocol.ServerMessage
import sh.ahmd.wallflower.protocol.State
import sh.ahmd.wallflower.protocol.Uploaded
import sh.ahmd.wallflower.protocol.Window
import sh.ahmd.wallflower.read.Reading
import sh.ahmd.wallflower.terminal.Terminal

/**
 * The phone's session with the Mac's tmux, over one [Link] at a time,
 * reconnecting until [stop]. tmux's state arrives pushed and is kept here
 * ([Workspace]), with the panes' terminals ([Terminals]) and read views
 * ([ReadViews]); presentation is local, and what the user does goes out as
 * intents ([Input], uploads).
 */
class Session(transport: Transport, private val scope: CoroutineScope, clock: () -> Long = SystemClock::elapsedRealtime) {
    private val link: Link = Link(transport, scope, clock, Events())
    private val panes = Terminals(link)
    private val workspace = Workspace(link, panes)
    private val reads = ReadViews(link)
    private val input = Input(link)
    private val transfer = Transfer(link)

    val status: StateFlow<Status> get() = link.status
    val state: StateFlow<State?> get() = workspace.state
    /** The window on screen; local, so it changes the moment a tab is tapped. */
    val viewing: StateFlow<String?> get() = workspace.viewing
    /** Panes picked on the phone, by window; local, so a swipe shows the pane at once. */
    val chosen: StateFlow<Map<String, PaneChoice>> get() = workspace.chosen
    /** Terminals of open panes, replaced on every reset. */
    val terminals: StateFlow<Map<String, Terminal>> get() = panes.terminals

    /** Panes whose shell marks its commands (OSC 133), so they can be shown apart. */
    val marked: StateFlow<Set<String>> get() = panes.marked
    /** Read views of the panes [read] asked for, as they arrive. */
    val readings: StateFlow<Map<String, Reading>> get() = reads.readings
    val undelivered: SharedFlow<Undelivered> get() = input.undelivered

    private val _uploadDir = MutableStateFlow<String?>(null)
    /** Where the gateway saves uploads: paths under it in what's sent are uploads, pasted on their own. */
    val uploadDir: StateFlow<String?> = _uploadDir.asStateFlow()

    @Volatile private var grid = 80 to 24
    private var started = false
    private var resizing: Job? = null

    private inner class Events : Link.Events {
        override fun hello() = ClientHello(cols = grid.first, rows = grid.second)

        override fun linked() {
            panes.linked()
            workspace.linked()
            reads.linked()
        }

        override fun up() = reads.up()

        override fun message(m: ServerMessage) {
            when (m) {
                is ServerHello -> _uploadDir.value = m.uploads
                is State -> workspace.state(m)
                is Reset -> panes.reset(m)
                is Entries -> reads.entries(m)
                is Pong -> input.arrived(m.id)
                is Uploaded -> transfer.uploaded(m)
                else -> Unit
            }
        }

        override fun data(pane: String, bytes: ByteArray, offset: Int, length: Int) = panes.write(pane, bytes, offset, length)

        override fun picture(id: String, bytes: ByteArray, offset: Int, length: Int) = transfer.picture(id, bytes, offset, length)

        override fun down() = input.down()
    }

    /** Connects, saying the phone's size; [resize] says when it changes. */
    fun start(cols: Int, rows: Int) {
        if (started) return
        started = true
        grid = cols to rows
        link.start()
    }

    fun stop() {
        started = false
        link.stop()
    }

    /** Tries again now, after the user dealt with a [Status.NeedsUser]. */
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

    /** Resizes once the size settles (the keyboard animates through every size between), or at once if it's known [ahead]. */
    fun resize(cols: Int, rows: Int, ahead: Boolean = false) {
        if (grid == cols to rows) return
        grid = cols to rows
        resizing?.cancel()
        resizing = scope.launch {
            if (!ahead) delay(RESIZE_SETTLE_MS)
            link.send(Resize(cols, rows))
        }
    }

    fun view(window: Window) = workspace.view(window)

    fun show(window: Window, pane: Pane) = workspace.show(window, pane)

    fun newWindow() = workspace.newWindow()

    fun split(pane: Pane) = workspace.split(pane.id)

    fun kill(window: Window) = workspace.kill(window)

    fun kill(pane: Pane) = workspace.kill(pane)

    fun rename(window: Window, name: String) = workspace.rename(window, name)

    /** Has the gateway send these panes' read views, and no others'. */
    fun read(panes: Set<String>) = reads.read(panes)

    /** [pane]'s conversation was rewound to before the user's message [said] matches. */
    fun rewound(pane: String, said: (String) -> Boolean) = reads.rewound(pane, said)

    fun keys(pane: String, keys: List<String>, bindings: Boolean = false) = input.keys(pane, keys, bindings)

    /** Ends what [pane]'s shell runs, even what won't take Ctrl-C. */
    fun quit(pane: String) = link.send(Quit(pane))

    fun bytes(pane: String, bytes: ByteArray) = input.bytes(pane, bytes)

    /** See [Input.submit]. */
    fun submit(pane: String, text: String, enter: Boolean = true, clear: List<String> = emptyList()) = input.submit(pane, text, enter, clear)

    /** A picture entry of [pane]'s read view, its bytes asked of the gateway; null if they're gone. */
    suspend fun picture(pane: String, id: String): ByteArray? = transfer.picture(pane, id)

    /** See [Transfer.upload]. */
    suspend fun upload(id: String, name: String, size: Long, input: InputStream, progress: (Long) -> Unit) =
        transfer.upload(id, name, size, input, progress)

    companion object {
        const val PROBE_TIMEOUT_MS = 5_000L
        const val RESIZE_SETTLE_MS = 120L
    }
}
