package sh.ahmd.wallflower.ui

import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import sh.ahmd.wallflower.protocol.Pane
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
 */
@Composable
fun PanePager(
    panes: List<Pane>,
    shown: Pane,
    onShow: (Pane) -> Unit,
    modifier: Modifier = Modifier,
    page: @Composable (pane: Pane, current: Boolean, swipe: Swipe) -> Unit,
) {
    var current by remember { mutableStateOf(shown.id) }
    var offset by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val settling = remember { arrayOfNulls<Job>(1) }

    // The shown pane changing elsewhere (a card, tmux) cuts straight to it.
    LaunchedEffect(shown.id) {
        if (shown.id != current) {
            settling[0]?.cancel()
            current = shown.id
            offset = 0f
        }
    }

    val i = panes.indexOfFirst { it.id == current }.takeIf { it >= 0 } ?: panes.indexOfFirst { it.id == shown.id }.coerceAtLeast(0)
    val here = panes.getOrNull(i) ?: shown
    val previous = if (panes.size > 1) panes[(i - 1).mod(panes.size)] else null
    val next = if (panes.size > 1) panes[(i + 1).mod(panes.size)] else null
    val neighbours by rememberUpdatedState(previous to next)
    val onShowNow by rememberUpdatedState(onShow)

    val swipe = remember {
        object : Swipe {
            var from = 0f
            var dragging = false

            override fun drag(dx: Float) {
                if (!dragging) {
                    dragging = true
                    settling[0]?.cancel()
                    from = offset
                }
                val w = width.toFloat()
                offset = if (neighbours.second == null) (from + dx) * RESISTANCE else (from + dx).coerceIn(-w, w)
            }

            override fun end(dx: Float, velocity: Float) {
                dragging = false
                val w = width.toFloat()
                val (before, after) = neighbours
                val target = when {
                    after == null -> 0f
                    velocity < -FLING || (velocity <= FLING && offset < -w * THRESHOLD) -> -w
                    velocity > FLING || offset > w * THRESHOLD -> w
                    else -> 0f
                }
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
    Box(modifier.onSizeChanged { width = it.width }.clipToBounds()) {
        for ((pane, slot) in pages) {
            key(pane.id) {
                Box(Modifier.fillMaxSize().offset { IntOffset((slot * width + offset).roundToInt(), 0) }) {
                    page(pane, slot == 0, swipe)
                }
            }
        }
    }
}

private const val THRESHOLD = 0.2f // of the width, to move to the next pane without a fling
private const val FLING = 800f // px/s
private const val RESISTANCE = 0.3f // a lone pane gives a little, then springs back
