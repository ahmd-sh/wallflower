package sh.ahmd.wallflower.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Constraints
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.ahmd.wallflower.AppModel
import sh.ahmd.wallflower.program.Programs
import sh.ahmd.wallflower.protocol.Pane
import sh.ahmd.wallflower.protocol.Window
import sh.ahmd.wallflower.read.Activity
import sh.ahmd.wallflower.read.Reading
import sh.ahmd.wallflower.read.activityOf
import sh.ahmd.wallflower.session.Blocked
import sh.ahmd.wallflower.session.Session
import sh.ahmd.wallflower.session.Status
import sh.ahmd.wallflower.session.shownPane
import sh.ahmd.wallflower.terminal.TerminalView
import sh.ahmd.wallflower.ui.composer.Attachments
import sh.ahmd.wallflower.ui.composer.Composer

private sealed interface Screen {
    data object Terminal : Screen
    data object Settings : Screen
    data object Setup : Screen
    data class Profile(val ref: ProfileRef, val back: Screen) : Screen
}

/** How many pages in: a page further in slides in over the one before. */
private val Screen.depth: Int
    get() = when (this) {
        Screen.Terminal -> 0
        Screen.Settings -> 1
        Screen.Setup -> 2
        is Screen.Profile -> back.depth + 1
    }

private fun Screen.previous(): Screen = when (this) {
    Screen.Terminal, Screen.Settings -> Screen.Terminal
    Screen.Setup -> Screen.Settings
    is Screen.Profile -> back
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
        BackHandler(enabled = screen != Screen.Terminal) { screen = screen.previous() }
        // A page further in slides in from the right over the last; back, it slides away again.
        AnimatedContent(
            targetState = screen,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val deeper = targetState.depth - initialState.depth
                when {
                    deeper > 0 -> (slideInHorizontally(Motion.spatial()) { it / 4 } + fadeIn(Motion.effect())) togetherWith
                        (slideOutHorizontally(Motion.spatial()) { -it / 10 } + fadeOut(Motion.effect()))
                    deeper < 0 -> ((slideInHorizontally(Motion.spatial()) { -it / 10 } + fadeIn(Motion.effect())) togetherWith
                        (slideOutHorizontally(Motion.spatial()) { it / 4 } + fadeOut(Motion.effect()))).apply { targetContentZIndex = -1f }
                    else -> fadeIn(Motion.effect()) togetherWith fadeOut(Motion.effect())
                }
            },
            label = "page",
        ) { s ->
            when (s) {
                Screen.Terminal -> Unit
                Screen.Settings -> SettingsScreen(
                    host = host,
                    store = model.keyBar,
                    settings = model.settings,
                    program = program,
                    onBack = { screen = Screen.Terminal },
                    onEditHost = { screen = Screen.Setup },
                    onOpen = { screen = Screen.Profile(it, Screen.Settings) },
                )
                Screen.Setup -> Box(Modifier.pointerInput(Unit) { detectTapGestures { } }) { setup() }
                is Screen.Profile -> ProfileScreen(
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

/**
 * The session's terminals: tabs, the shown window's panes to swipe between
 * (each its terminal, or its read view), the composer, and the cards of
 * every pane. What's shown is the phone's own; tmux follows.
 */
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
    val haptics = haptics()
    val focusManager = LocalFocusManager.current

    // Text the gateway never confirmed comes back into the box; it's felt, as it may not be seen.
    LaunchedEffect(session) { session.undelivered.collect { haptics.reject() } }

    // Panes whose program can be read show their read view while it's on: the shown pane's, and its
    // neighbours', so a swipe brings theirs in already loaded.
    val readings by session.readings.collectAsStateWithLifecycle()
    val readViewOn by model.settings.readView.flow.collectAsStateWithLifecycle()
    val nearby = window?.panes?.sortedBy { it.index }?.let { panes ->
        val i = panes.indexOfFirst { it.id == pane?.id }
        if (i < 0) emptyList() else listOf(-1, 0, 1).map { panes[(i + it).mod(panes.size)] }.distinct()
    }.orEmpty()
    val readViews = if (readViewOn) nearby.filter { it.reader.isNotEmpty() }.map { it.id }.toSet() else emptySet()
    LaunchedEffect(readViews) { session.read(readViews) }
    val readView = pane != null && pane.id in readViews

    fun actionsOf(p: Pane): PaneActions {
        val program = Programs.of(p.command)
        val profile = bar.profileFor(p.command)
        return PaneActions(session, p, program, profile.clear ?: program.clearKeys, profile.stop)
    }
    val actions = remember(session, pane, bar) { pane?.let(::actionsOf) }
    val activity = activityOf(pane)
    val screen = rememberPaneScreen(
        pane?.id,
        pane?.let { terminals[it.id] },
        actions?.program ?: Programs.of(""),
        asks = readView && activity != Activity.Working, // a prompt while the program works isn't one to answer
        activity,
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.bg0)
            .systemBarsPadding()
            .imePadding(),
    ) {
        val closing = remember(session, haptics) { Closing(session, model.settings, haptics) }
        var renaming by remember { mutableStateOf<Window?>(null) }
        renaming?.let { w ->
            RenameDialog(w.name, onRename = { session.rename(w, it); renaming = null }, onDismiss = { renaming = null })
        }
        CloseDialog(closing, windows)

        TabBar(
            windows = windows,
            selected = window?.id,
            status = status,
            onSelect = { session.view(it) },
            onNew = { session.newWindow() },
            onRename = { renaming = it },
            onClose = { closing.close(it, null) },
            onSettings = onSettings,
            onExit = onExit,
        )

        val blocked = (status as? Status.NeedsUser)?.blocked
        // Connecting is the moment to ask for the fingerprint, so ask right away.
        LaunchedEffect(blocked) {
            if (blocked is Blocked.KeyLocked) unlock { ok -> if (ok) session.retry() }
        }
        val lastBlocked = rememberLast(blocked)
        AnimatedVisibility(
            blocked != null,
            enter = fadeIn(Motion.effect()) + expandVertically(Motion.spatial()),
            exit = fadeOut(Motion.effect()) + shrinkVertically(Motion.spatial()),
        ) {
            lastBlocked?.let {
                BlockedCard(
                    blocked = it,
                    authorizeCommand = model::authorizeCommand,
                    onUnlock = { unlock { ok -> if (ok) session.retry() } },
                    onTrust = { k -> model.hostKeys.trust(k); session.retry() },
                    onForget = { k -> model.hostKeys.forget(k.host); session.retry() },
                    onRetry = session::retry,
                    onSetup = onSettings,
                )
            }
        }

        val overview = rememberOverview()
        LaunchedEffect(window == null) {
            if (window == null) {
                overview.open = false
                overview.zoom.snapTo(0f)
            }
        }
        BackHandler(enabled = overview.open) { overview.hide(into = pane?.id) }
        var view by remember { mutableStateOf<TerminalView?>(null) } // the shown pane's
        var paneBounds by remember { mutableStateOf(Rect.Zero) }
        var position by remember { mutableFloatStateOf(0f) }
        var areaHeight by remember { mutableIntStateOf(0) }
        val settleHeight by rememberSettleHeight { areaHeight }

        @Composable
        fun Page(p: Pane?, current: Boolean, swipe: Swipe?) = PanePage(
            actions = remember(session, p, bar) { p?.let(::actionsOf) },
            terminal = p?.let { terminals[it.id] },
            reading = p?.takeIf { it.id in readViews }?.let { readings[it.id] ?: Reading() },
            current = current,
            swipe = swipe,
            settleHeight = settleHeight,
            covered = overview.covering,
            prompt = screen.prompt,
            line = { screen.line },
            onGridSize = { cols, rows, ahead -> session.start(cols, rows); session.resize(cols, rows, ahead) },
            onView = { view = it },
        )

        // The panes take what the composer leaves; the cards cover both. While the
        // pane zooms it's drawn over the cards, and once they've settled, under them.
        Layout(
            contents = listOf(
                {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .onSizeChanged { areaHeight = it.height }
                            .onGloballyPositioned { paneBounds = it.rootRect() },
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer { zoomInto(overview.zoom.value, overview.zoomed?.let { overview.cards[it] }, paneBounds, view) }
                                .background(Palette.bg0),
                        ) {
                            if (window == null || pane == null) {
                                Page(null, current = true, swipe = null) // measures the grid until there are panes
                            } else {
                                // Tabs slide along in the direction of the tab bar.
                                AnimatedContent(
                                    targetState = window.id,
                                    transitionSpec = {
                                        val forward = windows.indexOfFirst { it.id == targetState } >= windows.indexOfFirst { it.id == initialState }
                                        val sign = if (forward) 1 else -1
                                        (slideInHorizontally(Motion.spatial()) { sign * it / 5 } + fadeIn(Motion.effect())) togetherWith
                                            (slideOutHorizontally(Motion.spatial()) { -sign * it / 5 } + fadeOut(Motion.effect()))
                                    },
                                    label = "tab",
                                ) { id ->
                                    val w = windows.firstOrNull { it.id == id }
                                    val shownHere = w?.shownPane(chosen)
                                    if (w != null && shownHere != null) {
                                        PanePager(
                                            remember(w.panes) { w.panes.sortedBy { it.index } },
                                            shownHere,
                                            onShow = { session.show(w, it) },
                                            onPosition = if (id == window.id) { at -> position = at } else null,
                                        ) { p, current, swipe ->
                                            Page(p, current, swipe)
                                        }
                                    }
                                }
                            }
                        }
                        if (window != null && pane != null) {
                            Row(
                                Modifier.align(Alignment.TopEnd).graphicsLayer { alpha = 1f - overview.zoom.value },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AnimatedVisibility(
                                    pane.reader.isNotEmpty(),
                                    enter = fadeIn(Motion.effect()) + expandHorizontally(Motion.spatial()),
                                    exit = fadeOut(Motion.effect()) + shrinkHorizontally(Motion.spatial()),
                                ) {
                                    ViewToggle(reading = readView) {
                                        haptics.toggle(!readView)
                                        model.settings.readView.set(!readView)
                                    }
                                }
                                key(window.id) {
                                    PaneDots(window.panes.size, { position }) {
                                        overview.show(from = pane.id)
                                        focusManager.clearFocus()
                                    }
                                }
                            }
                        }
                    }
                },
                {
                    if (pane != null && actions != null) {
                        val uploads by model.uploads.items.collectAsStateWithLifecycle()
                        val uploadDir by model.uploads.dir.collectAsStateWithLifecycle()
                        Composer(
                            state = model.drafts.of(pane.id),
                            program = pane.command,
                            keys = bar.profileFor(pane.command).keys,
                            connected = status is Status.Connected,
                            line = screen.line,
                            attachments = Attachments(
                                uploads = remember(uploads, pane.id) { uploads.filter { it.pane == pane.id } },
                                dir = uploadDir,
                                thumbnail = model.uploads::thumbnail,
                                onAttach = { uris -> model.uploads.add(session, pane.id, uris) },
                                onDismiss = model.uploads::dismiss,
                            ),
                            onClear = if (!actions.clears) null else {
                                {
                                    screen.line?.let { actions.clear(it, unseen = readView) }
                                    screen.line = null // gone at once; the next read says what's there now
                                }
                            },
                            onKey = { actions.keys(it.keys, it.bindings) },
                            onSend = { text, enter ->
                                // The box replaces whatever the app's line holds.
                                val known = screen.line
                                actions.send(text, enter, known, unseen = readView).also { sent ->
                                    if (sent && '\n' !in text && known?.text.isNullOrEmpty()) view?.predict(text)
                                }
                            },
                            onEnter = actions::enter,
                            onStop = actions::stop,
                            onEditKeys = onEditKeys,
                        )
                    }
                },
                {
                    if (overview.up && window != null) {
                        PaneOverview(
                            window = window,
                            shown = pane?.id,
                            zoomed = overview.zoomed,
                            progress = { overview.zoom.value },
                            terminals = terminals,
                            cards = overview.cards,
                            onPick = { p ->
                                haptics.tick()
                                session.show(window, p)
                                overview.hide(into = p.id)
                            },
                            onNew = {
                                pane?.let { session.split(it) }
                                overview.hide(into = pane?.id)
                            },
                            onClose = { p -> closing.close(window, p) },
                        )
                    }
                },
            ),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { (panes, composer, overviewSlot), constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val c = composer.firstOrNull()?.measure(constraints.copy(minHeight = 0))
            val paneHeight = (height - (c?.height ?: 0)).coerceAtLeast(0)
            val p = panes.firstOrNull()?.measure(Constraints.fixed(width, paneHeight))
            val o = overviewSlot.firstOrNull()?.measure(Constraints.fixed(width, height))
            layout(width, height) {
                p?.place(0, 0, zIndex = if (overview.covering) -1f else 1f)
                c?.place(0, paneHeight)
                o?.place(0, 0, zIndex = 0.5f)
            }
        }
    }
}
