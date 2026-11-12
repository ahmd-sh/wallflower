package sh.ahmd.wallflower.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.ahmd.wallflower.protocol.Pane
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where a page reports sideways drags: how far, as they go, then how far and how fast (px/s) when let go. */
interface Swipe {
    fun drag(dx: Float)
    fun end(dx: Float, velocity: Float)
}

/**
 * A window's panes side by side, the shown one on screen. A sideways drag
 * moves them with the finger; let go, they settle on a pane with a spring
 * that carries the fling, and that pane is shown. Panes wrap around. Every
 * pane's terminal is kept current, so the next one comes in already drawn.
 * A drag far enough to move on is felt as it gets there. [onPosition] hears
 * where the panes are, as an index between panes while they move.
 *
 * A lone pane dragged left far enough, with [onNew], gives a little more at
 * once, felt, and shows where a new pane would go; let go there, it's asked
 * for ([onNew]) and its place slides in, for the pane to come into.
 */
@Composable
fun PanePager(
    panes: List<Pane>,
    shown: Pane,
    onShow: (Pane) -> Unit,
    modifier: Modifier = Modifier,
    onPosition: ((Float) -> Unit)? = null,
    onNew: (() -> Unit)? = null,
    page: @Composable (pane: Pane, current: Boolean, swipe: Swipe) -> Unit,
) {
    var current by remember { mutableStateOf(shown.id) }
    var offset by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val settling = remember { arrayOfNulls<Job>(1) }
    val snap = remember { Animatable(0f) } // the more a lone pane gives once it would make a new one
    var armed by remember { mutableStateOf(false) } // let go now, and it moves on, or makes a new pane
    var making by remember { mutableStateOf(false) } // a new pane asked for, its place on screen
    val density = LocalDensity.current
    val reach = with(density) { NEW_PANE_REACH.toPx() }
    val give = with(density) { NEW_PANE_SNAP.toPx() }

    // The shown pane changing elsewhere (a card, the computer) cuts straight to it; the new pane asked for slides the rest of the way in first.
    LaunchedEffect(shown.id) {
        if (shown.id != current) {
            settling[0]?.cancel()
            if (making && width > 0) animate(offset, -width.toFloat(), 0f, Motion.spatial()) { value, _ -> offset = value }
            Snapshot.withMutableSnapshot {
                current = shown.id
                offset = 0f
                making = false
            }
        }
    }

    val i = panes.indexOfFirst { it.id == current }.takeIf { it >= 0 } ?: panes.indexOfFirst { it.id == shown.id }.coerceAtLeast(0)
    val here = panes.getOrNull(i) ?: shown
    val previous = if (panes.size > 1) panes[(i - 1).mod(panes.size)] else null
    val next = if (panes.size > 1) panes[(i + 1).mod(panes.size)] else null
    val neighbours by rememberUpdatedState(previous to next)
    val onShowNow by rememberUpdatedState(onShow)
    val onNewNow by rememberUpdatedState(onNew)
    val haptics = haptics()

    val index by rememberUpdatedState(i)
    val report by rememberUpdatedState(onPosition)
    LaunchedEffect(Unit) {
        snapshotFlow { if (width == 0) index.toFloat() else index - offset / width }.collect { report?.invoke(it) }
    }

    val swipe = remember {
        object : Swipe {
            var from = 0f
            var dragging = false

            override fun drag(dx: Float) {
                if (making) return
                if (!dragging) {
                    dragging = true
                    settling[0]?.cancel()
                    from = offset
                    armed = false
                }
                val w = width.toFloat()
                val lone = neighbours.second == null
                offset = if (lone) (from + dx) * RESISTANCE else (from + dx).coerceIn(-w, w)
                val reached = if (lone) onNewNow != null && offset < -reach else abs(offset) > w * THRESHOLD
                if (reached != armed) {
                    armed = reached
                    haptics.threshold(reached)
                    if (lone) scope.launch { snap.animateTo(if (reached) give else 0f, SNAP) }
                }
            }

            override fun end(dx: Float, velocity: Float) {
                if (making) return
                dragging = false
                val w = width.toFloat()
                val (before, after) = neighbours
                if (after == null && armed) {
                    armed = false
                    making = true
                    onNewNow?.invoke()
                    settling[0] = scope.launch {
                        // On to the new pane's place, to wait for it; back, if it doesn't come.
                        val from = offset - snap.value
                        snap.snapTo(0f)
                        offset = from
                        animate(offset, -w, velocity, Motion.spatial()) { value, _ -> offset = value }
                        delay(NEW_PANE_WAIT_MS)
                        animate(offset, 0f, 0f, Motion.spatial()) { value, _ -> offset = value }
                        making = false
                    }
                    return
                }
                // A fling goes its way, back to the pane if that's where it points.
                val target = when {
                    after == null -> 0f
                    velocity < -FLING -> if (offset <= 0f) -w else 0f
                    velocity > FLING -> if (offset >= 0f) w else 0f
                    offset < -w * THRESHOLD -> -w
                    offset > w * THRESHOLD -> w
                    else -> 0f
                }
                if (target != 0f && !armed) haptics.threshold(true) // a fling short of the threshold
                settling[0] = scope.launch {
                    animate(offset, target, velocity, Motion.spatial()) { value, _ -> offset = value }
                    val to = when {
                        target < 0f -> after
                        target > 0f -> before
                        else -> null
                    } ?: return@launch
                    // Its view is already on screen: it becomes current in the same frame.
                    Snapshot.withMutableSnapshot {
                        current = to.id
                        offset = 0f
                    }
                    onShowNow(to)
                }
            }
        }
    }

    // With two panes the other is both neighbours; it waits on the side being uncovered.
    val uncoveringLeft by remember { derivedStateOf { offset > 0f } }
    val pages = buildList {
        add(here to 0)
        when {
            next == null -> Unit
            previous == next -> add(next to if (uncoveringLeft) -1 else 1)
            else -> {
                add(previous!! to -1)
                add(next to 1)
            }
        }
    }
    val shift = { offset - snap.value }
    Box(modifier.onSizeChanged { width = it.width }.clipToBounds()) {
        for ((pane, slot) in pages) {
            key(pane.id) {
                Box(Modifier.fillMaxSize().offset { IntOffset((slot * width + shift()).roundToInt(), 0) }) {
                    page(pane, slot == 0, swipe)
                }
            }
        }
        if (next == null && onNew != null) {
            Box(Modifier.fillMaxSize().offset { IntOffset((width + shift()).roundToInt(), 0) }) {
                NewPane(
                    lit = armed || making,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .graphicsLayer { alpha = (-shift() / reach).coerceIn(0f, 1f) }
                        .layout { measurable, constraints ->
                            // Kept in the middle of what the pane has uncovered.
                            val p = measurable.measure(constraints)
                            layout(p.width, p.height) {
                                val gap = (-shift()).coerceIn(0f, width.toFloat())
                                p.place(((gap - p.width) / 2).roundToInt(), 0)
                            }
                        },
                )
            }
        }
    }
}

/** Where a new pane would go: a plus and "new pane", reading up the edge it's at. */
@Composable
private fun NewPane(lit: Boolean, modifier: Modifier) {
    val ink = if (lit) Palette.grey else Palette.dim
    Row(modifier.upward(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GlyphIcon(Glyph.Plus, ink, size = 14.dp)
        Text("new pane", color = ink, style = Type.label, maxLines = 1)
    }
}

/** Turned a quarter left, so it reads from the bottom up, and laid out that way. */
private fun Modifier.upward() = layout { measurable, _ ->
    val p = measurable.measure(Constraints())
    layout(p.height, p.width) {
        p.placeWithLayer((p.height - p.width) / 2, (p.width - p.height) / 2) { rotationZ = -90f }
    }
}

private const val THRESHOLD = 0.2f // of the width, to move to the next pane without a fling
private const val FLING = 800f // px/s
private const val RESISTANCE = 0.3f // a lone pane gives a little, then springs back
private val NEW_PANE_REACH = 48.dp // how far a lone pane goes left, given, to make a new one
private val NEW_PANE_SNAP = 24.dp // and how much more it gives at once there
private const val NEW_PANE_WAIT_MS = 2500L // how long its place waits for the new pane
private val SNAP = spring<Float>(dampingRatio = 0.7f, stiffness = Spring.StiffnessHigh)
