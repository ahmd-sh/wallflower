package sh.ahmd.wallflower.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import sh.ahmd.wallflower.protocol.Kill
import sh.ahmd.wallflower.protocol.Rename
import sh.ahmd.wallflower.protocol.Split
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
fun WallflowerRoot(model: AppModel, unlock: (onDone: (Boolean) -> Unit) -> Unit, onExit: () -> Unit) {
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
                onExit = onExit,
            )
        }
        when (val s = screen) {
            Screen.Terminal -> Unit
            Screen.Settings -> {
                BackHandler { screen = Screen.Terminal }
                SettingsScreen(
                    host = host,
                    store = model.keyBar,
                    confirm = model.confirm,
                    appearance = model.appearance,
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
    onExit: () -> Unit,
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
        // Closing asks first, unless "don't ask again" said not to; a window's last pane closes the window.
        var renaming by remember { mutableStateOf<Window?>(null) }
        var closing by remember { mutableStateOf<Pair<Window, Pane?>?>(null) }
        fun close(w: Window, p: Pane?) {
            val wholeWindow = p == null || w.panes.size == 1
            val ask = if (wholeWindow) model.confirm.windows.value else model.confirm.panes.value
            when {
                ask -> closing = w to p
                p != null -> session.send(Kill(pane = p.id))
                else -> session.send(Kill(window = w.id))
            }
        }
        renaming?.let { w ->
            RenameDialog(w.name, onRename = { session.send(Rename(w.id, it)); renaming = null }, onDismiss = { renaming = null })
        }
        closing?.let { (w, p) ->
            val wholeWindow = p == null || w.panes.size == 1
            val last = windows.size == 1
            ConfirmClose(
                title = if (wholeWindow) "close ${w.name}?" else "close pane ${p.index}?",
                body = if (wholeWindow) {
                    (if (w.panes.size == 1) "Its pane, and whatever runs in it," else "Its ${w.panes.size} panes, and whatever runs in them,") +
                        " will be killed." +
                        if (last) " It's the last tab, so the tmux session ends too." else ""
                } else {
                    "${p.command} in pane ${p.index} will be killed."
                },
                action = if (wholeWindow) "close tab" else "close pane",
                onConfirm = { never ->
                    if (wholeWindow) {
                        if (never) model.confirm.askForWindows(false)
                        session.send(Kill(window = w.id))
                    } else {
                        if (never) model.confirm.askForPanes(false)
                        session.send(Kill(pane = p.id))
                    }
                    closing = null
                },
                onDismiss = { closing = null },
            )
        }

        TabBar(
            windows = windows,
            selected = window?.id,
            status = status,
            onSelect = { session.view(it) },
            onNew = { session.newWindow() },
            onRename = { renaming = it },
            onClose = { close(it, null) },
            onSettings = onSettings,
            onExit = onExit,
        )

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
        LaunchedEffect(window == null) { if (window == null) overview = false }
        BackHandler(enabled = overview) { overview = false }

        if (overview && window != null) {
            PaneOverview(
                window = window,
                shown = pane?.id,
                terminals = terminals,
                views = views,
                modifier = Modifier.weight(1f),
                onPick = { p ->
                    session.show(window, p)
                    overview = false
                },
                onNew = {
                    pane?.let { session.send(Split(it.id)) }
                    overview = false
                },
                onClose = { p -> close(window, p) },
            )
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
            if (window != null && pane != null) {
                PaneDots(window, pane.id, Modifier.align(Alignment.TopEnd)) { overview = true }
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
 * Every pane of a window as a card with its live contents, in tmux's order,
 * then one to open a new pane. Tap a card to show it, × to close it. Cards
 * fill as many columns as the screen is wide.
 */
@Composable
private fun PaneOverview(
    window: Window,
    shown: String?,
    terminals: Map<String, Terminal>,
    views: MutableMap<String, TerminalView>,
    modifier: Modifier,
    onPick: (Pane) -> Unit,
    onNew: () -> Unit,
    onClose: (Pane) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(window.panes.sortedBy { it.index }, key = { it.id }) { p ->
            PaneCard(p, p.id == shown, terminals[p.id], views, onClick = { onPick(p) }, onClose = { onClose(p) })
        }
        item(key = "new") { NewPaneCard(onNew) }
    }
}

@Composable
private fun PaneCard(
    pane: Pane,
    shown: Boolean,
    terminal: Terminal?,
    views: MutableMap<String, TerminalView>,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .clip(shape)
            .background(Palette.bg1)
            .border(2.dp, if (shown) Palette.orange else Palette.bg1, shape)
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${pane.index}", color = if (shown) Palette.orange else Palette.dim, fontFamily = words, fontSize = wordSize(12.sp, 14.sp), fontWeight = FontWeight.Bold)
            Text(" ${pane.command}", color = Palette.fg, fontFamily = words, fontSize = wordSize(12.sp, 14.sp), maxLines = 1, modifier = Modifier.weight(1f))
            if (pane.agent.isNotEmpty()) Box(Modifier.size(6.dp).clip(CircleShape).background(agentColor(pane.agent)))
            Box(Modifier.clip(CircleShape).clickable(onClick = onClose).padding(10.dp)) { GlyphIcon(Glyph.Close, Palette.grey, size = 14.dp) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(CARD_RATIO).background(Palette.bg0)) {
            // Small enough for the pane's whole width to fit the card.
            val font = (maxWidth.value / (pane.cols.coerceAtLeast(1) * GLYPH_WIDTH)).coerceIn(4f, 11f)
            // The grid reuses cards' views for other panes as it scrolls, so a view
            // registers for its pane's output whenever it's given one.
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(4.dp).clipToBounds(),
                factory = { ctx -> TerminalView(ctx) },
                onReset = { v -> views.values.remove(v) },
                onRelease = { v -> views.values.remove(v) },
                update = { v ->
                    v.fontSizeSp = font
                    v.terminal = terminal
                    views[pane.id] = v
                },
            )
            Box(Modifier.matchParentSize().clickable(onClick = onClick)) // the card takes the tap, not the terminal
        }
    }
}

@Composable
private fun NewPaneCard(onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.bg1)
            .clickable(onClick = onClick),
    ) {
        Text("new pane", color = Palette.grey, fontFamily = words, fontSize = wordSize(12.sp, 14.sp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
        Box(Modifier.fillMaxWidth().aspectRatio(CARD_RATIO), contentAlignment = Alignment.Center) {
            GlyphIcon(Glyph.Plus, Palette.grey, size = 28.dp)
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
 * The tabs (tmux windows), with new tab first and the connection last. It's
 * local: it scrolls natively and a tap switches immediately, tmux learns
 * afterwards. Holding a tab offers rename and close.
 */
@Composable
private fun TabBar(
    windows: List<Window>,
    selected: String?,
    status: Status,
    onSelect: (Window) -> Unit,
    onNew: () -> Unit,
    onRename: (Window) -> Unit,
    onClose: (Window) -> Unit,
    onSettings: () -> Unit,
    onExit: () -> Unit,
) {
    val view = LocalView.current
    val list = rememberLazyListState()
    val index = windows.indexOfFirst { it.id == selected }
    LaunchedEffect(index) {
        if (index >= 0) {
            val visible = list.layoutInfo.visibleItemsInfo
            if (visible.none { it.index == index && it.offset >= 0 && it.offset + it.size <= list.layoutInfo.viewportEndOffset }) {
                list.animateScrollToItem(index, scrollOffset = -48)
            }
        }
    }
    var menu by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        BarButton(Glyph.Plus, Palette.fg, Modifier.padding(start = 6.dp), onClick = onNew)
        LazyRow(
            state = list,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(windows, key = { it.id }) { w ->
                val on = w.id == selected
                val agent = w.panes.map { it.agent }.firstOrNull { it.isNotEmpty() }
                Box {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (on) Palette.bg3 else Palette.bg1)
                            .combinedClickable(
                                onClick = { onSelect(w) },
                                onLongClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    menu = w.id
                                },
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${w.index}", color = if (on) Palette.orange else Palette.dim, fontFamily = words, fontSize = wordSize(12.sp, 14.sp), fontWeight = FontWeight.Bold)
                        Text(" ${w.name}", color = if (on) Palette.fg else Palette.grey, fontFamily = words, fontSize = wordSize(12.sp, 14.sp), maxLines = 1)
                        if (agent != null) {
                            Box(Modifier.padding(start = 6.dp).size(6.dp).clip(CircleShape).background(agentColor(agent)))
                        }
                    }
                    DropdownMenu(expanded = menu == w.id, onDismissRequest = { menu = null }) {
                        DropdownMenuItem(text = { Text("rename") }, onClick = { menu = null; onRename(w) })
                        DropdownMenuItem(text = { Text("close", color = Palette.red) }, onClick = { menu = null; onClose(w) })
                    }
                }
            }
        }
        PowerButton(status, onSettings, onExit)
    }
}

@Composable
private fun BarButton(glyph: Glyph, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(glyph, color, size = 14.dp) }
}

/** The connection: its color says how it is; the menu says why, and leaves. */
@Composable
private fun PowerButton(status: Status, onSettings: () -> Unit, onExit: () -> Unit) {
    val (color, what) = when (status) {
        is Status.Connected -> Palette.green to "connected · tmux ${status.tmux}"
        Status.Connecting -> Palette.yellow to "connecting…"
        is Status.Disconnected -> Palette.red to "reconnecting: ${status.reason}"
        is Status.NeedsUser -> Palette.orange to "waiting for you"
    }
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(end = 6.dp)) {
        BarButton(Glyph.Power, color) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(what, color = color, fontFamily = words, fontSize = wordSize(12.sp, 14.sp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            DropdownMenuItem(text = { Text("settings") }, onClick = { open = false; onSettings() })
            DropdownMenuItem(text = { Text("disconnect", color = Palette.red) }, onClick = { open = false; onExit() })
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
