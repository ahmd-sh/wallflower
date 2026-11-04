package sh.ahmd.wallflower

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.ahmd.wallflower.notify.ConnectionService
import sh.ahmd.wallflower.notify.Notices
import sh.ahmd.wallflower.program.Program
import sh.ahmd.wallflower.program.Programs
import sh.ahmd.wallflower.protocol.Notification
import sh.ahmd.wallflower.session.HardwareKey
import sh.ahmd.wallflower.session.Host
import sh.ahmd.wallflower.session.HostKeys
import sh.ahmd.wallflower.session.Session
import sh.ahmd.wallflower.session.SshTransport
import sh.ahmd.wallflower.session.Status
import sh.ahmd.wallflower.session.TcpTransport

/**
 * The session with the Mac, which outlives the app's screen: while the user
 * is away, [ConnectionService] keeps it, and what the Mac's programs notify
 * shows in the phone's notifications ([Notices]) as the pane's [Program]
 * has it said. It ends when the user disconnects.
 */
class Connection(private val context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("wallflower", Context.MODE_PRIVATE)
    val key = HardwareKey()
    val hostKeys = HostKeys(prefs)
    val notices = Notices(context)

    private val _host = MutableStateFlow(Host.load(prefs))
    val host: StateFlow<Host?> = _host.asStateFlow()

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** The user isn't looking at the app. */
    @Volatile var away = true
        private set
    private var watching: Job? = null

    /** By pane, the latest notification, until it's shown or the pane's seen. */
    private val latest = ConcurrentHashMap<String, Notification>()

    fun configure(host: Host) {
        host.save(prefs)
        _host.value = host
        connect(host)
    }

    /** Connects to the configured Mac, unless already connected. */
    fun ensure() {
        if (_session.value == null) _host.value?.let(::connect)
    }

    private fun connect(host: Host) {
        _session.value?.stop()
        watching?.cancel()
        val transport = if (host.dev) TcpTransport() else SshTransport(host, key, hostKeys)
        val session = Session(transport, scope, host.mux)
        session.away(away)
        watching = scope.launch {
            launch {
                session.notifications.collect { n ->
                    if (away || n.pane != session.shownPane()) {
                        latest[n.pane] = n
                        launch { notify(session, n) }
                    }
                }
            }
            session.status.collect { s ->
                when {
                    s is Status.Connected -> notices.lost(null)
                    s is Status.NeedsUser && away -> {
                        // It can't get back on its own: the user has to, from the app.
                        notices.lost(s)
                        ConnectionService.stop(context)
                    }
                }
            }
        }
        _session.value = session
        if (!away) ConnectionService.start(context)
    }

    /**
     * Shows [n] as its pane's program has it said, from the pane's screen if
     * the program reads it; nothing it says takes away what it said before.
     * Unless a newer one came, or the pane's been seen.
     */
    private suspend fun notify(session: Session, n: Notification) {
        val program = program(session, n.pane)
        val screen = if (program.readsScreenToNotify) session.peek(n.pane) else null
        val notice = withContext(Dispatchers.Default) { program.notice(n.title, n.body.takeUnless { n.bell }, screen) }
        if (!latest.remove(n.pane, n)) return
        if (notice != null) notices.show(n.pane, notice, session.state.value) else notices.cancel(n.pane)
    }

    private fun program(session: Session, pane: String): Program =
        Programs.of(session.state.value?.windows?.firstNotNullOfOrNull { w -> w.panes.firstOrNull { it.id == pane } }?.command.orEmpty())

    /** Ends the session, and the service keeping it. */
    fun disconnect() {
        watching?.cancel()
        _session.value?.stop()
        _session.value = null
        ConnectionService.stop(context)
        latest.clear()
        notices.clear()
    }

    /** The user left the app (it's no longer on screen), or came back to it. */
    fun away(on: Boolean) {
        away = on
        if (on) {
            _session.value?.away(true)
            return
        }
        ensure()
        val session = _session.value ?: return
        session.away(false)
        notices.lost(null)
        ConnectionService.start(context)
    }

    /** The pane the app shows: what the Mac's programs said there has been seen. */
    fun seen(pane: String) {
        latest.remove(pane)
        notices.cancel(pane)
    }
}
