package sh.ahmd.wallflower.ui.mobile

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import sh.ahmd.wallflower.program.Aside
import sh.ahmd.wallflower.program.Asked
import sh.ahmd.wallflower.program.Mode
import sh.ahmd.wallflower.read.Activity
import sh.ahmd.wallflower.read.Entry
import sh.ahmd.wallflower.read.Reading
import sh.ahmd.wallflower.read.items
import sh.ahmd.wallflower.screen.Prompt
import sh.ahmd.wallflower.ui.Glyph
import sh.ahmd.wallflower.ui.GlyphIcon
import sh.ahmd.wallflower.ui.Motion
import sh.ahmd.wallflower.ui.Palette
import sh.ahmd.wallflower.ui.Swipe
import sh.ahmd.wallflower.ui.Type
import sh.ahmd.wallflower.ui.chrome
import sh.ahmd.wallflower.ui.rememberLast

/**
 * A pane's mobile view in place of its terminal: what each side said, with
 * each run of steps and thinking folded into one line, to open when
 * wanted. The newest is at the bottom, which stays in view as entries come.
 * Below it, while the program works, a line says so; when its screen offers
 * a [prompt] (a permission, a question), that's there to answer, and the keys
 * picking an option go to [onKeys], and the option to [onPicked]. What the
 * program shows [aside] of its line, where keys are its own, is said at the
 * foot, with the keys that put it away. Its [mode] goes beside how full its
 * context is, to pick another of those it goes round ([modes]). Sideways
 * drags go to [swipe], to move between panes as on the terminal. The
 * conversation's [title], if its tab doesn't say it, sits at the top.
 */
@Composable
fun MobileView(
    reading: Reading,
    /** What the program is doing, if it says, and since when (Unix milliseconds). */
    activity: Activity?,
    since: Long,
    /** A picture entry's bytes, asked for when it shows. */
    picture: suspend (String) -> ByteArray?,
    modifier: Modifier = Modifier,
    swipe: Swipe? = null,
    prompt: Prompt? = null,
    /** What the program says it's doing, in its words, while it works. */
    doing: String? = null,
    /** Questions it asked that wait on its screen to be brought up, which [onKeys] does. */
    asked: Asked? = null,
    /** When something sent was held for the prompt to be answered first (System.nanoTime); 0 if it wasn't. */
    nudged: Long = 0,
    /** A message asked back to edit is being found in what the program lists, which isn't the user's to answer yet. */
    seeking: Boolean = false,
    aside: Aside? = null,
    /** Of an answer aside, what's been read of it, as it's scrolled. */
    answer: List<String> = emptyList(),
    mode: Mode? = null,
    modes: List<Mode> = emptyList(),
    onMode: (String) -> Unit = {},
    onKeys: (List<String>) -> Unit = {},
    onPicked: (Prompt, Prompt.Option) -> Unit = { _, _ -> },
    /** Sends the program its compact command, from the context button; null while it can't. */
    onCompact: (() -> Unit)? = null,
    /** Asks for one of the user's messages back, to edit; null while it can't be. */
    onEdit: ((Entry.Said) -> Unit)? = null,
    title: String? = reading.title,
    /** On screen, so what moves moves; a page beside it holds still. */
    live: Boolean = true,
) = CompositionLocalProvider(LocalLive provides live) {
    var dragged by remember { mutableFloatStateOf(0f) }
    Box(
        modifier
            .fillMaxSize()
            .background(Palette.bg0)
            .draggable(
                rememberDraggableState { delta ->
                    dragged += delta
                    swipe?.drag(dragged)
                },
                Orientation.Horizontal,
                enabled = swipe != null,
                onDragStarted = { dragged = 0f },
                onDragStopped = { velocity -> swipe?.end(dragged, velocity) },
            ),
    ) {
        val items = remember(reading.entries, reading.rewound) { items(reading.shown) }
        // A prompt while the program says it's busy is one left on the screen, or a list that looks like one.
        val asking = prompt?.takeIf { activity != Activity.Working }
        // The program says it's waiting a moment after its screen moved on (answered), so for that long it's working.
        var unread by remember { mutableStateOf(false) }
        LaunchedEffect(activity, asking == null) {
            unread = false
            if (activity == Activity.Waiting && asking == null) {
                delay(UNREAD_MS)
                unread = true
            }
        }
        // What waits to be brought up isn't on its way to the screen: it says so at once.
        LaunchedEffect(activity, asked != null) {
            if (activity == Activity.Waiting && asked != null) unread = true
        }
        // Stopped from here, it says so at once, until the program says it's stopped or a few seconds pass.
        val stopping by produceState(false, reading.stopped) {
            val left = STOPPING_MS - (SystemClock.elapsedRealtime() - reading.stopped)
            if (reading.stopped != 0L && left > 0) {
                value = true
                delay(left)
            }
            value = false
        }
        val status = when {
            seeking -> Status.Seeking
            asking != null -> Status.Asking
            activity == Activity.Working -> Status.Working
            // Claude says it waits while its /btw answer is up, which asks nothing: the card says what's there.
            activity == Activity.Waiting && aside != null -> null
            activity == Activity.Waiting -> if (unread) Status.Waiting else Status.Working
            else -> null
        }
        // The card opening and shutting, felt even where the status line leaves with it.
        val feel = rememberFold()
        SideEffect { feel.shown(status == Status.Asking) }
        val feelAside = rememberFold()
        SideEffect { feelAside.shown(aside != null) }
        val list = rememberLazyListState()
        // At the bottom, new entries glide into view; scrolled up to read, the list stays put.
        val atBottom by remember { derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < 8 } }
        val away by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 600 } }
        // Following the newest until the user drags, and again once they've let go at it: not whether the
        // list happens to be at the bottom as things change, which a reply's copy row coming in can move it off.
        var following by remember { mutableStateOf(true) }
        LaunchedEffect(list) {
            list.interactionSource.interactions.collect { if (it is DragInteraction.Start) following = false }
        }
        LaunchedEffect(list) { snapshotFlow { atBottom && !list.isScrollInProgress }.collect { if (it) following = true } }
        LaunchedEffect(items.size, items.lastOrNull(), status, reading.queued.size, aside != null, answer.size) { if (following) list.animateScrollToItem(0) }
        // Editing a message asks the program at the foot, where what it asks comes.
        val edit = onEdit?.let { e ->
            { said: Entry.Said ->
                following = true
                e(said)
            }
        }

        // The mode, and how full the context is: beside the last reply's copy; with no copy to go beside, on their own.
        val footer: (@Composable () -> Unit)? = if (reading.context > 0 || mode != null) {
            { Footer(mode, modes, onMode, reading.context, onCompact) }
        } else {
            null
        }
        // Nothing said yet, a conversation can still have something at its foot: an answer aside, a menu, what's queued.
        val phase = when {
            aside is Aside.Tasks -> Phase.Tasks
            !reading.loaded -> Phase.Reading
            items.isEmpty() && aside == null && status == null && reading.queued.isEmpty() -> Phase.Empty
            else -> Phase.Shown
        }
        Crossfade(phase, animationSpec = Motion.effect(), label = "mobile view") { p ->
            when (p) {
                // The program's list of its tasks takes the conversation's place, as it does the program's screen.
                Phase.Tasks -> (aside as? Aside.Tasks)?.let { TasksPage(it, onKeys, nudged) }
                Phase.Reading -> Hint("reading…")
                // The mode can be picked before the first message.
                Phase.Empty -> Box(Modifier.fillMaxSize()) {
                    Empty(reading.problem)
                    footer?.let { Box(Modifier.align(Alignment.BottomEnd).padding(horizontal = 16.dp, vertical = 12.dp)) { it() } }
                }
                Phase.Shown -> Conversation(
                    items = items,
                    reading = reading,
                    status = status,
                    asking = asking,
                    since = since,
                    doing = if (stopping) "stopping" else doing,
                    asked = asked,
                    nudged = nudged,
                    aside = aside,
                    answer = answer,
                    footer = footer,
                    picture = picture,
                    onKeys = onKeys,
                    onPicked = onPicked,
                    onEdit = edit,
                    feel = feel,
                    feelAside = feelAside,
                    list = list,
                    away = away,
                )
            }
        }
        val shownTitle = rememberLast(title)
        AnimatedVisibility(
            title != null && phase != Phase.Tasks, // the list says what it is
            modifier = Modifier.align(Alignment.TopStart),
            enter = fadeIn(Motion.effect()),
            exit = fadeOut(Motion.effect()),
        ) {
            Text(
                shownTitle.orEmpty(),
                color = Palette.grey,
                style = Type.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp).widthIn(max = 240.dp).chrome().padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

private enum class Phase { Tasks, Reading, Empty, Shown }

/** What's below the conversation: a menu to answer, the program working, or waiting on something unread. */
internal enum class Status { Asking, Working, Waiting, Seeking }

/** How long a stop from the phone says it's stopping before it's for the program to say. */
private const val STOPPING_MS = 3_000L

/** How long the program can say it's waiting without a menu on screen before that's what it is. */
private const val UNREAD_MS = 1_200L

@Composable
private fun Hint(text: String) = Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
    Text(text, color = Palette.grey, style = Type.body, textAlign = TextAlign.Center)
}

/** A conversation with nothing in it yet, to start from the composer; or, with a [problem], why there's nothing to show. */
@Composable
private fun Empty(problem: String?) = Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GlyphIcon(Glyph.Chat, Palette.bg4, size = 30.dp)
        Text(if (problem == null) "a new conversation" else "nothing to show", color = Palette.grey, style = Type.title)
        Text(problem ?: "what you send below starts it", color = Palette.dim, style = Type.body, textAlign = TextAlign.Center)
    }
}
