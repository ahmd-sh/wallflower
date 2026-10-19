package sh.ahmd.wallflower.ui

import android.graphics.RenderNode
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import sh.ahmd.wallflower.read.ShellItem
import sh.ahmd.wallflower.read.shellItems
import sh.ahmd.wallflower.terminal.Commands
import sh.ahmd.wallflower.terminal.FrameBuffer
import sh.ahmd.wallflower.terminal.RowPainter
import sh.ahmd.wallflower.terminal.Terminal
import sh.ahmd.wallflower.terminal.TerminalView
import sh.ahmd.wallflower.terminal.Theme

/**
 * A shell pane's read view, in place of its terminal: the commands its shell
 * marked (OSC 133), each what was typed and a card of its output drawn as
 * the terminal draws it, the newest at the bottom, which stays in view as
 * output comes. What came before the first is one card, kept above the
 * screen until scrolled to. A full-screen app's screen shows live, the
 * pane's height, its scroll going to the app ([onWheel]). Sideways drags go
 * to [swipe], to move between panes as on the terminal.
 */
@Composable
internal fun CommandsView(
    terminal: Terminal,
    /** The pane's width in cells, which its rows are laid out for. */
    cols: Int,
    /** What was typed at the pane's prompts, kept across its terminals. */
    titles: Titles,
    swipe: Swipe?,
    onWheel: (up: Boolean, bytes: ByteArray?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val painter = remember { RowPainter(context) }
    val rows = remember(terminal) { Packer(terminal) }
    var commands by remember(terminal) { mutableStateOf<Commands?>(null) }
    var fullscreen by remember(terminal) { mutableStateOf(false) }
    var generation by remember(terminal) { mutableIntStateOf(0) } // a new read of what's going on
    var back by remember(terminal) { mutableIntStateOf(0) } // times the commands came back from behind a full-screen app, when rows read while they couldn't be read again
    LaunchedEffect(terminal) {
        terminal.setTheme(Theme.EverforestDarkHard) // as its terminal view, which may not have drawn yet
        val ticks = Channel<Unit>(Channel.CONFLATED)
        val output = Runnable { ticks.trySend(Unit) }
        terminal.addListener(output)
        try {
            ticks.trySend(Unit)
            for (tick in ticks) {
                val now = withContext(Dispatchers.Default) { terminal.commands() }
                // A full-screen app's alternate screen hides the commands; they're as they were.
                if (fullscreen && now != null) back++
                fullscreen = now == null
                if (now != null) commands = now
                generation++
                delay(READ_EVERY_MS)
            }
        } finally {
            terminal.removeListener(output)
        }
    }

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
        val shown = commands
        val items = remember(shown, fullscreen) { shown?.let { shellItems(it, fullscreen) }.orEmpty() }
        val list = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val atBottom by remember { derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < 8 } }
        val away by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 600 } }
        // At the bottom, what comes next glides into view; scrolled up to read, the list stays put.
        val follow = atBottom
        LaunchedEffect(items.lastOrNull()?.key) { if (follow) list.animateScrollToItem(0) }
        val gap = rememberGap(list)

        Crossfade(shown == null || items.isEmpty(), animationSpec = Motion.effect(), label = "commands") { empty ->
            if (empty) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(if (shown == null) "reading…" else "commands you run show here", color = Palette.dim, style = Type.body)
                }
                return@Crossfade
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                state = list,
                reverseLayout = true,
                contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 48.dp, bottom = 12.dp),
            ) {
                items(items.asReversed(), key = { it.key }, contentType = { it::class }) { item ->
                    when (item) {
                        is ShellItem.Title -> Title(item, rows, painter, cols, titles, back)
                        is ShellItem.Rows -> Card(item, rows, painter, cols, version = if (item.live) generation else back)
                        is ShellItem.Gap -> Spacer(Modifier.height(with(LocalDensity.current) { gap.toDp() }))
                        is ShellItem.Fullscreen -> Live(terminal, onWheel, Modifier.fillParentMaxHeight().padding(vertical = 6.dp))
                    }
                }
            }
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

/**
 * What was typed at a command's prompt, as said. Where the shell's marks
 * didn't say (a repaint of history, which keeps only where prompts and
 * output start), what the phone saw typed on the same prompt rows before;
 * failing that, the prompt rows as they are.
 */
@Composable
private fun Title(item: ShellItem.Title, rows: Packer, painter: RowPainter, cols: Int, titles: Titles, version: Int) {
    val frame by produceState<FrameBuffer?>(null, item.from, item.to, version) { rows.read(item.from, item.to)?.let { value = it } }
    val f = frame
    val typed = remember(f) {
        f?.let {
            val rows = text(it)
            typed(it).takeIf(String::isNotBlank)?.also { t -> titles.keep(rows, t) } ?: titles.recall(rows)
        }.orEmpty()
    }
    Box(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), contentAlignment = Alignment.CenterEnd) {
        when {
            f == null -> Spacer(Modifier.height(with(LocalDensity.current) { painter.cellHeight.toDp() }))
            typed.isNotBlank() -> Text(
                typed,
                style = TextStyle(color = Palette.fg, fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.sp),
                modifier = Modifier
                    .padding(start = 40.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Palette.bg2)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            else -> Rows(f, item.to - item.from, painter, cols, Modifier.fillMaxWidth())
        }
    }
}

/** A piece of a card of rows: the first rounds its top, the last its bottom. */
@Composable
private fun Card(item: ShellItem.Rows, rows: Packer, painter: RowPainter, cols: Int, version: Int) {
    val frame by produceState<FrameBuffer?>(null, item.from, item.to, version) { rows.read(item.from, item.to)?.let { value = it } }
    val round = 12.dp
    val shape = RoundedCornerShape(
        topStart = if (item.first) round else 0.dp,
        topEnd = if (item.first) round else 0.dp,
        bottomStart = if (item.last) round else 0.dp,
        bottomEnd = if (item.last) round else 0.dp,
    )
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.bg1)
            .padding(start = INSET, end = INSET, top = if (item.first) INSET else 0.dp, bottom = if (item.last) INSET else 0.dp),
    ) {
        Rows(frame, item.to - item.from, painter, cols, Modifier.fillMaxWidth())
    }
}

/**
 * [count] rows as the terminal draws them, shrunk to fit if the pane is
 * wider than the room; blank until [frame] is read. Recorded once per frame.
 */
@Composable
private fun Rows(frame: FrameBuffer?, count: Int, painter: RowPainter, cols: Int, modifier: Modifier) {
    Spacer(
        modifier
            .layout { measurable, constraints ->
                val width = constraints.maxWidth
                val scale = scale(width, cols, painter)
                val height = ceil(count * painter.cellHeight * scale).roundToInt()
                val p = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                layout(width, height) { p.place(0, 0) }
            }
            .drawWithCache {
                val f = frame
                val node = f?.let { record(it, painter) }
                val scale = scale(size.width.roundToInt(), cols, painter)
                onDrawBehind {
                    if (node == null) return@onDrawBehind
                    drawIntoCanvas { canvas ->
                        val c = canvas.nativeCanvas
                        c.save()
                        c.scale(scale, scale)
                        if (c.isHardwareAccelerated) c.drawRenderNode(node) else draw(c, f, painter)
                        c.restore()
                    }
                }
            },
    )
}

private fun scale(width: Int, cols: Int, painter: RowPainter): Float {
    val w = cols * painter.cellWidth
    return if (w > width && w > 0f) width / w else 1f
}

private fun record(f: FrameBuffer, painter: RowPainter): RenderNode {
    val node = RenderNode("rows")
    node.setPosition(0, 0, ceil(f.cols * painter.cellWidth).toInt(), ceil(f.rowCount * painter.cellHeight).toInt())
    val c = node.beginRecording()
    draw(c, f, painter)
    node.endRecording()
    return node
}

private fun draw(c: android.graphics.Canvas, f: FrameBuffer, painter: RowPainter) {
    for (i in 0 until f.rowCount) {
        c.save()
        c.translate(0f, f.rowY(i) * painter.cellHeight)
        painter.draw(c, f, i, painter.cellHeight, f.background, f.foreground)
        c.restore()
    }
}

/**
 * What was typed into [f]'s prompt rows: the cells the shell marked as
 * input, first to last on each row (a shell can leave a blank it typed
 * undrawn, unmarked). A row that runs to the edge goes on into the next.
 */
private fun typed(f: FrameBuffer): String {
    val out = StringBuilder()
    var joins = false
    for (i in 0 until f.rowCount) {
        val row = StringBuilder()
        var edge = false
        val first = (0 until f.cols).firstOrNull { f.role(i, it) == FrameBuffer.ROLE_INPUT } ?: -1
        val end = (f.cols - 1 downTo 0).firstOrNull { f.role(i, it) == FrameBuffer.ROLE_INPUT } ?: -2
        for (x in maxOf(first, 0)..end) {
            if (FrameBuffer.wide(f.flags(i, x)) >= 2) continue
            if (f.codepoint(i, x) == 0) row.append(' ') else f.appendText(i, x, row)
            if (x == f.cols - 1) edge = f.codepoint(i, x) != 0
        }
        val text = if (edge) row.toString() else row.trimEnd().toString()
        if (text.isBlank()) {
            joins = false
            continue
        }
        if (out.isNotEmpty() && !joins) out.append('\n')
        out.append(text)
        joins = edge
    }
    return out.toString().trim()
}

/** [f]'s rows as text, each without its trailing blanks. */
private fun text(f: FrameBuffer): String {
    val out = StringBuilder()
    for (i in 0 until f.rowCount) {
        if (i > 0) out.append('\n')
        val row = StringBuilder()
        for (x in 0 until f.cols) if (FrameBuffer.wide(f.flags(i, x)) < 2) {
            if (f.codepoint(i, x) == 0) row.append(' ') else f.appendText(i, x, row)
        }
        out.append(row.trimEnd())
    }
    return out.toString()
}

/**
 * What was typed at a pane's prompts, by the prompt rows it was typed on, so
 * a repaint (a reconnect), whose history keeps only where prompts start, can
 * still say it. The newest few hundred.
 */
internal class Titles {
    private val byRows = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 500
    }

    fun keep(rows: String, typed: String) = synchronized(byRows) { byRows[rows] = typed }

    fun recall(rows: String): String? = synchronized(byRows) { byRows[rows] }
}

/** A full-screen app's screen, live, as the pane's terminal view shows it. */
@Composable
private fun Live(terminal: Terminal, onWheel: (Boolean, ByteArray?) -> Unit, modifier: Modifier) {
    AndroidView(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
        factory = { ctx -> TerminalView(ctx) },
        update = {
            it.terminal = terminal
            it.onAppScroll = onWheel
        },
    )
}

/**
 * How tall the gap above the commands is, in pixels: enough to keep what's
 * above it (the history) off the screen while the commands below it are
 * shorter than the screen; nothing once they aren't.
 */
@Composable
private fun rememberGap(list: LazyListState): Int {
    var gap by remember { mutableIntStateOf(0) }
    LaunchedEffect(list) {
        snapshotFlow {
            val info = list.layoutInfo
            val at = info.visibleItemsInfo.firstOrNull { it.key == ShellItem.Gap.key } ?: return@snapshotFlow null
            val below = info.visibleItemsInfo.filter { it.index < at.index }
            if (below.size < at.index) return@snapshotFlow 0 // some aren't laid out: they're taller than the screen
            maxOf(0, info.viewportSize.height - below.sumOf { it.size }) // past the top edge, margin and all
        }.collect { if (it != null) gap = it }
    }
    return gap
}

/** Reads rows into frames of their own, one read at a time; null while there are none to read. */
@Stable
private class Packer(private val terminal: Terminal) {
    private val scratch = FrameBuffer()
    private val lock = Mutex()

    suspend fun read(from: Int, to: Int): FrameBuffer? = withContext(Dispatchers.Default) {
        lock.withLock { if (to > from && terminal.rows(from, to - from, scratch)) scratch.copy() else null }
    }
}

private val SIDE = 6.dp
private val INSET = 6.dp
private const val READ_EVERY_MS = 60L
