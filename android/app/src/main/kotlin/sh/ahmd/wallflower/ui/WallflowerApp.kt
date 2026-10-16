package sh.ahmd.wallflower.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.rememberUpdatedState
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import sh.ahmd.wallflower.protocol.Pane
import sh.ahmd.wallflower.terminal.InputLine
import sh.ahmd.wallflower.terminal.Terminal
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.ahmd.wallflower.protocol.Keys
import sh.ahmd.wallflower.protocol.Window
import sh.ahmd.wallflower.AppModel
import sh.ahmd.wallflower.session.Blocked
import sh.ahmd.wallflower.session.Session
import sh.ahmd.wallflower.session.Session.Companion.shownPane
import sh.ahmd.wallflower.session.Status
import sh.ahmd.wallflower.terminal.TerminalView

private sealed interface Screen {
    data object Terminal : Screen
    data object Settings : Screen
    data object Setup : Screen
    data class Profile(val ref: ProfileRef, val back: Screen) : Screen
}

@Composable
fun WallflowerRoot(model: AppModel, unlock: (onDone: (Boolean) -> Unit) -> Unit) {
    val host by model.host.collectAsStateWithLifecycle()
    val session by model.session.collectAsStateWithLifecycle()
    val hasKey by model.hasKey.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf<Screen>(Screen.Terminal) }
    var program by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    LaunchedEffect(screen) { if (screen != Screen.Terminal) focus.clearFocus() } // the composer's keyboard goes away
    val current = session
    val setup = @Composable {
        SetupScreen(
            host = host,
            hasKey = hasKey,
            fingerprint = if (hasKey) model.key.fingerprint else null,
            authorizeCommand = model::authorizeCommand,
            createKey = model::createKey,
        ) {
            model.configure(it)
            screen = Screen.Terminal
        }
    }
    if (host == null || current == null) {
        setup()
        return
    }
    // Pages draw over the terminal, which keeps its place underneath.
    Box(Modifier.fillMaxSize()) {
        key(current) {
            TerminalScreen(
                current,
                model,
                unlock,
                onProgram = { program = it },
                onSettings = { screen = Screen.Settings },
                onEditKeys = { screen = Screen.Profile(model.keyBar.bar.value.refFor(program), Screen.Terminal) },
            )
        }
        when (val s = screen) {
            Screen.Terminal -> Unit
            Screen.Settings -> {
                BackHandler { screen = Screen.Terminal }
                SettingsScreen(
                    host = host,
                    store = model.keyBar,
                    program = program,
                    onBack = { screen = Screen.Terminal },
                    onEditHost = { screen = Screen.Setup },
                    onOpen = { screen = Screen.Profile(it, Screen.Settings) },
                )
            }
            Screen.Setup -> {
                BackHandler { screen = Screen.Settings }
                Box(Modifier.pointerInput(Unit) { detectTapGestures { } }) { setup() }
            }
            is Screen.Profile -> {
                BackHandler { screen = s.back }
                ProfileScreen(
                    store = model.keyBar,
                    ref = s.ref,
                    program = program,
                    onBack = { screen = s.back },
                    onOpen = { screen = s.copy(ref = it) },
                )
            }
        }
    }
}

@Composable
private fun TerminalScreen(
    session: Session,
    model: AppModel,
    unlock: (onDone: (Boolean) -> Unit) -> Unit,
    onProgram: (String?) -> Unit,
    onSettings: () -> Unit,
    onEditKeys: () -> Unit,
) {
    val status by session.status.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val terminals by session.terminals.collectAsStateWithLifecycle()
    val viewing by session.viewing.collectAsStateWithLifecycle()
    val chosen by session.chosen.collectAsStateWithLifecycle()
    val bar by model.keyBar.bar.collectAsStateWithLifecycle()
    val windows = state?.windows.orEmpty()
    val window = windows.firstOrNull { it.id == viewing } ?: windows.firstOrNull { it.active }
    val pane = window?.shownPane(chosen)
    LaunchedEffect(pane?.command) { onProgram(pane?.command) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.bg0)
            .systemBarsPadding()
            .imePadding(),
    ) {
        WindowTabs(windows, window?.id, status, onSettings) { session.view(it) }

        var view by remember { mutableStateOf<TerminalView?>(null) }

        val blocked = (status as? Status.NeedsUser)?.blocked
        // Connecting is the moment to ask for the fingerprint, so ask right away.
        LaunchedEffect(blocked) {
            if (blocked is Blocked.KeyLocked) unlock { ok -> if (ok) session.retry() }
        }
        if (blocked != null) {
            BlockedCard(
                blocked = blocked,
                authorizeCommand = model::authorizeCommand,
                onUnlock = { unlock { ok -> if (ok) session.retry() } },
                onTrust = { model.hostKeys.trust(it); session.retry() },
                onForget = { model.hostKeys.forget(it.host); session.retry() },
                onRetry = session::retry,
                onSetup = onSettings,
            )
        }

        // Output reaches whichever views show the pane, and rereads the app's input line.
        val views = remember { ConcurrentHashMap<String, TerminalView>() }
        val lineTicks = remember { Channel<Unit>(Channel.CONFLATED) }
        val shownId by rememberUpdatedState(pane?.id)
        DisposableEffect(session) {
            session.onOutput = { id ->
                views[id]?.requestFrame()
                if (id == shownId) lineTicks.trySend(Unit)
            }
            onDispose { session.onOutput = null }
        }
        var line by remember { mutableStateOf<InputLine?>(null) }
        val terminal = pane?.let { terminals[it.id] }
        LaunchedEffect(terminal) {
            line = null
            lineTicks.trySend(Unit)
            for (tick in lineTicks) {
                line = withContext(Dispatchers.Default) { terminal?.screen()?.let(InputLine::read) }
                delay(LINE_EVERY_MS)
            }
        }

        var overview by remember { mutableStateOf(false) }
        LaunchedEffect(window?.id, window?.panes?.size) { if ((window?.panes?.size ?: 0) < 2) overview = false }
        BackHandler(enabled = overview) { overview = false }

        if (overview && window != null) {
            PaneOverview(window, pane?.id, terminals, views, Modifier.weight(1f)) { p ->
                session.show(window, p)
                overview = false
            }
            return@Column
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp).clipToBounds(),
                factory = { ctx ->
                    TerminalView(ctx).apply {
                        onGridSize = { cols, rows -> session.start(cols, rows); session.resize(cols, rows) }
                        view = this
                    }
                },
                update = {
                    it.terminal = terminal
                    it.onSwipe = { direction -> window?.let { w -> session.swipe(w, direction) } }
                    it.onAppScroll = { up, wheel ->
                        pane?.let { p ->
                            if (wheel != null) session.input(p.id, wheel) else session.send(Keys(p.id, listOf(if (up) "Up" else "Down")))
                        }
                    }
                },
            )
            if (window != null && pane != null && window.panes.size > 1) {
                PaneDots(window, pane.id, Modifier.align(Alignment.TopEnd)) {
                    session.peek(window)
                    overview = true
                }
            }
            DisposableEffect(view, pane?.id) {
                val v = view
                val id = pane?.id
                if (v != null && id != null) views[id] = v
                onDispose { if (id != null) views.remove(id, v) }
            }
        }

        if (pane != null) {
            val profile = bar.profileFor(pane.command)
            Composer(
                state = model.drafts.of(pane.id),
                program = pane.command,
                keys = profile.keys,
                connected = status is Status.Connected,
                line = line,
                onKey = { session.send(Keys(pane.id, it.keys, it.bindings)) },
                onSend = { text, enter ->
                    // The box replaces whatever the app's line holds.
                    val known = line
                    val clear = if (known == null) emptyList() else List(known.rows * 2 + 1) { profile.clearKeys() }.flatten()
                    session.submit(pane.id, text, enter, clear).also { sent ->
                        if (sent && '\n' !in text && known?.text.isNullOrEmpty()) view?.predict(text)
                    }
                },
                onEnter = { session.send(Keys(pane.id, listOf("Enter"))) },
                onStop = { session.send(Keys(pane.id, profile.stop)) },
                onEditKeys = onEditKeys,
            )
        }
    }
}

/**
 * Every pane of a window as a card with its live contents, in tmux's order;
 * tap one to show it. Cards fill as many columns as the screen is wide.
 */
@Composable
private fun PaneOverview(
    window: Window,
    shown: String?,
    terminals: Map<String, Terminal>,
    views: MutableMap<String, TerminalView>,
    modifier: Modifier,
    onPick: (Pane) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(window.panes.sortedBy { it.index }, key = { it.id }) { p ->
            PaneCard(p, p.id == shown, terminals[p.id], views) { onPick(p) }
        }
    }
}

@Composable
private fun PaneCard(pane: Pane, shown: Boolean, terminal: Terminal?, views: MutableMap<String, TerminalView>, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .clip(shape)
            .background(Palette.bg1)
            .border(2.dp, if (shown) Palette.orange else Palette.bg1, shape)
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${pane.index}", color = if (shown) Palette.orange else Palette.dim, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(" ${pane.command}", color = Palette.fg, fontFamily = Mono, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
            if (pane.agent.isNotEmpty()) Box(Modifier.size(6.dp).clip(CircleShape).background(agentColor(pane.agent)))
        }
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(CARD_RATIO).background(Palette.bg0)) {
            // Small enough for the pane's whole width to fit the card.
            val font = (maxWidth.value / (pane.cols.coerceAtLeast(1) * GLYPH_WIDTH)).coerceIn(4f, 11f)
            var thumb by remember { mutableStateOf<TerminalView?>(null) }
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(4.dp).clipToBounds(),
                factory = { ctx -> TerminalView(ctx).also { thumb = it } },
                update = {
                    it.fontSizeSp = font
                    it.terminal = terminal
                },
            )
            DisposableEffect(thumb, pane.id) {
                val v = thumb
                if (v != null) views[pane.id] = v
                onDispose { views.remove(pane.id, v) }
            }
            Box(Modifier.matchParentSize().clickable(onClick = onClick)) // the card takes the tap, not the terminal
        }
    }
}

/** Which of the window's panes is showing: swipe the terminal sideways for the others, tap for all of them. */
@Composable
private fun PaneDots(window: Window, shown: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.bg1.copy(alpha = 0.85f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        for (p in window.panes.sortedBy { it.index }) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (p.id == shown) Palette.fg else Palette.bg3))
        }
    }
}

/**
 * The window strip. Entirely local: it scrolls natively and a tap switches
 * immediately; tmux learns about it afterwards.
 */
@Composable
private fun WindowTabs(windows: List<Window>, selected: String?, status: Status, onSettings: () -> Unit, onSelect: (Window) -> Unit) {
    val list = rememberLazyListState()
    val index = windows.indexOfFirst { it.id == selected }
    LaunchedEffect(index) {
        if (index >= 0) {
            val visible = list.layoutInfo.visibleItemsInfo
            if (visible.none { it.index == index + 1 && it.offset >= 0 && it.offset + it.size <= list.layoutInfo.viewportEndOffset }) {
                list.animateScrollToItem(index + 1, scrollOffset = -48)
            }
        }
    }
    LazyRow(
        state = list,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "status") {
            Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onSettings).padding(6.dp)) { StatusDot(status) }
        }
        items(windows, key = { it.id }) { w ->
            val on = w.id == selected
            val agent = w.panes.map { it.agent }.firstOrNull { it.isNotEmpty() }
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) Palette.bg3 else Palette.bg1)
                    .clickable { onSelect(w) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${w.index}",
                    color = if (on) Palette.orange else Palette.dim,
                    fontFamily = Mono,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    " ${w.name}",
                    color = if (on) Palette.fg else Palette.grey,
                    fontFamily = Mono,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
                if (agent != null) {
                    Box(Modifier.padding(start = 6.dp).size(6.dp).clip(CircleShape).background(agentColor(agent)))
                }
            }
        }
    }
}

@Composable
private fun StatusDot(status: Status) {
    val color = when (status) {
        is Status.Connected -> Palette.green
        Status.Connecting -> Palette.yellow
        is Status.Disconnected -> Palette.red
        is Status.NeedsUser -> Palette.orange
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        if (status is Status.Disconnected) {
            Text(" ${status.reason}", color = Palette.red, fontFamily = Mono, fontSize = 11.sp, maxLines = 1)
        }
    }
}

private const val LINE_EVERY_MS = 80L

/** Cards are a phone screen's shape, like a browser's tab grid. */
private const val CARD_RATIO = 0.75f

/** A JetBrains Mono glyph's width, per point of font size. */
private const val GLYPH_WIDTH = 0.6f

private fun agentColor(state: String): Color = when (state) {
    "waiting" -> Palette.orange
    "working" -> Palette.blue
    "done" -> Palette.green
    "error" -> Palette.red
    else -> Palette.grey
}
