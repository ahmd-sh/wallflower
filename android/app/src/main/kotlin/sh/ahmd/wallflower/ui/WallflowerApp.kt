package sh.ahmd.wallflower.ui

import androidx.activity.compose.BackHandler
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
import sh.ahmd.wallflower.session.Session.Companion.activePane
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
    val bar by model.keyBar.bar.collectAsStateWithLifecycle()
    val windows = state?.windows.orEmpty()
    val window = windows.firstOrNull { it.id == viewing } ?: windows.firstOrNull { it.active }
    val pane = window?.activePane()
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

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp).clipToBounds(),
                factory = { ctx ->
                    TerminalView(ctx).apply {
                        onGridSize = { cols, rows -> session.start(cols, rows); session.resize(cols, rows) }
                        view = this
                    }
                },
                update = { it.terminal = pane?.let { p -> terminals[p.id] } },
            )
            DisposableEffect(view, pane?.id) {
                val v = view
                val id = pane?.id
                session.onOutput = { if (it == id) v?.requestFrame() }
                onDispose { session.onOutput = null }
            }
        }

        if (pane != null) {
            val profile = bar.profileFor(pane.command)
            Composer(
                state = model.drafts.of(pane.id),
                program = pane.command,
                keys = profile.keys,
                connected = status is Status.Connected,
                onKeys = { session.send(Keys(pane.id, it)) },
                onSend = { text ->
                    session.submit(pane.id, text).also { sent -> if (sent && '\n' !in text) view?.predict(text) }
                },
                onStop = { session.send(Keys(pane.id, profile.stop)) },
                onEditKeys = onEditKeys,
            )
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

private fun agentColor(state: String): Color = when (state) {
    "waiting" -> Palette.orange
    "working" -> Palette.blue
    "done" -> Palette.green
    "error" -> Palette.red
    else -> Palette.grey
}
