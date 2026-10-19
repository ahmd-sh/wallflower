package sh.ahmd.wallflower.ui

import android.graphics.RenderNode
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
import sh.ahmd.wallflower.read.Prompts
import sh.ahmd.wallflower.read.Said
import sh.ahmd.wallflower.read.ShellItem
import sh.ahmd.wallflower.read.promptBefore
import sh.ahmd.wallflower.read.shellItems
import sh.ahmd.wallflower.read.withoutMark
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
 * output comes, and below them the prompt waiting, as the composer's [draft]
 * (or, while that's empty, the shell's own line, [pending]) with a caret
 * while the composer is [focused]. Each line reads as a styled prompt: what
 * the prompt says (its directory, in most), then what was typed. What came
 * before the first command is one card, kept above the screen until
 * scrolled to. A full-screen app's screen shows live, as tall as the room a
 * one-line composer leaves ([lines] it has now), its scroll going to the app
 * ([onWheel]), with a button that ends it ([onQuit]). Sideways drags go to
 * [swipe], to move between panes as on the terminal.
 */
@Composable
internal fun CommandsView(
    terminal: Terminal,
    /** The pane's width in cells, which its rows are laid out for. */
    cols: Int,
    /** What the phone learned of prompts, to read those a repaint left without saying what was typed. */
    prompts: Prompts,
    draft: TextFieldState,
    pending: String,
    focused: Boolean,
    lines: Int,
    swipe: Swipe?,
    onWheel: (up: Boolean, bytes: ByteArray?) -> Unit,
    onQuit: () -> Unit,
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

    // What was typed at each command's prompt, by id, once read; empty for nothing.
    val typed = remember(terminal) { mutableStateMapOf<Int, Said>() }
    var mark by remember { mutableStateOf(prompts.mark) }
    val unread = remember(terminal) { HashMap<Int, String?>() } // prompts that couldn't be read, by the mark they were tried with
    var waiting by remember(terminal) { mutableStateOf("") } // what the prompt waiting says
    val list = commands?.list.orEmpty()
    LaunchedEffect(list.size, list.lastOrNull()?.id, list.lastOrNull()?.ran, mark, fullscreen) {
        for ((i, c) in list.withIndex()) {
            val newest = i == list.lastIndex
            if (newest && !c.ran) {
                // A full-screen app that started before the commands were last read: what started it was read as it ran.
                val started = if (fullscreen) terminal.typed(c.id) else null
                if (started != null && started.text.isNotBlank()) {
                    typed[c.id] = Said(withoutMark(started.prompt, mark), started.text)
                    continue
                }
                val head = rows.read(c.start, c.end) ?: continue
                waiting = if (c.typed) withoutMark(promptOf(head), mark) else mark?.let { promptBefore(it, lines(head)) }.orEmpty()
                continue
            }
            if (c.id in typed || (c.id in unread && unread[c.id] == mark)) continue
            // Seen run, it was read as it started: its rows may be behind the full-screen app it started.
            val seen = if (c.ran && c.typed) terminal.typed(c.id) else null
            if (seen != null && seen.text.isNotBlank()) {
                prompts.learn(rows.read(c.start, c.output)?.let(::lines), seen.prompt, seen.text)
                mark = prompts.mark
                typed[c.id] = Said(withoutMark(seen.prompt, mark), seen.text)
                continue
            }
            val head = rows.read(c.start, if (c.ran) c.output else c.end) ?: continue // behind a full-screen app
            val said = typed(head)
            val rows = lines(head)
            if (said.isNotBlank()) {
                val prompt = promptOf(head)
                prompts.learn(rows, prompt, said)
                mark = prompts.mark
                typed[c.id] = Said(withoutMark(prompt, mark), said)
            } else {
                val read = prompts.read(rows, head.cols)
                if (read == null) unread[c.id] = mark else typed[c.id] = read
            }
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
        // Behind a full-screen app from the start (opened, or repainted, while it ran), its screen is all there is.
        val shown = commands ?: if (fullscreen) Commands(0, 0, emptyList()) else null
        val nothing by remember(typed) { derivedStateOf { typed.filterValues { it.text.isEmpty() }.keys } }
        val items = remember(shown, fullscreen, nothing) { shown?.let { shellItems(it, fullscreen, nothing) }.orEmpty() }
        val lazy = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val atBottom by remember { derivedStateOf { lazy.firstVisibleItemIndex == 0 && lazy.firstVisibleItemScrollOffset < 8 } }
        val away by remember { derivedStateOf { lazy.firstVisibleItemIndex > 0 || lazy.firstVisibleItemScrollOffset > 600 } }
        // At the bottom, what comes next glides into view; scrolled up to read, the list stays put.
        val follow = atBottom
        LaunchedEffect(items.lastOrNull()?.key) { if (follow) lazy.animateScrollToItem(0) }
        val gap = rememberGap(lazy)
        // A full-screen app gets the room a one-line composer leaves; a taller composer scrolls it up.
        var room by remember { mutableIntStateOf(0) }
        val viewport by remember { derivedStateOf { lazy.layoutInfo.let { it.viewportSize.height - it.beforeContentPadding - it.afterContentPadding } } }
        LaunchedEffect(viewport, lines) { if (lines <= 1 && viewport > 0) room = viewport }

        Crossfade(shown == null || items.isEmpty(), animationSpec = Motion.effect(), label = "commands") { empty ->
            if (empty) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(if (shown == null) "reading…" else "commands you run show here", color = Palette.dim, style = Type.body)
                }
                return@Crossfade
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                state = lazy,
                reverseLayout = true,
                contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 48.dp, bottom = 12.dp),
            ) {
                items(items.asReversed(), key = { it.key }, contentType = { it::class }) { item ->
                    when (item) {
                        is ShellItem.Title -> Title(item, typed[item.id], rows, painter, cols, back)
                        is ShellItem.Rows -> Card(item, rows, painter, cols, version = if (item.live) generation else back)
                        is ShellItem.Gap -> Spacer(Modifier.height(with(LocalDensity.current) { gap.toDp() }))
                        is ShellItem.Fullscreen -> Live(terminal, room, onWheel, onQuit)
                        is ShellItem.Draft -> Draft(waiting, draft, pending, focused)
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
                    .clickable { scope.launch { lazy.animateScrollToItem(0) } }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * A command's line: what its prompt says, then what was typed ([said]), as
 * a styled prompt; where that couldn't be read, its prompt rows as they are.
 */
@Composable
private fun Title(item: ShellItem.Title, said: Said?, rows: Packer, painter: RowPainter, cols: Int, version: Int) {
    Box(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp)) {
        if (said != null && said.text.isNotBlank()) {
            Text(promptLine(said.prompt, said.text), style = promptStyle())
            return@Box
        }
        val frame by produceState<FrameBuffer?>(null, item.from, item.to, version) { rows.read(item.from, item.to)?.let { value = it } }
        Rows(frame, item.to - item.from, painter, cols, Modifier.fillMaxWidth())
    }
}

/**
 * The prompt waiting, saying [prompt], with what's written in the composer,
 * [draft], and its caret, blinking, while the composer is [focused]; while
 * that's empty, the shell's own line, [pending], dimmed.
 */
@Composable
private fun Draft(prompt: String, draft: TextFieldState, pending: String, focused: Boolean) {
    val text = draft.text.toString()
    val shown = text.ifEmpty { pending }
    val caret = if (text.isEmpty()) 0 else draft.selection.max.coerceIn(0, text.length)
    val on = remember { Animatable(1f) }
    LaunchedEffect(focused, text, caret) {
        on.snapTo(1f) // solid while typing, blinking once it stops
        while (focused) {
            delay(BLINK_MS)
            on.snapTo(0f)
            delay(BLINK_MS)
            on.snapTo(1f)
        }
    }
    val line = promptLine(prompt, shown, dim = text.isEmpty())
    val at = line.length - shown.length + caret
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val width = with(LocalDensity.current) { 2.dp.toPx() }
    Text(
        line,
        style = promptStyle(),
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 6.dp)
            .drawWithContent {
                drawContent()
                val l = layout ?: return@drawWithContent
                if (!focused) return@drawWithContent
                val r = l.getCursorRect(at.coerceIn(0, line.length))
                drawRect(Palette.orange, Offset(r.left + width, r.top), Size(width, r.height), alpha = on.value)
            },
    )
}

/** What a prompt says, then what was typed at it, as a styled prompt. */
@Composable
private fun promptLine(prompt: String, typed: String, dim: Boolean = false) = buildAnnotatedString {
    if (prompt.isNotEmpty()) {
        withStyle(SpanStyle(color = Palette.green)) { append(prompt) }
        append("  ")
    }
    if (dim) withStyle(SpanStyle(color = Palette.dim)) { append(typed) } else append(typed)
}

@Composable
private fun promptStyle() = TextStyle(color = Palette.fg, fontFamily = words, fontSize = wordSize(14.sp, 15.sp), lineHeight = wordSize(21.sp, 22.sp))

/**
 * A piece of a terminal view card: rows as the terminal has them, framed by a
 * subtle border, lit from inside at its top and shaded at its bottom, so it
 * stands out. The first piece rounds its top, the last its bottom. An empty
 * card is a row tall.
 */
@Composable
private fun Card(item: ShellItem.Rows, rows: Packer, painter: RowPainter, cols: Int, version: Int) {
    val frame by produceState<FrameBuffer?>(null, item.from, item.to, version) { rows.read(item.from, item.to)?.let { value = it } }
    Box(Modifier.fillMaxWidth().card(item.first, item.last).padding(start = INSET, end = INSET, top = if (item.first) INSET else 0.dp, bottom = if (item.last) INSET else 0.dp)) {
        Rows(frame, maxOf(1, item.to - item.from), painter, cols, Modifier.fillMaxWidth())
    }
}

/**
 * The look of a terminal view card, or of one piece of it, [top] and
 * [bottom] saying whether its edges are the card's: its border runs on past
 * the edges that aren't, so the pieces join.
 */
private fun Modifier.card(top: Boolean, bottom: Boolean): Modifier {
    val shape = RoundedCornerShape(
        topStart = if (top) CARD_ROUND else 0.dp,
        topEnd = if (top) CARD_ROUND else 0.dp,
        bottomStart = if (bottom) CARD_ROUND else 0.dp,
        bottomEnd = if (bottom) CARD_ROUND else 0.dp,
    )
    return clip(shape)
        .background(Palette.bg1)
        .drawWithContent {
            drawContent()
            val round = CARD_ROUND.toPx()
            val line = 1.dp.toPx()
            val above = if (top) 0f else round * 2 // edges that aren't the card's lie out of sight
            val below = if (bottom) 0f else round * 2
            if (top) {
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.Transparent), endY = 18.dp.toPx()))
            }
            if (bottom) {
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.22f)), startY = size.height - 14.dp.toPx(), endY = size.height))
            }
            drawRoundRect(
                Palette.bg3,
                topLeft = Offset(line / 2, line / 2 - above),
                size = Size(size.width - line, size.height - line + above + below),
                cornerRadius = CornerRadius(round - line / 2),
                style = Stroke(line),
            )
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

/** [f]'s rows as text, as wide as it is. */
private fun lines(f: FrameBuffer): List<String> = List(f.rowCount) { i ->
    val row = StringBuilder()
    for (x in 0 until f.cols) if (FrameBuffer.wide(f.flags(i, x)) < 2) {
        if (f.codepoint(i, x) == 0) row.append(' ') else f.appendText(i, x, row)
    }
    row.toString()
}

/**
 * The prompt in [f], as the shell marked it live: the run of prompt cells
 * that starts the row typing starts on, or the last row a prompt starts.
 */
private fun promptOf(f: FrameBuffer): String {
    val y = (0 until f.rowCount).firstOrNull { i -> (0 until f.cols).any { f.role(i, it) == FrameBuffer.ROLE_INPUT } }
        ?: (f.rowCount - 1 downTo 0).firstOrNull { f.role(it, 0) == FrameBuffer.ROLE_PROMPT }
        ?: return ""
    val prompt = StringBuilder()
    var x = 0
    while (x < f.cols && f.role(y, x) == FrameBuffer.ROLE_PROMPT) {
        if (FrameBuffer.wide(f.flags(y, x)) < 2) {
            if (f.codepoint(y, x) == 0) prompt.append(' ') else f.appendText(y, x, prompt)
        }
        x++
    }
    return prompt.toString()
}

/**
 * A full-screen app's screen, live, in a terminal view card [room] tall
 * with the button below it that ends the app ([onQuit]); the screen shrinks
 * to fit, whole.
 */
@Composable
private fun Live(terminal: Terminal, room: Int, onWheel: (Boolean, ByteArray?) -> Unit, onQuit: () -> Unit) {
    val density = LocalDensity.current
    val height = with(density) { (room.toDp() - QUIT_ROW).coerceAtLeast(120.dp) }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(height).card(top = true, bottom = true).padding(INSET),
            factory = { ctx -> TerminalView(ctx).apply { fitsHeight = true } },
            update = {
                it.terminal = terminal
                it.onAppScroll = onWheel
            },
        )
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.CenterEnd) {
            Text(
                "kill",
                color = Palette.red,
                style = Type.label,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.bg2)
                    .clickable(onClick = onQuit)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
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
private val CARD_ROUND = 14.dp // under the composer's 26
private val QUIT_ROW = 52.dp // the kill button below a full-screen app's card, with its margins
private const val READ_EVERY_MS = 60L
private const val BLINK_MS = 530L
