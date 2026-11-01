package sh.ahmd.wallflower.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import sh.ahmd.wallflower.protocol.Focus
import sh.ahmd.wallflower.protocol.Kill
import sh.ahmd.wallflower.protocol.New
import sh.ahmd.wallflower.protocol.Pane
import sh.ahmd.wallflower.protocol.Rename
import sh.ahmd.wallflower.protocol.Select
import sh.ahmd.wallflower.protocol.Split
import sh.ahmd.wallflower.protocol.State
import sh.ahmd.wallflower.protocol.Window

/** A pane shown instead of its window's active one, until tmux's active pane is no longer [active]. */
data class PaneChoice(val pane: String, val active: String?)

fun Window.activePane(): Pane? = panes.firstOrNull { it.active }

/** The pane the phone shows for a window: one picked on the phone, until tmux moves on. */
fun Window.shownPane(chosen: Map<String, PaneChoice>): Pane? {
    val active = activePane()
    val choice = chosen[id]?.takeIf { it.active == active?.id }
    return choice?.let { c -> panes.firstOrNull { it.id == c.pane } } ?: active
}

/**
 * The tmux session as the phone has it: its state, pushed by the gateway,
 * and which window and panes the phone shows, which are the phone's own and
 * change the moment they're picked; tmux is told in the background.
 */
internal class Workspace(private val link: Link, private val terminals: Terminals) {
    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    /** The window on screen. */
    private val _viewing = MutableStateFlow<String?>(null)
    val viewing: StateFlow<String?> = _viewing.asStateFlow()

    /** Panes picked on the phone, by window, so a swipe shows the pane at once. */
    private val _chosen = MutableStateFlow<Map<String, PaneChoice>>(emptyMap())
    val chosen: StateFlow<Map<String, PaneChoice>> = _chosen.asStateFlow()

    @Volatile private var awaitingWindow: Set<String>? = null // windows there were when the phone opened one
    @Volatile private var synced = false // this connection's session has the window being viewed current

    /** The user is away: a new connection leaves the desktop's window as it is (coming back takes it, see [reclaim]). */
    @Volatile var away = false

    /** A new connection's session starts wherever the desktop is. */
    fun linked() {
        synced = false
    }

    fun state(m: State) {
        _state.value = m
        // A pane picked on the phone gives way once tmux's active pane moves on (to it, or elsewhere).
        _chosen.update { chosen -> chosen.filter { (w, c) -> m.windows.firstOrNull { it.id == w }?.activePane()?.id == c.active } }
        _viewing.update { v -> if (m.windows.none { it.id == v }) m.windows.firstOrNull { it.active }?.id else v }
        awaitingWindow?.let { known ->
            m.windows.firstOrNull { it.id !in known }?.let { w ->
                _viewing.value = w.id
                awaitingWindow = null
            }
        }
        // What's shown is the phone's choice.
        if (!synced) {
            synced = true
            if (!away && m.windows.none { it.id == _viewing.value && it.active }) _viewing.value?.let { link.send(Select(window = it)) }
        }
        open(m)
        terminals.keep(m.windows.flatMapTo(HashSet()) { w -> w.panes.map { it.id } })
    }

    /** Opens every pane again, after they went quiet. */
    fun reopen() {
        _state.value?.let(::open)
    }

    private fun open(m: State) {
        val chosen = _chosen.value
        m.windows.firstOrNull { it.id == _viewing.value }?.shownPane(chosen)?.let { terminals.open(it.id, viewed = true) }
        for (w in m.windows) w.shownPane(chosen)?.let { terminals.open(it.id, viewed = false, full = true) }
        for (w in m.windows) for (p in w.panes.sortedBy { it.index }) terminals.open(p.id, viewed = false)
    }

    /** Shows a window now; tmux follows in the background (it sizes the window for the phone). */
    fun view(window: Window) {
        _viewing.value = window.id
        window.activePane()?.let { terminals.open(it.id, viewed = true) }
        link.send(Select(window = window.id))
    }

    /** Shows a pane of a window now, and has tmux focus it in the background, zoomed so it gets the whole screen. */
    fun show(window: Window, pane: Pane) {
        _chosen.update { it + (window.id to PaneChoice(pane.id, window.activePane()?.id)) }
        terminals.open(pane.id, viewed = true)
        link.send(Focus(pane.id))
    }

    /** The desktop may have taken the window over; showing it again takes it back. */
    fun reclaim() {
        _viewing.value?.let { link.send(Select(window = it)) }
    }

    /** Opens a window, which the phone then shows; the desktop stays where it is. */
    fun newWindow() {
        awaitingWindow = _state.value?.windows?.map { it.id }?.toSet().orEmpty()
        link.send(New)
    }

    /** Opens a pane beside [pane], which tmux focuses. */
    fun split(pane: String) = link.send(Split(pane))

    fun kill(window: Window) = link.send(Kill(window = window.id))

    fun kill(pane: Pane) = link.send(Kill(pane = pane.id))

    fun rename(window: Window, name: String) = link.send(Rename(window.id, name))
}
