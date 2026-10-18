package sh.ahmd.wallflower.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.pow
import sh.ahmd.wallflower.protocol.Pane
import sh.ahmd.wallflower.protocol.Window
import sh.ahmd.wallflower.terminal.Terminal
import sh.ahmd.wallflower.terminal.TerminalView

/**
 * The window's panes as cards: the shown pane zooms out into its card among
 * the others, and a card zooms back in. [open] says which way it's going;
 * [zoom] is how far (0 the pane, 1 the cards), and [zoomed] the pane whose
 * card it goes into or out of; [cards] is where each card's terminal is.
 */
@Stable
internal class Overview {
    var open by mutableStateOf(false)
    var zoomed by mutableStateOf<String?>(null)
    val zoom = Animatable(0f)
    val cards = mutableStateMapOf<String, Rect>()

    /** The cards show, or are coming or going. */
    val up by derivedStateOf { open || zoom.value > 0f }

    /** Only the cards show. */
    val covering by derivedStateOf { open && zoom.value == 1f }

    fun show(from: String?) {
        zoomed = from
        open = true
    }

    fun hide(into: String?) {
        zoomed = into
        open = false
    }
}

@Composable
internal fun rememberOverview(): Overview {
    val o = remember { Overview() }
    LaunchedEffect(o.open) { o.zoom.animateTo(if (o.open) 1f else 0f, Motion.spatial()) }
    return o
}

/**
 * Every pane of a window as a card with its live contents, in tmux's order,
 * then one to open a new pane. Tap a card to show it, × to close it. Cards
 * fill as many columns as the screen is wide. They come in with [progress]
 * as the [zoomed] pane shrinks into its card, which says where it is in
 * [cards].
 */
@Composable
internal fun PaneOverview(
    window: Window,
    shown: String?,
    zoomed: String?,
    progress: () -> Float,
    terminals: Map<String, Terminal>,
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
            // The grid reuses cards' views for other panes as it scrolls; each draws whichever terminal it's given.
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
                onRelease = { v -> v.terminal = null },
                update = { v -> v.terminal = terminal },
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

internal fun LayoutCoordinates.rootRect() = Rect(positionInRoot(), size.toSize())

/**
 * Draws a pane [p] of the way into its [card]'s terminal view (both in root
 * pixels): its grid ([terminal]'s) scaled onto the card's, which draws the
 * same grid fitted to its width, showing as much as fits around the cursor,
 * as the card does. It shrinks at an even pace (scale on a log curve) and
 * lands just before the end, so the card's view fades in over it exactly.
 * With no card to go to it fades.
 */
internal fun GraphicsLayerScope.zoomInto(p: Float, card: Rect?, pane: Rect, terminal: TerminalView?) {
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

/** Cards are a phone screen's shape, like a browser's tab grid. */
private const val CARD_RATIO = 0.75f

private val CARD_CORNER = 14.dp

/** The last stretch of a zoom, where the card's own view fades in over the pane. */
private const val HANDOFF = 0.06f
