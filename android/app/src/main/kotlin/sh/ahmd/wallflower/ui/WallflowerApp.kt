package sh.ahmd.wallflower.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import sh.ahmd.wallflower.AppModel
import sh.ahmd.wallflower.protocol.Keys
import sh.ahmd.wallflower.protocol.Kill
import sh.ahmd.wallflower.protocol.Pane
import sh.ahmd.wallflower.protocol.Rename
import sh.ahmd.wallflower.protocol.Split
import sh.ahmd.wallflower.protocol.Window
import sh.ahmd.wallflower.session.Blocked
import sh.ahmd.wallflower.session.Conversation
import sh.ahmd.wallflower.session.Session
import sh.ahmd.wallflower.session.Session.Companion.shownPane
import sh.ahmd.wallflower.session.Status
import sh.ahmd.wallflower.terminal.InputLine
import sh.ahmd.wallflower.terminal.Terminal
import sh.ahmd.wallflower.terminal.TerminalView

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
                    confirm = model.confirm,
                    appearance = model.appearance,
                    conversations = model.conversationViews,
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

@OptIn(ExperimentalLayoutApi::class)
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

    // A pane whose program records its conversation can show that instead of its terminal.
    val conversations by session.conversations.collectAsStateWithLifecycle()
    val offered by model.conversationViews.offered.collectAsStateWithLifecycle()
    val conversing by model.conversationViews.panes.collectAsStateWithLifecycle()
    val converses = offered && pane != null && pane.transcript.isNotEmpty()
    // The shown pane's neighbours too, so a swipe brings theirs in already loaded.
    val nearby = window?.panes?.sortedBy { it.index }?.let { panes ->
        val i = panes.indexOfFirst { it.id == pane?.id }
        if (i < 0) emptyList() else listOf(-1, 0, 1).map { panes[(i + it).mod(panes.size)] }.distinct()
    }.orEmpty()
    val following = if (offered) nearby.filter { it.transcript.isNotEmpty() && it.id in conversing }.map { it.id }.toSet() else emptySet()
    LaunchedEffect(following) { session.follow(following) }

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
        fun kill(w: Window, p: Pane?) {
            haptics.confirm()
            session.send(if (p != null) Kill(pane = p.id) else Kill(window = w.id))
        }
        fun close(w: Window, p: Pane?) {
            val wholeWindow = p == null || w.panes.size == 1
            val ask = if (wholeWindow) model.confirm.windows.value else model.confirm.panes.value
            if (ask) closing = w to p else kill(w, p)
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
                        kill(w, null)
                    } else {
                        if (never) model.confirm.askForPanes(false)
                        kill(w, p)
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
        var lastBlocked by remember { mutableStateOf<Blocked?>(null) }
        if (blocked != null) lastBlocked = blocked
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

        // Output reaches whichever views show the pane, and rereads the app's input line.
        val views = remember { Views() }
        val lineTicks = remember { Channel<Unit>(Channel.CONFLATED) }
        val shownId by rememberUpdatedState(pane?.id)
        DisposableEffect(session) {
            session.onOutput = { id ->
                views.frame(id)
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

        // The pane zooms out into its card among the window's others; a card zooms back in.
        var overview by remember { mutableStateOf(false) }
        var zoomed by remember { mutableStateOf<String?>(null) } // the pane whose card it zooms into or out of
        val zoom = remember { Animatable(0f) } // 0 the pane, 1 the cards
        LaunchedEffect(overview) { zoom.animateTo(if (overview) 1f else 0f, Motion.spatial()) }
        LaunchedEffect(window == null) {
            if (window == null) {
                overview = false
                zoom.snapTo(0f)
            }
        }
        fun showCards() {
            zoomed = pane?.id
            overview = true
            focusManager.clearFocus()
        }
        BackHandler(enabled = overview) {
            zoomed = pane?.id
            overview = false
        }
        val cardsUp by remember { derivedStateOf { overview || zoom.value > 0f } }
        val covered by remember { derivedStateOf { overview && zoom.value == 1f } } // only the cards show
        val cards = remember { mutableStateMapOf<String, Rect>() } // each card's terminal, in root pixels
        var paneBounds by remember { mutableStateOf(Rect.Zero) }
        var position by remember { mutableFloatStateOf(0f) }

        // The keyboard says where it'll stop as it starts to move: terminals learn how tall they'll be.
        var areaHeight by remember { mutableIntStateOf(0) }
        var settleHeight by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        val ime = WindowInsets.ime
        val imeTarget = WindowInsets.imeAnimationTarget
        val navigation = WindowInsets.navigationBars
        LaunchedEffect(Unit) {
            var lastTarget = -1
            snapshotFlow { imeTarget.getBottom(density) to ime.getBottom(density) }.collect { (to, now) ->
                fun above(inset: Int) = maxOf(inset - navigation.getBottom(density), 0) // imePadding's share of it
                settleHeight = when {
                    to == now -> 0
                    settleHeight == 0 || to != lastTarget -> areaHeight + above(now) - above(to)
                    else -> settleHeight
                }
                lastTarget = to
            }
        }

        /** A pane's terminal, and its conversation over it while that's on. */
        @Composable
        fun Page(p: Pane, current: Boolean, swipe: Swipe?) = Box(Modifier.fillMaxSize()) {
            var mine by remember { mutableStateOf<TerminalView?>(null) }
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(horizontal = PAGE_PADDING).clipToBounds(),
                factory = { ctx ->
                    TerminalView(ctx).apply {
                        onGridSize = { cols, rows, ahead -> session.start(cols, rows); session.resize(cols, rows, ahead) }
                        mine = this
                    }
                },
                update = {
                    it.terminal = terminals[p.id]
                    it.settleHeight = settleHeight
                    it.paused = covered
                    it.onSwipe = swipe?.let { s -> s::drag }
                    it.onSwipeEnd = swipe?.let { s -> s::end }
                    it.onAppScroll = { up, wheel ->
                        if (wheel != null) session.input(p.id, wheel) else session.send(Keys(p.id, listOf(if (up) "Up" else "Down")))
                    }
                    if (current) view = it
                },
            )
            DisposableEffect(mine, p.id) {
                val v = mine
                if (v != null) views.add(p.id, v)
                onDispose { if (v != null) views.remove(p.id, v) }
            }
            val conversation = conversations[p.id]?.takeIf { p.id in following } ?: if (p.id in following) Conversation() else null
            var last by remember { mutableStateOf(Conversation()) }
            if (conversation != null) last = conversation
            androidx.compose.animation.AnimatedVisibility(conversation != null, enter = fadeIn(Motion.effect()), exit = fadeOut(Motion.effect())) {
                ConversationView(last, swipe = swipe)
            }
        }

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
                                .graphicsLayer { zoomInto(zoom.value, zoomed?.let { cards[it] }, paneBounds, view) }
                                .background(Palette.bg0),
                        ) {
                            if (window == null || pane == null) {
                                Page(Pane("", 0, false, 0, 0, 0, 0, command = ""), current = true, swipe = null) // measures the grid until there are panes
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
                                            w.panes.sortedBy { it.index },
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
                                Modifier.align(Alignment.TopEnd).graphicsLayer { alpha = 1f - zoom.value },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AnimatedVisibility(
                                    converses,
                                    enter = fadeIn(Motion.effect()) + expandHorizontally(Motion.spatial()),
                                    exit = fadeOut(Motion.effect()) + shrinkHorizontally(Motion.spatial()),
                                ) {
                                    val on = pane.id in following
                                    ViewToggle(conversation = on) {
                                        haptics.toggle(!on)
                                        model.conversationViews.toggle(pane.id)
                                    }
                                }
                                key(window.id) { PaneDots(window.panes.size, { position }, onClick = ::showCards) }
                            }
                        }
                    }
                },
                {
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
                },
                {
                    if (cardsUp && window != null) {
                        PaneOverview(
                            window = window,
                            shown = pane?.id,
                            zoomed = zoomed,
                            progress = { zoom.value },
                            terminals = terminals,
                            views = views,
                            cards = cards,
                            onPick = { p ->
                                haptics.tick()
                                zoomed = p.id
                                session.show(window, p)
                                overview = false
                            },
                            onNew = {
                                pane?.let { session.send(Split(it.id)) }
                                zoomed = pane?.id
                                overview = false
                            },
                            onClose = { p -> close(window, p) },
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
                p?.place(0, 0, zIndex = if (covered) -1f else 1f)
                c?.place(0, paneHeight)
                o?.place(0, 0, zIndex = 0.5f)
            }
        }
    }
}

/** The views showing each pane, which its output redraws: a pane's own, and its card's while the cards are up. */
private class Views {
    private val byPane = ConcurrentHashMap<String, MutableSet<TerminalView>>()

    fun add(pane: String, view: TerminalView) {
        byPane.computeIfAbsent(pane) { ConcurrentHashMap.newKeySet() }.add(view)
    }

    fun remove(pane: String, view: TerminalView) {
        byPane[pane]?.remove(view)
    }

    fun remove(view: TerminalView) = byPane.values.forEach { it.remove(view) }

    fun frame(pane: String) = byPane[pane]?.forEach { it.requestFrame() }
}

private fun LayoutCoordinates.rootRect() = Rect(positionInRoot(), size.toSize())

/**
 * Draws a pane [p] of the way into its [card]'s terminal view (both in root
 * pixels): its grid ([terminal]'s) scaled onto the card's, which draws the
 * same grid fitted to its width, showing as much as fits around the cursor,
 * as the card does. It shrinks at an even pace (scale on a log curve) and
 * lands just before the end, so the card's view fades in over it exactly.
 * With no card to go to it fades.
 */
private fun GraphicsLayerScope.zoomInto(p: Float, card: Rect?, pane: Rect, terminal: TerminalView?) {
    if (p == 0f) return
    if (card == null || pane.isEmpty) {
        alpha = 1f - p
        return
    }
    val w = size.width
    val h = size.height
    val pad = PAGE_PADDING.toPx()
    val grid = terminal?.gridWidth()?.takeIf { it > 0f } ?: (w - 2 * pad)
    val target = card.translate(-pane.left, -pane.top)
    val g = (p / (1f - HANDOFF)).coerceAtMost(1f).let { 1f - (1f - it) * (1f - it) }
    val window = lerp(Rect(0f, 0f, w, h), target, g) // what shows, on screen
    val scale = (target.width / grid).pow(g)
    val shown = minOf(window.height / scale, h) // of the pane, in its own pixels
    val from = ((terminal?.cursorBottom() ?: h) - shown).coerceIn(0f, h - shown)
    val dx = window.left - lerp(0f, pad, g) * scale
    val radius = CARD_CORNER.toPx() * 4f * g * (1f - g) / scale // rounder midway, square where it lands
    transformOrigin = TransformOrigin(0f, 0f)
    scaleX = scale
    scaleY = scale
    translationX = dx
    translationY = window.top - from * scale
    shape = object : Shape {
        override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
            Outline.Rounded(RoundRect((window.left - dx) / scale, from, (window.right - dx) / scale, from + shown, CornerRadius(radius)))
    }
    clip = true
    alpha = handoff(p)
}

/** How much of a zooming pane still shows: all of it until the last stretch, where the card's view takes over. */
private fun handoff(p: Float) = ((1f - p) / HANDOFF).coerceIn(0f, 1f)

/** How much of the cards show as they gather around the zooming pane. */
private fun cardsIn(p: Float) = ((p - 0.2f) / 0.8f).coerceIn(0f, 1f)

/**
 * Every pane of a window as a card with its live contents, in tmux's order,
 * then one to open a new pane. Tap a card to show it, × to close it. Cards
 * fill as many columns as the screen is wide. They come in with [progress]
 * as the [zoomed] pane shrinks into its card, which says where it is in
 * [cards].
 */
@Composable
private fun PaneOverview(
    window: Window,
    shown: String?,
    zoomed: String?,
    progress: () -> Float,
    terminals: Map<String, Terminal>,
    views: Views,
    cards: MutableMap<String, Rect>,
    onPick: (Pane) -> Unit,
    onNew: () -> Unit,
    onClose: (Pane) -> Unit,
) {
    val panes = window.panes.sortedBy { it.index }
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = panes.indexOfFirst { it.id == shown }.coerceAtLeast(0))
    LazyVerticalGrid(
        state = grid,
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = Modifier.fillMaxSize().drawBehind { drawRect(Palette.bg0, alpha = progress()) },
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(panes, key = { it.id }) { p ->
            PaneCard(
                p,
                shown = p.id == shown,
                zoomed = p.id == zoomed,
                progress = progress,
                terminal = terminals[p.id],
                views = views,
                cards = cards,
                onClick = { onPick(p) },
                onClose = { onClose(p) },
                modifier = Modifier.animateItem(placementSpec = Motion.spatial()),
            )
        }
        item(key = "new") { NewPaneCard(Modifier.animateItem(placementSpec = Motion.spatial()).cardIn(progress, zoomed = false), onNew) }
    }
}

/** Fades a card in with the zoom, from a little smaller unless it's the one being zoomed into. */
private fun Modifier.cardIn(progress: () -> Float, zoomed: Boolean) = graphicsLayer {
    val p = progress()
    alpha = cardsIn(p)
    if (!zoomed) {
        val s = lerp(0.94f, 1f, p)
        scaleX = s
        scaleY = s
    }
}

@Composable
private fun PaneCard(
    pane: Pane,
    shown: Boolean,
    zoomed: Boolean,
    progress: () -> Float,
    terminal: Terminal?,
    views: Views,
    cards: MutableMap<String, Rect>,
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(CARD_CORNER)
    DisposableEffect(pane.id) { onDispose { cards.remove(pane.id) } }
    Column(
        modifier
            .cardIn(progress, zoomed)
            .clip(shape)
            .background(Palette.bg1)
            .border(2.dp, if (shown) Palette.orange else Palette.bg1, shape)
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${pane.index}", color = if (shown) Palette.orange else Palette.dim, fontFamily = words, fontSize = wordSize(12.sp, 14.sp), fontWeight = FontWeight.Bold)
            Text(" ${pane.command}", color = Palette.fg, fontFamily = words, fontSize = wordSize(12.sp, 14.sp), maxLines = 1, modifier = Modifier.weight(1f))
            if (pane.agent.isNotEmpty()) AgentDot(pane.agent)
            Box(Modifier.clip(CircleShape).clickable(onClick = onClose).padding(10.dp)) { GlyphIcon(Glyph.Close, Palette.grey, size = 14.dp) }
        }
        // The pane as its own terminal draws it, shrunk to the card's width.
        Box(Modifier.fillMaxWidth().aspectRatio(CARD_RATIO).background(Palette.bg0)) {
            // The grid reuses cards' views for other panes as it scrolls, so a view
            // registers for its pane's output whenever it's given one.
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .onGloballyPositioned { c ->
                        // Where a zooming pane's terminal lands.
                        val r = c.rootRect()
                        if (cards[pane.id] != r) cards[pane.id] = r
                    }
                    .clipToBounds()
                    .graphicsLayer { alpha = if (zoomed) 1f - handoff(progress()) else 1f },
                factory = { ctx -> TerminalView(ctx) },
                onReset = { v -> views.remove(v) },
                onRelease = { v -> views.remove(v) },
                update = { v ->
                    v.terminal = terminal
                    views.remove(v)
                    views.add(pane.id, v)
                },
            )
            Box(Modifier.matchParentSize().clickable(onClick = onClick)) // the card takes the tap, not the terminal
        }
    }
}

@Composable
private fun NewPaneCard(modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(CARD_CORNER))
            .background(Palette.bg1)
            .clickable(onClick = onClick),
    ) {
        Text("new pane", color = Palette.grey, fontFamily = words, fontSize = wordSize(12.sp, 14.sp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
        Box(Modifier.fillMaxWidth().aspectRatio(CARD_RATIO), contentAlignment = Alignment.Center) {
            GlyphIcon(Glyph.Plus, Palette.grey, size = 28.dp)
        }
    }
}

/** Switches a pane between its terminal and its conversation. */
@Composable
private fun ViewToggle(conversation: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.bg1.copy(alpha = 0.85f))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Crossfade(conversation, animationSpec = Motion.effect(), label = "view") {
            GlyphIcon(if (it) Glyph.Terminal else Glyph.Chat, Palette.fg, size = 14.dp)
        }
    }
}

/**
 * Which of the window's [count] panes is showing: swipe the terminal sideways
 * for the others, tap for all of them. The light follows [position] as panes
 * move, stretching from dot to dot, and glides when the pane changes at once.
 */
@Composable
private fun PaneDots(count: Int, position: () -> Float, onClick: () -> Unit) {
    val n = count.coerceAtLeast(1).toFloat()
    val at = remember { Animatable(position()) }
    LaunchedEffect(count) {
        snapshotFlow { position() }.collectLatest { to ->
            val from = at.value
            var d = (to - from).mod(n)
            if (d > n / 2f) d -= n
            if (abs(d) <= 0.5f) {
                at.snapTo(from + d) // following a swipe
            } else {
                at.snapTo(from.mod(n))
                at.animateTo(to.mod(n), Motion.spatial())
            }
        }
    }
    val dot = 6.dp
    val gap = 5.dp
    Canvas(
        Modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.bg1.copy(alpha = 0.85f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .size(width = dot * count + gap * (count - 1).coerceAtLeast(0), height = dot),
    ) {
        val d = dot.toPx()
        val step = d + gap.toPx()
        val r = CornerRadius(d / 2)
        for (i in 0 until count) drawRoundRect(Palette.bg3, Offset(i * step, 0f), Size(d, d), r)
        val pos = at.value.mod(n)
        val i = floor(pos).toInt().coerceIn(0, count - 1)
        val f = pos - floor(pos)
        if (i == count - 1 && f > 0f) {
            // Round the end: the light leaves the last dot as it comes back at the first.
            drawRoundRect(Palette.fg, Offset(i * step, 0f), Size(d, d), r, alpha = 1f - f)
            drawRoundRect(Palette.fg, Offset(0f, 0f), Size(d, d), r, alpha = f)
        } else {
            val left = i * step + step * (f * 2f - 1f).coerceIn(0f, 1f)
            val right = i * step + d + step * (f * 2f).coerceIn(0f, 1f)
            drawRoundRect(Palette.fg, Offset(left, 0f), Size(right - left, d), r)
        }
    }
}

/**
 * The tabs (tmux windows), with new tab first and the connection last. It's
 * local: it scrolls natively and a tap switches immediately, tmux learns
 * afterwards. The highlight glides to the tab chosen. Holding a tab offers
 * rename and close.
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
    val haptics = haptics()
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
    val at = remember { Animatable(index.coerceAtLeast(0).toFloat()) }
    LaunchedEffect(index) { if (index >= 0) at.animateTo(index.toFloat(), Motion.spatial()) }
    var menu by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        BarButton(Glyph.Plus, Palette.fg, Modifier.padding(start = 6.dp), onClick = onNew)
        LazyRow(
            state = list,
            modifier = Modifier
                .weight(1f)
                .clipToBounds()
                .drawBehind {
                    // The tabs' backgrounds, where the list put them, and the highlight between them.
                    val info = list.layoutInfo
                    val start = -info.viewportStartOffset.toFloat()
                    val radius = CornerRadius(8.dp.toPx())
                    fun span(i: Int) = info.visibleItemsInfo.firstOrNull { it.index == i }?.let { (start + it.offset) to it.size.toFloat() }
                    for (item in info.visibleItemsInfo) {
                        drawRoundRect(Palette.bg1, Offset(start + item.offset, 0f), Size(item.size.toFloat(), size.height), radius)
                    }
                    if (index < 0) return@drawBehind
                    val v = at.value
                    val a = span(floor(v).toInt())
                    val b = span(ceil(v).toInt())
                    val (x, w) = if (a != null && b != null) {
                        val f = v - floor(v)
                        lerp(a.first, b.first, f) to lerp(a.second, b.second, f)
                    } else {
                        a ?: b ?: return@drawBehind
                    }
                    drawRoundRect(Palette.bg3, Offset(x, 0f), Size(w, size.height), radius)
                },
            contentPadding = PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(windows, key = { it.id }) { w ->
                val on = w.id == selected
                val number by animateColorAsState(if (on) Palette.orange else Palette.dim, Motion.effect(), label = "tab")
                val name by animateColorAsState(if (on) Palette.fg else Palette.grey, Motion.effect(), label = "tab")
                val agent = w.panes.map { it.agent }.firstOrNull { it.isNotEmpty() }
                Box {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .combinedClickable(
                                onClick = {
                                    if (!on) haptics.tick()
                                    onSelect(w)
                                },
                                onLongClick = {
                                    haptics.hold()
                                    menu = w.id
                                },
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${w.index}", color = number, fontFamily = words, fontSize = wordSize(12.sp, 14.sp), fontWeight = FontWeight.Bold)
                        Text(" ${w.name}", color = name, fontFamily = words, fontSize = wordSize(12.sp, 14.sp), maxLines = 1)
                        if (agent != null) AgentDot(agent, Modifier.padding(start = 6.dp))
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

/** An agent's state as a colored dot, which breathes while it works. */
@Composable
private fun AgentDot(state: String, modifier: Modifier = Modifier) {
    val color by animateColorAsState(agentColor(state), Motion.effect(), label = "agent")
    val breath = if (state == "working") {
        rememberInfiniteTransition(label = "agent").animateFloat(1f, 0.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "breath")
    } else {
        null
    }
    Box(modifier.size(6.dp).graphicsLayer { alpha = breath?.value ?: 1f }.clip(CircleShape).background(color))
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
    val (target, what) = when (status) {
        is Status.Connected -> Palette.green to "connected · tmux ${status.tmux}"
        Status.Connecting -> Palette.yellow to "connecting…"
        is Status.Disconnected -> Palette.red to "reconnecting: ${status.reason}"
        is Status.NeedsUser -> Palette.orange to "waiting for you"
    }
    val color by animateColorAsState(target, Motion.effect(), label = "connection")
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(end = 6.dp)) {
        BarButton(Glyph.Power, color) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(what, color = target, fontFamily = words, fontSize = wordSize(12.sp, 14.sp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            DropdownMenuItem(text = { Text("settings") }, onClick = { open = false; onSettings() })
            DropdownMenuItem(text = { Text("disconnect", color = Palette.red) }, onClick = { open = false; onExit() })
        }
    }
}

private const val LINE_EVERY_MS = 80L

/** Cards are a phone screen's shape, like a browser's tab grid. */
private const val CARD_RATIO = 0.75f

private val CARD_CORNER = 14.dp

/** The last stretch of a zoom, where the card's own view fades in over the pane. */
private const val HANDOFF = 0.06f

/** Either side of a pane's terminal. */
private val PAGE_PADDING = 4.dp

private fun agentColor(state: String): Color = when (state) {
    "waiting" -> Palette.orange
    "working" -> Palette.blue
    "done" -> Palette.green
    "error" -> Palette.red
    else -> Palette.grey
}
