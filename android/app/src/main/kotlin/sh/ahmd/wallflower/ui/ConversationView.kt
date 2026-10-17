package sh.ahmd.wallflower.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.ahmd.wallflower.protocol.Entry
import sh.ahmd.wallflower.session.Conversation

/**
 * A pane's conversation in place of its terminal: what each side said, with
 * each run of tool calls and thinking folded into one line, to open when
 * wanted. The newest is at the bottom, which stays in view as entries come.
 * Sideways drags go to [swipe], to move between panes as on the terminal.
 */
@Composable
fun ConversationView(conversation: Conversation, modifier: Modifier = Modifier, swipe: Swipe? = null) {
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
        val items = remember(conversation.entries) { items(conversation.entries) }
        val list = rememberLazyListState()
        val scope = rememberCoroutineScope()
        // At the bottom, new entries glide into view; scrolled up to read, the list stays put.
        val atBottom by remember { derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < 8 } }
        val away by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 600 } }
        val follow = atBottom
        LaunchedEffect(items.size) { if (follow) list.animateScrollToItem(0) }
        val phase = when {
            !conversation.loaded -> Phase.Reading
            items.isEmpty() -> Phase.Empty
            else -> Phase.Shown
        }
        Crossfade(phase, animationSpec = Motion.effect(), label = "conversation") { p ->
            when (p) {
                Phase.Reading -> Hint("reading the conversation…")
                Phase.Empty -> Hint(conversation.error ?: "nothing said yet")
                Phase.Shown -> Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = list,
                        reverseLayout = true,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 48.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(items.asReversed(), key = { it.key }) {
                            Box(Modifier.animateItem(fadeInSpec = Motion.effect(), placementSpec = Motion.spatial(), fadeOutSpec = Motion.effect())) { ItemView(it) }
                        }
                        if (conversation.more) item(key = "more") { Note("earlier messages are in the terminal") }
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
                            fontFamily = words,
                            fontSize = wordSize(12.sp, 14.sp),
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
    }
}

private enum class Phase { Reading, Empty, Shown }

/** What the list shows: something said, or a run of steps (tool calls, thinking) between. */
private sealed interface Item {
    val key: String

    /** [last]: the reply that ends its turn, which gets a copy button. */
    data class Said(val entry: Entry, val last: Boolean = false) : Item {
        override val key get() = entry.id
    }

    data class Steps(val entries: List<Entry>) : Item {
        override val key get() = "steps " + entries.first().id
    }
}

private fun items(entries: List<Entry>): List<Item> {
    val out = mutableListOf<Item>()
    var steps = mutableListOf<Entry>()
    for (e in entries) {
        if (e.kind == "tool" || e.kind == "thinking") {
            steps += e
            continue
        }
        if (steps.isNotEmpty()) out += Item.Steps(steps)
        steps = mutableListOf()
        out += Item.Said(e)
    }
    if (steps.isNotEmpty()) out += Item.Steps(steps)
    // A turn ends where the user speaks next, or at the end.
    var ended = true
    for (i in out.indices.reversed()) {
        val item = out[i] as? Item.Said ?: continue
        when {
            item.entry.role == "user" && item.entry.kind == "text" -> ended = true
            item.entry.role == "assistant" && item.entry.kind == "text" && ended -> {
                out[i] = item.copy(last = true)
                ended = false
            }
        }
    }
    return out
}

@Composable
private fun ItemView(item: Item) {
    when (item) {
        is Item.Steps -> Steps(item.entries)
        is Item.Said -> {
            val e = item.entry
            when {
                e.kind == "image" -> Picture(e)
                e.kind == "note" -> Note(e.text)
                e.role == "user" -> Prompt(e.text)
                else -> Reply(e.text, copyable = item.last)
            }
        }
    }
}

@Composable
private fun Prompt(text: String) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalArrangement = Arrangement.End) {
        Text(
            text,
            style = TextStyle(color = Palette.fg, fontFamily = words, fontSize = wordSize(14.sp, 15.sp), lineHeight = wordSize(21.sp, 22.sp)),
            maxLines = if (open) Int.MAX_VALUE else 12,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .animateContentSize(Motion.spatial())
                .clip(RoundedCornerShape(18.dp))
                .background(Palette.bg2)
                .clickable { open = !open }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun Reply(text: String, copyable: Boolean) {
    val context = LocalContext.current
    val haptics = haptics()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MarkdownText(text)
        if (copyable) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        copy(context, text)
                        haptics.confirm()
                        copied = true
                    }
                    .padding(vertical = 4.dp, horizontal = 2.dp),
            ) {
                Crossfade(copied, animationSpec = Motion.effect(), label = "copy") {
                    Text(if (it) "copied" else "copy", color = Palette.dim, fontFamily = words, fontSize = wordSize(11.sp, 13.sp))
                }
            }
        }
    }
}

@Composable
private fun Picture(e: Entry) {
    val bitmap by produceState<ImageBitmap?>(null, e.data) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val bytes = Base64.decode(e.data, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (e.role == "user") Arrangement.End else Arrangement.Start) {
        val shown by animateFloatAsState(if (bitmap != null) 1f else 0f, Motion.effect(), label = "picture")
        bitmap?.let {
            Image(
                it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.heightIn(max = 260.dp).graphicsLayer { alpha = shown }.clip(RoundedCornerShape(12.dp)),
            )
        }
    }
}

@Composable
private fun Note(text: String) {
    var open by remember { mutableStateOf(false) }
    Text(
        text,
        color = Palette.dim,
        fontFamily = words,
        fontSize = wordSize(11.sp, 13.sp),
        textAlign = TextAlign.Center,
        maxLines = if (open) Int.MAX_VALUE else 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().animateContentSize(Motion.spatial()).clickable { open = !open }.padding(horizontal = 24.dp),
    )
}

/** A run of tool calls and thinking, as one line saying what ran; open, a line per step. */
@Composable
private fun Steps(steps: List<Entry>) {
    var open by remember { mutableStateOf(false) }
    val running = steps.lastOrNull { it.kind == "tool" }?.takeIf { !it.done }
    val failed = steps.count { it.error }
    val names = steps.filter { it.kind == "tool" }.map { it.name }.distinct()
    val summary = buildString {
        append(if (steps.size == 1) "1 step" else "${steps.size} steps")
        if (names.isNotEmpty()) append(" · ").append(names.take(3).joinToString(", ")).append(if (names.size > 3) " +${names.size - 3}" else "")
        if (failed > 0) append(" · $failed failed")
    }
    Column {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val turn by animateFloatAsState(if (open) 90f else 0f, Motion.spatial(), label = "chevron")
            Text("▸", color = Palette.grey, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.rotate(turn))
            if (running != null) Running(Modifier.padding(start = 8.dp))
            Text(
                summary,
                color = if (failed > 0) Palette.red else Palette.grey,
                fontFamily = words,
                fontSize = wordSize(12.sp, 14.sp),
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
            Column(Modifier.padding(start = 6.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (s in steps) Step(s)
            }
        }
    }
}

/** A dot that breathes while a tool runs. */
@Composable
private fun Running(modifier: Modifier) {
    val breath by rememberInfiniteTransition(label = "running")
        .animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "breath")
    Box(modifier.size(6.dp).graphicsLayer { alpha = breath }.clip(CircleShape).background(Palette.orange))
}

@Composable
private fun Step(s: Entry) {
    var open by remember { mutableStateOf(false) }
    val mono = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp)
    Column(Modifier.fillMaxWidth().animateContentSize(Motion.spatial()).clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(horizontal = 8.dp, vertical = 5.dp)) {
        Row {
            Text(
                if (s.kind == "thinking") "thinking" else s.name,
                style = mono.copy(
                    color = when {
                        s.error -> Palette.red
                        s.kind == "tool" && !s.done -> Palette.orange
                        else -> Palette.aqua
                    },
                    fontStyle = if (s.kind == "thinking") FontStyle.Italic else FontStyle.Normal,
                ),
            )
            if (!open) {
                Text(s.text.lineSequence().firstOrNull().orEmpty(), style = mono.copy(color = Palette.grey), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (open) {
            if (s.text.isNotEmpty()) Text(s.text, style = mono.copy(color = Palette.fg), modifier = Modifier.padding(top = 4.dp))
            if (s.output.isNotEmpty()) {
                Text(
                    s.output,
                    style = mono.copy(color = if (s.error) Palette.red else Palette.grey, fontSize = 11.sp, lineHeight = 15.sp),
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

@Composable
private fun Hint(text: String) = Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
    Text(text, color = Palette.grey, fontFamily = words, fontSize = wordSize(13.sp, 15.sp), textAlign = TextAlign.Center)
}
