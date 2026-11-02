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
import sh.ahmd.wallflower.program.Effect
import sh.ahmd.wallflower.program.Program
import sh.ahmd.wallflower.program.Programs
import sh.ahmd.wallflower.protocol.Notification
import sh.ahmd.wallflower.screen.Prompt
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
 * shows in the phone's notifications ([Notices]), with what the pane asks
 * and buttons answering it where its [Program] reads its menus. It ends
 * when the user disconnects.
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

    /** A notification shown for a pane, and what the pane asked as it came, once read. */
    private class Asked(val n: Notification, @Volatile var prompt: Prompt? = null)

    /** By pane, the latest. */
    private val asked = ConcurrentHashMap<String, Asked>()
    private val answering = ConcurrentHashMap.newKeySet<String>()

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
        val session = Session(transport, scope)
        session.away(away)
        watching = scope.launch {
            launch {
                session.notifications.collect { n ->
                    if (away || n.pane != session.shownPane()) {
                        notices.show(n, session.state.value)
                        val a = Asked(n).also { asked[n.pane] = it }
                        launch { ask(session, a) }
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

    /** Fills in [a]'s notification with what its pane asks, read off its screen, if its program can tell. */
    private suspend fun ask(session: Session, a: Asked) {
        val (program, prompt) = read(session, a.n.pane) ?: return
        if (asked[a.n.pane] !== a || !notices.showing(a.n.pane)) return // a newer one, or seen
        a.prompt = prompt
        notices.show(a.n, session.state.value, prompt, program.answers(prompt), alert = false)
    }

    /** What [pane] asks, read off its screen by its program, which tmux may only just have told of. */
    private suspend fun read(session: Session, pane: String): Pair<Program, Prompt>? {
        val screen = session.peek(pane) ?: return null
        val program = program(session, pane) ?: return null
        return withContext(Dispatchers.Default) { program.prompt(screen) }?.let { program to it }
    }

    private fun program(session: Session, pane: String): Program? =
        session.state.value?.windows?.firstNotNullOfOrNull { w -> w.panes.firstOrNull { it.id == pane } }?.let { Programs.of(it.command) }

    /**
     * A notification's button: picks [option] of what [pane] asks, if it
     * still asks the same (it may have been answered on the Mac); [done] once
     * it's dealt with.
     */
    fun answer(pane: String, option: String, done: () -> Unit) {
        if (!answering.add(pane)) return done() // pressed twice
        scope.launch {
            try {
                answered(pane, option)
            } finally {
                answering.remove(pane)
                done()
            }
        }
    }

    private suspend fun answered(pane: String, option: String) {
        val session = _session.value
        val a = asked[pane]
        val prompt = a?.prompt
        if (session == null || a == null || prompt == null) return notices.unanswered(pane)
        val state = session.state.value
        notices.show(a.n, state, prompt, note = "answering “$option”…", alert = false)
        val (program, now) = read(session, pane) ?: (null to null)
        val picked = now?.takeIf { it.same(prompt) }?.options?.firstOrNull { it.label == option }
        if (program == null || now == null || picked == null) {
            asked.remove(pane, a)
            return notices.show(a.n, state, note = "It's not asking that any more: tap to see.", alert = false)
        }
        if (!session.keys(pane, now.keys(picked))) return notices.unanswered(pane)
        when (val effect = program.picked(now, picked)) {
            is Effect.Rewound -> session.rewound(pane, effect.said)
            null -> Unit
        }
        asked.remove(pane, a)
        notices.cancel(pane)
    }

    /** Ends the session, and the service keeping it. */
    fun disconnect() {
        watching?.cancel()
        _session.value?.stop()
        _session.value = null
        ConnectionService.stop(context)
        asked.clear()
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
        asked.remove(pane)
        notices.cancel(pane)
    }
}
