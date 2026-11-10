package sh.ahmd.wallflower.ui

import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.ahmd.wallflower.read.Activity
import sh.ahmd.wallflower.read.Entry
import sh.ahmd.wallflower.read.Item
import sh.ahmd.wallflower.read.Party
import sh.ahmd.wallflower.read.Reading
import sh.ahmd.wallflower.read.items
import sh.ahmd.wallflower.read.trace
import sh.ahmd.wallflower.screen.Prompt

/**
 * A pane's mobile view in place of its terminal: what each side said, with
 * each run of steps and thinking folded into one line, to open when
 * wanted. The newest is at the bottom, which stays in view as entries come.
 * Below it, while the program works, a line says so; when its screen offers
 * a [prompt] (a permission, a question), that's there to answer, and the keys
 * picking an option go to [onKeys], and the option to [onPicked]. Sideways
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
    /** When something sent was held for the prompt to be answered first (System.nanoTime); 0 if it wasn't. */
    nudged: Long = 0,
    /** A message asked back to edit is being found in what the program lists, which isn't the user's to answer yet. */
    seeking: Boolean = false,
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
            activity == Activity.Waiting -> if (unread) Status.Waiting else Status.Working
            else -> null
        }
        // The card opening and shutting, felt even where the status line leaves with it.
        val feel = rememberFold()
        SideEffect { feel.shown(status == Status.Asking) }
        val list = rememberLazyListState()
        val scope = rememberCoroutineScope()
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
        LaunchedEffect(items.size, items.lastOrNull(), status, reading.queued.size) { if (following) list.animateScrollToItem(0) }
        // Editing a message asks the program at the foot, where what it asks comes.
        val edit = onEdit?.let { e ->
            { said: Entry.Said ->
                following = true
                e(said)
            }
        }

        val phase = when {
            !reading.loaded -> Phase.Reading
            items.isEmpty() -> Phase.Empty
            else -> Phase.Shown
        }
        Crossfade(phase, animationSpec = Motion.effect(), label = "mobile view") { p ->
            when (p) {
                Phase.Reading -> Hint("reading…")
                Phase.Empty -> Empty(reading.problem)
                Phase.Shown -> Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = list,
                        reverseLayout = true,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 48.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        // How full the context is goes beside the last reply's copy; with no copy to go beside, on its own.
                        val newest = items.lastOrNull()
                        val context: (@Composable () -> Unit)? =
                            if (reading.context > 0) ({ ContextButton(reading.context, onCompact) }) else null
                        val besideCopy = newest?.copies(ongoing = status != null) == true
                        if (context != null && !besideCopy) {
                            item(key = "context") {
                                Box(
                                    Modifier.fillMaxWidth().animateItem(fadeInSpec = Motion.effect(), placementSpec = Motion.spatial(), fadeOutSpec = Motion.effect()),
                                    contentAlignment = Alignment.CenterEnd,
                                ) { context() }
                            }
                        }
                        // What waits for the program to take it is below its working, as Claude shows it.
                        reading.queued.asReversed().forEachIndexed { i, text ->
                            item(key = "queued $i $text") {
                                Box(Modifier.animateItem(fadeInSpec = Motion.effect(), placementSpec = Motion.spatial(), fadeOutSpec = Motion.effect())) { Queued(text) }
                            }
                        }
                        if (status != null) {
                            item(key = "status") {
                                // One item that changes between them, so a menu that flickers off and on doesn't leave and come back.
                                var shown by remember { mutableStateOf(asking) }
                                if (asking != null) shown = asking
                                AnimatedContent(
                                    status,
                                    Modifier.animateItem(fadeInSpec = Motion.effect(), placementSpec = Motion.spatial(), fadeOutSpec = Motion.effect()),
                                    transitionSpec = { fadeIn(Motion.effect()) togetherWith fadeOut(Motion.effect()) using SizeTransform(clip = false) },
                                    label = "status",
                                ) { now ->
                                    when (now) {
                                        Status.Asking -> shown?.let { p ->
                                            PromptCard(p, onKeys, Modifier.onSizeChanged { s -> feel.laidOut(s.height) }, nudged) { picked -> onPicked(p, picked) }
                                        }
                                        Status.Working -> Working(since, if (stopping) "stopping" else doing)
                                        Status.Waiting -> Waiting()
                                        Status.Seeking -> Working(0, "finding your message")
                                    }
                                }
                            }
                        }
                        items(items.asReversed(), key = { it.key }, contentType = { it::class }) {
                            Box(Modifier.animateItem(fadeInSpec = Motion.effect(), placementSpec = Motion.spatial(), fadeOutSpec = Motion.effect())) {
                                ItemView(it, picture, ongoing = status != null && it === newest, beside = context.takeIf { _ -> it === newest }, onEdit = edit)
                            }
                        }
                        if (reading.more) item(key = "more") { Note("earlier messages are in the terminal") }
                    }
                    AnimatedVisibility(
                        visible = away,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                        enter = fadeIn(Motion.effect()) + slideInVertically(Motion.spatial()) { it },
                        exit = fadeOut(Motion.effect()) + slideOutVertically(Motion.spatial()) { it },
                    ) {
                        Text(
                            "↓ latest",
                            color = Palette.fg,
                            style = Type.label,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(Palette.bg2)
                                .clickable { scope.launch { list.animateScrollToItem(0) } }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
        val shownTitle = rememberLast(title)
        AnimatedVisibility(
            title != null,
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

private enum class Phase { Reading, Empty, Shown }

/** What's below the conversation: a menu to answer, the program working, or waiting on something unread. */
private enum class Status { Asking, Working, Waiting, Seeking }

private val PICTURE_HEIGHT = 260.dp

/** What was left out, as "… 12 more lines"; null if nothing was. */
private fun more(n: Int, thing: String) = if (n > 0) "… %,d more %s%s".format(n, thing, if (n == 1) "" else "s") else null

/** How long a stop from the phone says it's stopping before it's for the program to say. */
private const val STOPPING_MS = 3_000L

/** How long the program can say it's waiting without a menu on screen before that's what it is. */
private const val UNREAD_MS = 1_200L

/** Whether [this] ends with a copy button: the reply that ends a turn, once the turn is done. */
private fun Item.copies(ongoing: Boolean) = this is Item.Said && last && !ongoing

/** [ongoing]: the newest item of a turn still going, whose reply isn't the last word yet. [beside]: on its copy row's right. */
@Composable
private fun ItemView(
    item: Item,
    picture: suspend (String) -> ByteArray?,
    ongoing: Boolean,
    beside: (@Composable () -> Unit)? = null,
    onEdit: ((Entry.Said) -> Unit)? = null,
) {
    when (item) {
        is Item.Steps -> Steps(item.entries)
        is Item.Said -> when (val e = item.entry) {
            is Entry.Picture -> Picture(e, picture)
            is Entry.Note -> Note(e.text, e.detail)
            is Entry.Agent -> AgentMessage(e)
            is Entry.Command -> Command(e)
            is Entry.Said -> if (e.by == Party.User) Prompt(e.text, onEdit?.let { { it(e) } }) else Reply(e.text, copyable = item.copies(ongoing), beside)
            is Entry.Step, is Entry.Thought -> Unit // folded into steps
        }
    }
}

/** Something the user said: tapped, it opens if it's cut short; held, it offers to [onEdit] it and send it again. */
@Composable
private fun Prompt(text: String, onEdit: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    var cut by remember { mutableStateOf(false) }
    val menu = rememberOpener()
    val copier = rememberCopier()
    val style = TextStyle(color = Palette.fg, fontFamily = words, fontSize = wordSize(14.sp, 15.sp), lineHeight = wordSize(21.sp, 22.sp))
    Column(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalAlignment = Alignment.End) {
        Column(
            Modifier
                .animateContentSize(Motion.spatial())
                .clip(RoundedCornerShape(18.dp))
                .background(Palette.bg2)
                .slides(menu)
                .combinedClickable(
                    hapticFeedbackEnabled = false, // the menu's opening is felt, as the setting allows
                    onClick = { open = !open },
                    onLongClick = { menu.open = true },
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text,
                style = style,
                maxLines = if (open) Int.MAX_VALUE else 12,
                overflow = TextOverflow.Clip,
                onTextLayout = { if (!open) cut = it.hasVisualOverflow },
            )
            // What's cut off is said on a line of its own, as something to open.
            if (cut && !open) Text("…", style = style.copy(color = Palette.blue, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 16.sp))
            PopupMenu(
                menu,
                actions = listOfNotNull(
                    onEdit?.let { edit -> MenuAction("edit") { menu.open = false; edit() } },
                    MenuAction("copy") { menu.open = false; copier.copy(text) },
                ),
            )
        }
        CopyIcon { text }
    }
}

@Composable
private fun Reply(text: String, copyable: Boolean, beside: (@Composable () -> Unit)? = null) {
    val copier = rememberCopier()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Held, words select, to copy some of it.
        SelectionContainer { MarkdownText(text) }
        if (copyable) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { copier.copy(text) }
                    .padding(vertical = 4.dp, horizontal = 2.dp),
            ) {
                Crossfade(copier.copied, animationSpec = Motion.effect(), label = "copy") {
                    Text(if (it) "copied" else "copy", color = Palette.dim, style = Type.caption)
                }
            }
            beside?.invoke()
        }
    }
}

/** Puts text on the clipboard, felt, and says it [copied] for a moment after. */
private class Copier(private val context: Context, private val haptics: Haptics) {
    var copied by mutableStateOf(false)

    fun copy(text: String) {
        copy(context, text)
        haptics.confirm()
        copied = true
    }
}

@Composable
private fun rememberCopier(): Copier {
    val context = LocalContext.current
    val haptics = haptics()
    val copier = remember(haptics) { Copier(context, haptics) }
    LaunchedEffect(copier.copied) {
        if (copier.copied) {
            delay(1_500)
            copier.copied = false
        }
    }
    return copier
}

/** A small copy button, ticked a moment after; [text] is what it copies, made when tapped. */
@Composable
private fun CopyIcon(modifier: Modifier = Modifier, text: () -> String) {
    val copier = rememberCopier()
    Box(modifier.clip(CircleShape).clickable { copier.copy(text()) }.padding(6.dp)) {
        Crossfade(copier.copied, animationSpec = Motion.effect(), label = "copy") {
            GlyphIcon(if (it) Glyph.Check else Glyph.Copy, Palette.dim, size = 13.dp)
        }
    }
}

/**
 * How much of the program's context the conversation takes; tapped or
 * held, it offers to compact it (held, as it's in a list that scrolls, the
 * finger can slide on to that).
 */
@Composable
private fun ContextButton(tokens: Int, onCompact: (() -> Unit)?) {
    val menu = rememberOpener()
    Box {
        Text(
            "context ${count(tokens)}",
            color = Palette.purple,
            style = Type.caption,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .slides(menu)
                .combinedClickable(
                    enabled = onCompact != null,
                    hapticFeedbackEnabled = false, // the menu's opening is felt
                    onClick = { menu.open = true },
                    onLongClick = { menu.open = true },
                )
                .padding(vertical = 4.dp, horizontal = 2.dp),
        )
        PopupMenu(
            menu,
            actions = listOf(
                MenuAction("compact") {
                    menu.open = false
                    onCompact?.invoke()
                },
            ),
        )
    }
}

private fun count(n: Int) = when {
    n < 1_000 -> "$n"
    n < 1_000_000 -> "${n / 1_000}k"
    else -> "%.1fM".format(n / 1e6)
}

@Composable
private fun Picture(e: Entry.Picture, fetch: suspend (String) -> ByteArray?) {
    val width = LocalWindowInfo.current.containerSize.width
    val height = with(LocalDensity.current) { PICTURE_HEIGHT.roundToPx() }
    val bitmap by produceState(Pictures.kept(e.id), e.id) {
        if (value == null) value = fetch(e.id)?.let { Pictures.decode(e.id, it, width, height) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (e.by == Party.User) Arrangement.End else Arrangement.Start) {
        val shown by animateFloatAsState(if (bitmap != null) 1f else 0f, Motion.effect(), label = "picture")
        bitmap?.let {
            Image(
                it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.heightIn(max = PICTURE_HEIGHT).graphicsLayer { alpha = shown }.clip(RoundedCornerShape(12.dp)),
            )
        }
    }
}

/** Something that happened, small and centered; tapped, it opens, with its [detail] if it has one (a compaction's summary). */
@Composable
private fun Note(text: String, detail: String = "") {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().animateContentSize(Motion.spatial()).clickable { open = !open }.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (detail.isEmpty() || open) text else "$text · see more",
            color = Palette.dim,
            style = Type.caption,
            textAlign = TextAlign.Center,
            maxLines = if (open || detail.isNotEmpty()) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (open && detail.isNotEmpty()) {
            Text(
                detail,
                color = Palette.grey,
                style = Type.caption,
                modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Palette.bg1).padding(12.dp),
            )
        }
    }
}

/** Something the user sent that waits for the program to take it: theirs, faint, said to be waiting. */
@Composable
private fun Queued(text: String) {
    Column(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text,
            color = Palette.grey,
            fontFamily = words,
            fontSize = wordSize(14.sp, 15.sp),
            lineHeight = wordSize(21.sp, 22.sp),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, Palette.bg3, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
        Text("queued", color = Palette.dim, style = Type.caption, modifier = Modifier.padding(end = 6.dp))
    }
}

/**
 * A message another agent sent the program, on the program's side: who, and
 * what it's about; tapped, the message itself opens beneath.
 */
@Composable
private fun AgentMessage(e: Entry.Agent) {
    var open by remember { mutableStateOf(false) }
    val feel = rememberFold()
    val opens = e.message.isNotEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(end = 24.dp)
            .animateContentSize(Motion.spatial())
            .onSizeChanged { feel.laidOut(it.height) }
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.bg1)
            .clickable(enabled = opens) {
                feel.tap()
                open = !open
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.Spark, Palette.purple, size = 11.dp)
            Text(e.name, color = Palette.purple, style = Type.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false))
            if (opens) Text(if (open) "  hide" else "  see all", color = Palette.dim, style = Type.caption)
        }
        if (!open || !opens) {
            Text(e.about, color = Palette.fg, style = Type.label, maxLines = 3, overflow = TextOverflow.Ellipsis)
        } else {
            SelectionContainer { MarkdownText(e.message + (more(e.cut, "character")?.let { "\n\n$it" } ?: "")) }
        }
    }
}

/** A command the user ran, on their side and marked as one, with what it said back beneath; tapped, that opens if it's long. */
@Composable
private fun Command(c: Entry.Command) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            listOf(c.name, c.args).filter { it.isNotEmpty() }.joinToString(" "),
            color = Palette.purple,
            fontFamily = Mono,
            fontSize = 13.sp,
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Palette.bg2).padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (c.output.isNotEmpty()) {
            Text(
                c.output + (more(c.outputCut, "character")?.let { "\n$it" } ?: ""),
                color = Palette.grey,
                fontFamily = Mono,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = if (open) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.animateContentSize(Motion.spatial()).clickable { open = !open }.padding(horizontal = 4.dp),
            )
        }
    }
}

/** A run of tool calls and thinking, as one line saying what ran; open, a line per step. */
@Composable
private fun Steps(steps: List<Entry>) {
    var open by remember { mutableStateOf(false) }
    val feel = rememberFold()
    val tools = steps.filterIsInstance<Entry.Step>()
    val running = tools.lastOrNull()?.takeIf { it.status == Entry.Step.Status.Running }
    val failed = tools.count { it.status == Entry.Step.Status.Failed }
    val names = tools.map { it.name }.distinct()
    val red = Palette.red
    val summary = buildAnnotatedString {
        append(if (steps.size == 1) "1 step" else "${steps.size} steps")
        if (names.isNotEmpty()) append(" · " + names.take(3).joinToString(", ") + if (names.size > 3) " +${names.size - 3}" else "")
        if (failed > 0) {
            append(" · ")
            withStyle(SpanStyle(color = red)) { append("$failed failed") }
        }
    }
    Column {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    feel.tap()
                    if (open) feel.leave()
                    open = !open
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val turn by animateFloatAsState(if (open) 90f else 0f, Motion.spatial(), label = "chevron")
            Text("▸", color = Palette.grey, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.graphicsLayer { rotationZ = turn })
            if (running != null) Running(Modifier.padding(start = 8.dp))
            Text(
                summary,
                color = Palette.grey,
                style = Type.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        AnimatedVisibility(
            open,
            enter = expandVertically(Motion.spatial()) + fadeIn(Motion.effect()),
            exit = shrinkVertically(Motion.spatial()) + fadeOut(Motion.effect()),
        ) {
            Column(
                Modifier.onSizeChanged { feel.laidOut(it.height) }.padding(start = 6.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (s in steps) Step(s)
                CopyIcon { trace(steps) }
            }
        }
    }
}

/** Whether the conversation is on screen, so its working marks move. */
private val LocalLive = compositionLocalOf { true }

/** A dot that breathes while a tool runs. */
@Composable
private fun Running(modifier: Modifier) {
    val breath = if (LocalLive.current) {
        rememberInfiniteTransition(label = "running").animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "breath")
    } else {
        null
    }
    Box(modifier.size(6.dp).graphicsLayer { alpha = breath?.value ?: 1f }.clip(CircleShape).background(Palette.purple))
}

@Composable
private fun Step(s: Entry) {
    val step = s as? Entry.Step
    val name = step?.name ?: "thinking"
    val input = step?.input ?: (s as? Entry.Thought)?.text.orEmpty()
    val output = step?.output.orEmpty()
    val diff = step?.diff.orEmpty()
    val diffCut = step?.diffCut ?: 0
    val diffLines = remember(diff, diffCut) {
        if (diff.isEmpty()) emptyList() else diffLines(diff) + listOfNotNull(more(diffCut, "line")?.let { DiffLine(DiffLine.Kind.Note, it) })
    }
    val outputCut = step?.outputCut ?: 0
    val failed = step?.status == Entry.Step.Status.Failed
    var open by remember { mutableStateOf(false) }
    val feel = rememberFold()
    val mono = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp)
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(Motion.spatial())
            .onSizeChanged { feel.laidOut(it.height) } // the size it's going to
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                feel.tap()
                open = !open
            }
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = mono.copy(
                    color = when {
                        failed -> Palette.red
                        step?.status == Entry.Step.Status.Running -> Palette.purple
                        else -> Palette.aqua
                    },
                    fontStyle = if (step == null) FontStyle.Italic else FontStyle.Normal,
                ),
            )
            if (!open) {
                Text(
                    input.lineSequence().firstOrNull().orEmpty(),
                    style = mono.copy(color = Palette.grey),
                    maxLines = 1,
                    // A changed file's name is at its end.
                    overflow = if (diff.isNotEmpty()) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (diffLines.isNotEmpty()) {
                val (added, removed) = remember(diffLines) { diffCounts(diffLines) }
                Text(
                    buildAnnotatedString {
                        if (added > 0) withStyle(SpanStyle(color = Palette.green)) { append("+$added") }
                        if (added > 0 && removed > 0) append(" ")
                        if (removed > 0) withStyle(SpanStyle(color = Palette.red)) { append("−$removed") }
                    },
                    style = mono.copy(fontSize = 11.sp),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (open) {
            if (input.isNotEmpty()) Text(input, style = mono.copy(color = Palette.fg), modifier = Modifier.padding(top = 4.dp))
            if (diffLines.isNotEmpty() && !failed) {
                DiffView(diffLines, Modifier.padding(top = 6.dp))
            } else if (output.isNotEmpty()) {
                Text(
                    output + more(outputCut, "character")?.let { "\n$it" }.orEmpty(),
                    style = mono.copy(color = if (failed) Palette.red else Palette.grey, fontSize = 11.sp, lineHeight = 15.sp),
                    softWrap = false,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Palette.bg1)
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                )
            }
        }
    }
}

/**
 * Feels a fold opening or shutting: after a [tap], its next change of height
 * plays a slide lasting as long as the spring takes to move that far.
 */
private class Fold(private val haptics: Haptics) {
    private var tapped = false
    private var height = 0
    private var open: Boolean? = null

    fun tap() {
        tapped = true
    }

    /** Its content goes as it shuts, all of it at once (it animates out after). */
    fun leave() {
        if (tapped) feel(height)
        tapped = false
        height = 0
    }

    /** Whether it's open now, for a fold that opens by itself: a change is felt as if tapped. */
    fun shown(open: Boolean) {
        val was = this.open
        this.open = open
        if (was == null || was == open) return
        tap()
        if (!open) leave()
    }

    /** Its content's height, laid out at the size it's animating to. */
    fun laidOut(h: Int) {
        val moved = kotlin.math.abs(h - height)
        height = h
        if (tapped && moved > 0) feel(moved)
    }

    private fun feel(px: Int) {
        tapped = false
        haptics.slide(Motion.settleMillis(px.toFloat()))
    }
}

@Composable
private fun rememberFold(): Fold {
    val haptics = haptics()
    return remember(haptics) { Fold(haptics) }
}

/** The program working on its turn, as it puts it ([doing]) if it says, and for how long; gone when it's done. */
@Composable
private fun Working(since: Long, doing: String?) {
    val live = LocalLive.current
    val seconds by produceState(elapsed(since), since, live) {
        while (live) {
            value = elapsed(since)
            delay(1_000 - System.currentTimeMillis() % 1_000)
        }
    }
    val shine = if (live) {
        rememberInfiniteTransition(label = "working").animateFloat(-0.5f, 1.5f, infiniteRepeatable(tween(1_600, easing = LinearEasing)), label = "shine")
    } else {
        null
    }
    val colors = Palette
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Running(Modifier)
        Text(
            (doing?.lowercase() ?: "working") + (if (since > 0) " · " + duration(seconds) else ""),
            style = Type.label,
            modifier = Modifier
                .padding(start = 8.dp)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    // A light passing along it.
                    val x = (shine?.value ?: -0.5f) * size.width
                    drawRect(
                        Brush.horizontalGradient(listOf(colors.grey, colors.purple, colors.grey), startX = x - size.width / 3, endX = x + size.width / 3),
                        blendMode = BlendMode.SrcIn,
                    )
                },
        )
    }
}

private fun elapsed(since: Long) = if (since > 0) ((System.currentTimeMillis() - since) / 1_000).coerceAtLeast(0) else 0

private fun duration(seconds: Long) = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3_600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3_600}h ${seconds % 3_600 / 60}m"
}

/** The program needs an answer that isn't on its screen as a menu wallflower can read. */
@Composable
private fun Waiting() {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Palette.orange))
        Text(
            "waiting for you · answer in the terminal",
            color = Palette.grey,
            style = Type.label,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

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
