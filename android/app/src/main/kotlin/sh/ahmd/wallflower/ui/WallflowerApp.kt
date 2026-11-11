package sh.ahmd.wallflower.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Constraints
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import sh.ahmd.wallflower.AppModel
import sh.ahmd.wallflower.program.Programs
import sh.ahmd.wallflower.program.Walk
import sh.ahmd.wallflower.protocol.Pane
import sh.ahmd.wallflower.protocol.Window
import sh.ahmd.wallflower.read.Activity
import sh.ahmd.wallflower.read.Entry
import sh.ahmd.wallflower.read.Party
import sh.ahmd.wallflower.read.Reading
import sh.ahmd.wallflower.read.activityOf
import sh.ahmd.wallflower.session.Blocked
import sh.ahmd.wallflower.session.Host
import sh.ahmd.wallflower.session.Session
import sh.ahmd.wallflower.session.Status
import sh.ahmd.wallflower.session.activePane
import sh.ahmd.wallflower.session.called
import sh.ahmd.wallflower.session.label
import sh.ahmd.wallflower.session.shownPane
import sh.ahmd.wallflower.terminal.TerminalView
import sh.ahmd.wallflower.ui.composer.Attachments
import sh.ahmd.wallflower.ui.composer.Composer
import sh.ahmd.wallflower.ui.composer.Recalled
import sh.ahmd.wallflower.ui.composer.Typing
import androidx.compose.runtime.mutableLongStateOf
import sh.ahmd.wallflower.screen.Prompt

/** Where the user is: the session's terminals, or the Mac's sessions while the phone's in none ([Terminal] either way), or a page over them. */
private sealed interface Screen {
    data object Terminal : Screen
    data object Settings : Screen
    data object Setup : Screen
    data object Look : Screen
    data object KeyBars : Screen
    data class Profile(val ref: ProfileRef, val back: Screen) : Screen
}

/** How many pages in: a page further in slides in over the one before. */
private val Screen.depth: Int
    get() = when (this) {
        Screen.Terminal -> 0
        Screen.Settings -> 1
        Screen.Setup, Screen.Look, Screen.KeyBars -> 2
        is Screen.Profile -> back.depth + 1
    }

private fun Screen.previous(): Screen = when (this) {
    Screen.Terminal, Screen.Settings -> Screen.Terminal
    Screen.Setup, Screen.Look, Screen.KeyBars -> Screen.Settings
    is Screen.Profile -> back
}

@Composable
fun WallflowerRoot(model: AppModel, unlock: (onDone: (Boolean) -> Unit) -> Unit, onExit: () -> Unit) {
    val host by model.host.collectAsStateWithLifecycle()
    val session by model.session.collectAsStateWithLifecycle()
    val hasKey by model.hasKey.collectAsStateWithLifecycle()
    val unlockSeconds by model.unlockSeconds.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf<Screen>(Screen.Terminal) }
    var program by remember { mutableStateOf<String?>(null) } // the focused pane's command
    var programId by remember { mutableStateOf<String?>(null) } // and the program the gateway named
    val focus = LocalFocusManager.current
    LaunchedEffect(screen) { if (screen != Screen.Terminal) focus.clearFocus() } // the composer's keyboard goes away
    val current = session
    val mux by (current?.mux ?: flowOf(null)).collectAsStateWithLifecycle(null)
    val setup = @Composable { onBack: (() -> Unit)? ->
        SetupScreen(
            host = host,
            hasKey = hasKey,
            fingerprint = if (hasKey) model.key.fingerprint else null,
            authorizeCommand = model::authorizeCommand,
            createKey = model::createKey,
            unlockSeconds = unlockSeconds,
            newKey = model::newKey,
            onBack = onBack,
        ) {
            model.configure(it)
            screen = Screen.Terminal
        }
    }
    val mac = host
    if (mac == null || current == null) {
        setup(null)
        return
    }
    val place by current.place.collectAsStateWithLifecycle()
    // Pages draw over the terminal, which keeps its place underneath.
    Box(Modifier.fillMaxSize()) {
        FloatingHost {
            key(current) {
                Crossfade(place == null, animationSpec = Motion.effect(), label = "home") { home ->
                    if (home) {
                        LaunchedEffect(Unit) { program = null; programId = null }
                        HomeScreen(current, mac, model, unlock, onSettings = { screen = Screen.Settings }, onExit = onExit)
                    } else {
                        TerminalScreen(
                            current,
                            mac,
                            model,
                            unlock,
                            onProgram = { command, id -> program = command; programId = id },
                            onSessions = current::detach,
                            onSettings = { screen = Screen.Settings },
                            onEditKeys = { screen = Screen.Profile(model.keyBar.bar.value.refFor(program, programId), Screen.Terminal) },
                            onExit = onExit,
                        )
                    }
                }
            }
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
                    programId = programId,
                    gateway = mux?.let { m -> "${m.name} ${m.version}" + (mac.machine?.let { " on $it" } ?: "") },
                    onBack = { screen = Screen.Terminal },
                    onEditHost = { screen = Screen.Setup },
                    onLook = { screen = Screen.Look },
                    onKeyBars = { screen = Screen.KeyBars },
                )
                Screen.Setup -> setup { screen = Screen.Settings }
                Screen.Look -> LookScreen(model.settings, onBack = { screen = Screen.Settings })
                Screen.KeyBars -> KeyBarsScreen(model.keyBar, program, programId, onBack = { screen = Screen.Settings }) {
                    screen = Screen.Profile(it, Screen.KeyBars)
                }
                is Screen.Profile -> ProfileScreen(
                    store = model.keyBar,
                    ref = s.ref,
                    program = program,
                    programId = programId,
                    mux = mux,
                    onBack = { screen = s.back },
                    onOpen = { screen = s.copy(ref = it) },
                )
            }
        }
    }
}

/**
 * The session's terminals: tabs, the shown window's panes to swipe between
 * (each its terminal, or its mobile view), the composer, and the cards of
 * every pane. What's shown is the phone's own; the Mac follows. A terminal is
 * typed into straight from the keyboard, which a tap on it brings up; a
 * mobile view is written for in the composer's box. While the connection
 * to [host] is down, it says so, and how it's coming back. [onSessions]
 * leaves the session for the Mac's sessions.
 */
@Composable
private fun TerminalScreen(
    session: Session,
    host: Host,
    model: AppModel,
    unlock: (onDone: (Boolean) -> Unit) -> Unit,
    onProgram: (command: String?, program: String?) -> Unit,
    onSessions: () -> Unit,
    onSettings: () -> Unit,
    onEditKeys: () -> Unit,
    onExit: () -> Unit,
) {
    val status by session.status.collectAsStateWithLifecycle()
    val place by session.place.collectAsStateWithLifecycle()
    val attached by session.state.collectAsStateWithLifecycle()
    val state = attached?.takeIf { it.session == place?.session } // not the last session's, on the way into another
    val mux by session.mux.collectAsStateWithLifecycle()
    val listed by session.sessions.collectAsStateWithLifecycle()
    val terminals by session.terminals.collectAsStateWithLifecycle()
    val viewing by session.viewing.collectAsStateWithLifecycle()
    val chosen by session.chosen.collectAsStateWithLifecycle()
    val bar by model.keyBar.bar.collectAsStateWithLifecycle()
    val windows = state?.windows.orEmpty()
    val window = windows.firstOrNull { it.id == viewing } ?: windows.firstOrNull { it.active }
    val pane = window?.shownPane(chosen)
    LaunchedEffect(pane?.command, pane?.program) { onProgram(pane?.command, pane?.program) }
    // A notification tapped shows its pane; until then, the one shown isn't what's looked at.
    val opening by model.opening.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val looking = lifecycle.isAtLeast(Lifecycle.State.STARTED) && opening == null
    LaunchedEffect(pane?.id, looking) { if (looking) pane?.let { model.seen(it.id) } }
    LaunchedEffect(opening, windows) {
        val id = opening ?: return@LaunchedEffect
        val w = windows.firstOrNull { w -> w.panes.any { it.id == id } } ?: return@LaunchedEffect
        model.opening.value = null
        session.view(w)
        if (w.activePane()?.id != id) session.show(w, w.panes.first { it.id == id })
    }
    val haptics = haptics()
    val focusManager = LocalFocusManager.current

    // Text the gateway never confirmed comes back into the box; it's felt, as it may not be seen.
    LaunchedEffect(session) { session.undelivered.collect { haptics.reject() } }

    // Panes whose program has a mobile view show it while it's on: the shown pane's, and its
    // neighbours', so a swipe brings theirs in already loaded.
    val readings by session.readings.collectAsStateWithLifecycle()
    val mobileViewOn by model.settings.mobileView.flow.collectAsStateWithLifecycle()
    val nearby = window?.panes?.sortedBy { it.index }?.let { panes ->
        val i = panes.indexOfFirst { it.id == pane?.id }
        if (i < 0) emptyList() else listOf(-1, 0, 1).map { panes[(i + it).mod(panes.size)] }.distinct()
    }.orEmpty()
    val mobileViews = if (mobileViewOn) nearby.filter { it.view }.map { it.id }.toSet() else emptySet()
    LaunchedEffect(mobileViews) { session.read(mobileViews) }
    val mobileView = pane != null && pane.id in mobileViews

    fun actionsOf(p: Pane): PaneActions {
        val program = Programs.of(p.program)
        val profile = bar.profileFor(p.command, p.program)
        return PaneActions(session, p, program, profile.clear ?: program.clearKeys, profile.stop, profile.sendKeys(), profile.returnKeys(program))
    }
    val actions = remember(session, pane, bar) { pane?.let(::actionsOf) }
    val activity = activityOf(pane)
    val screen = rememberPaneScreen(
        pane?.id,
        pane?.let { terminals[it.id] },
        actions?.program ?: Programs.of(null),
        asks = mobileView && activity != Activity.Working, // a prompt while the program works isn't one to answer
        activity,
    )
    // A message the program puts back in its line stays taken back, whatever the line holds next, till the program records more.
    val held = screen.line?.text
    LaunchedEffect(pane?.id, held, mobileView) { if (mobileView && pane != null) session.takenBack(pane.id, held) }
    var nudged by remember(pane?.id) { mutableLongStateOf(0L) }
    // A message asked back by a command (/rewind) is found in what it opens, up to where the user confirms.
    var seeking by remember(pane?.id) { mutableStateOf<Seeking?>(null) }
    var editing by remember(pane?.id) { mutableStateOf<String?>(null) } // the message walked to, to edit
    LaunchedEffect(seeking) {
        delay(if (seeking?.back == true) SEEKS_BACK_MS else SEEKS_MS) // since the last keys
        seeking = null
    }
    LaunchedEffect(screen.prompt, seeking) {
        val s = seeking ?: return@LaunchedEffect
        val p = screen.prompt ?: return@LaunchedEffect
        if (p == s.from) return@LaunchedEffect // what the last keys went from: they haven't shown yet
        val list = s.list
        when (val walk = actions?.program?.walk(p, s.said)) {
            null -> Unit // something between
            is Walk.Listed -> {
                // Straight to where the conversation has it, and picked, which only asks; once back from asking about another, close by.
                if (list != null && !s.back) return@LaunchedEffect // on the way to what a pick asks
                val at = nearby(s.newer, walk.last).firstOrNull { it !in s.tried }
                if (at == null) {
                    seeking = null // the list hasn't it: the user picks
                    return@LaunchedEffect
                }
                actions.keys(walk.moves(at - walk.back) + walk.pick)
                seeking = s.copy(list = list ?: walk, tried = s.tried + at, from = p, back = false)
            }
            is Walk.Picked -> {
                if (list == null || s.back) return@LaunchedEffect // not one this asked, or on the way back
                if (walk.on) {
                    editing = s.id
                    seeking = null
                    return@LaunchedEffect
                }
                // Not it (the list may count a message differently): back to the list, keys alone, since
                // what comes right after Esc can be taken with it (alt and a key), and then Enter would answer this.
                actions.keys(walk.back)
                seeking = if (nearby(s.newer, list.last).any { it !in s.tried }) s.copy(from = p, back = true) else null
            }
        }
    }
    // What a prompt did shows in the line it leaves, a moment after it goes, whoever answered it: a rewind restored at the Mac, say.
    var asked by remember(pane?.id) { mutableStateOf<Prompt?>(null) }
    var gone by remember(pane?.id) { mutableLongStateOf(0L) }
    LaunchedEffect(screen.prompt, held) {
        val p = screen.prompt
        val was = asked
        when {
            p != null -> { asked = p; gone = 0L }
            was == null -> Unit
            gone == 0L -> gone = System.currentTimeMillis()
        }
        if (p == null && was != null && held != null && System.currentTimeMillis() - gone < SETTLES_MS) {
            actions?.settled(was, screen.line, editing)
            asked = null
            editing = null
        }
    }

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

        val machine = host.called
        val address = if (host.dev) "development transport" else "${host.user}@${host.address}:${host.port}"
        TabBar(
            windows = windows,
            selected = window?.id,
            status = status,
            machine = machine,
            address = address,
            session = listOfNotNull(state?.name, mux?.let { "${it.name} ${it.version}" }).joinToString(" in ").ifEmpty { null },
            onSelect = { session.view(it) },
            onNew = { session.newWindow() },
            onRename = { renaming = it },
            onClose = { closing.close(it, null) },
            onRetry = session::retry,
            onSessions = onSessions,
            onSettings = onSettings,
            onExit = onExit,
        )

        // Down a moment, as across a network change, it isn't worth saying; down longer, what was shown dims beneath how it's coming back.
        val down = status == Status.Connecting || status is Status.Disconnected
        var saysDown by remember { mutableStateOf(false) }
        LaunchedEffect(down) {
            if (down) delay(DOWN_UNSAID_MS)
            saysDown = down
        }
        val reconnecting = saysDown && state != null
        val lastDown = rememberLast(status.takeIf { down })
        AnimatedVisibility(
            reconnecting,
            enter = fadeIn(Motion.effect()) + expandVertically(Motion.spatial()),
            exit = fadeOut(Motion.effect()) + shrinkVertically(Motion.spatial()),
        ) {
            lastDown?.let { Reconnecting(it, machine, session::retry) }
        }
        val shown by animateFloatAsState(if (reconnecting) 0.45f else 1f, Motion.effect(), label = "down")

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
                    machine = machine,
                    unlockSeconds = model.unlockSeconds.value,
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
        var recalled by remember(pane?.id) { mutableStateOf<Recalled?>(null) } // the message last asked back to edit
        val typing = remember { Typing() }
        var paneBounds by remember { mutableStateOf(Rect.Zero) }
        var position by remember { mutableFloatStateOf(0f) }
        var areaHeight by remember { mutableIntStateOf(0) }
        val settleHeight by rememberSettleHeight { areaHeight }

        @Composable
        fun Page(p: Pane?, current: Boolean, swipe: Swipe?) = PanePage(
            actions = remember(session, p, bar) { p?.let(::actionsOf) },
            terminal = p?.let { terminals[it.id] },
            reading = p?.takeIf { it.id in mobileViews }?.let { readings[it.id] ?: Reading() },
            current = current,
            swipe = swipe,
            settleHeight = settleHeight,
            covered = overview.covering,
            prompt = screen.prompt,
            line = { screen.line },
            status = screen.status,
            asked = screen.asked,
            nudged = nudged,
            seeking = seeking != null,
            onGridSize = { cols, rows, ahead -> session.start(cols, rows); session.resize(cols, rows, ahead) },
            onView = { view = it },
            onTap = if (current && p != null && p.id !in mobileViews) typing::start else null,
            onPrompt = { seeking = null }, // the user's own hand on it
            held = editing,
            onRecalled = { e, listed ->
                recalled = Recalled(e.text, System.nanoTime())
                val newer = readings[p?.id]?.shown.orEmpty().dropWhile { it.id != e.id }.drop(1)
                if (listed) seeking = Seeking(e.id, e.text, newer = newer.count { it is Entry.Said && it.by == Party.User })
            },
            tab = window?.label(),
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
                            .onGloballyPositioned { paneBounds = it.rootRect() }
                            .graphicsLayer { alpha = shown },
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
                        val first by animateFloatAsState(if (state == null) 1f else 0f, Motion.effect(), label = "connecting")
                        val opening = listed?.muxes?.firstOrNull { it.name == place?.mux }?.sessions?.firstOrNull { it.id == place?.session }?.name ?: "the session"
                        if (first > 0f) Connecting(status, machine, address, session::retry, Modifier.graphicsLayer { alpha = first }, opening = opening)
                        if (window != null && pane != null) {
                            Row(
                                Modifier.align(Alignment.TopEnd).graphicsLayer { alpha = 1f - overview.zoom.value },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AnimatedVisibility(
                                    pane.view,
                                    enter = fadeIn(Motion.effect()) + expandHorizontally(Motion.spatial()),
                                    exit = fadeOut(Motion.effect()) + shrinkHorizontally(Motion.spatial()),
                                ) {
                                    ViewToggle(mobile = mobileView) {
                                        haptics.toggle(!mobileView)
                                        model.settings.mobileView.set(!mobileView)
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
                        // A menu the program's waiting on would take what's typed as picks, and Enter as its choice: what's sent waits, unless it's for writing in.
                        val holding = screen.prompt?.takeIf { mobileView && activity == Activity.Waiting }?.options?.none { it.current && it.muted && it.scroll == 0 } == true
                        val uploads by model.uploads.items.collectAsStateWithLifecycle()
                        val uploadDir by model.uploads.dir.collectAsStateWithLifecycle()
                        Composer(
                            state = model.drafts.of(pane.id),
                            program = pane.command,
                            keys = bar.profileFor(pane.command, pane.program).keys,
                            connected = status is Status.Connected,
                            line = screen.line.takeIf { seeking == null }, // what's typed to ask a message back isn't the user's
                            attachments = Attachments(
                                uploads = remember(uploads, pane.id) { uploads.filter { it.pane == pane.id } },
                                dir = uploadDir,
                                thumbnail = model.uploads::thumbnail,
                                onAttach = { uris -> model.uploads.add(session, pane.id, uris) },
                                onDismiss = model.uploads::dismiss,
                            ),
                            onClear = if (!actions.clears) null else {
                                {
                                    screen.line?.let { actions.clear(it, unseen = mobileView) }
                                    screen.line = null // gone at once; the next read says what's there now
                                }
                            },
                            onKey = {
                                if (!mobileView) view?.scrollToBottom()
                                actions.keys(it.keys, it.bindings)
                            },
                            typing = if (mobileView) null else typing,
                            sendKeys = actions.sendKeys,
                            returnKeys = actions.returnKeys,
                            commands = actions.program.commands,
                            onAcceptSuggestion = actions.acceptSuggestion,
                            recalled = recalled,
                            stoppable = actions.busy,
                            working = activity?.let { it == Activity.Working } ?: actions.busy,
                            queues = activity == Activity.Working && actions.program.queues,
                            holds = holding,
                            onType = { text ->
                                view?.scrollToBottom()
                                if (screen.line != null) view?.predict(text) // at a prompt, which echoes
                                actions.type(text)
                            },
                            onSend = send@{ text, enter ->
                                if (holding) {
                                    haptics.reject()
                                    nudged = System.nanoTime()
                                    return@send false
                                }
                                // The box replaces whatever the app's line holds.
                                val known = screen.line
                                actions.send(text, enter, known, unseen = mobileView).also { sent ->
                                    if (sent && '\n' !in text && known?.text.isNullOrEmpty()) view?.predict(text)
                                }
                            },
                            onEnter = {
                                if (mobileView) {
                                    actions.enter()
                                } else {
                                    view?.scrollToBottom()
                                    actions.press()
                                }
                            },
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

/** How long the connection is down before the screen says so. */
internal const val DOWN_UNSAID_MS = 1_500L

/** How long a message asked back is looked for in what its command opens, from the last keys sent. */
private const val SEEKS_MS = 10_000L

/** Or back in its list from another's confirmation: the list can't be read while Claude says the wheel sends arrows, for some seconds. */
private const val SEEKS_BACK_MS = 20_000L

/**
 * A message asked back, looked for in the list its command opens: [id] and
 * what it [said], with [newer] of the user's messages after it. The [list]
 * as it opened; the places in it picked to see what they ask, the latest
 * last ([tried]); the prompt the last keys went [from]; and whether they went
 * [back] to the list, from what picking another asked.
 */
private data class Seeking(
    val id: String,
    val said: String,
    val newer: Int,
    val list: Walk.Listed? = null,
    val tried: List<Int> = emptyList(),
    val from: Prompt? = null,
    val back: Boolean = false,
)

/** Keys that move the list's cursor [by] messages back (forward, if negative). */
private fun Walk.Listed.moves(by: Int): List<String> = List(abs(by)) { if (by > 0) up else down }.flatten()

/** Where to look for a message [at] in a list going [last] back: there, then a step either side, then two, and so on, up to [NEARBY]. */
internal fun nearby(at: Int, last: Int): List<Int> =
    (0..NEARBY).flatMap { d -> if (d == 0) listOf(at) else listOf(at - d, at + d) }.filter { it in 0..last }

/** How far from where the conversation has a message the list it's asked back from is looked through. */
private const val NEARBY = 3

/** How soon after a prompt goes the line it leaves says what answering it did. */
private const val SETTLES_MS = 3_000L
