package sh.ahmd.wallflower.ui.composer

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import sh.ahmd.wallflower.BarKey
import sh.ahmd.wallflower.KeyBar
import sh.ahmd.wallflower.ui.Glyph
import sh.ahmd.wallflower.ui.GlyphIcon
import sh.ahmd.wallflower.ui.Keep
import sh.ahmd.wallflower.ui.LocalFloating
import sh.ahmd.wallflower.ui.Mono
import sh.ahmd.wallflower.ui.Motion
import sh.ahmd.wallflower.ui.Palette
import sh.ahmd.wallflower.ui.faceOf
import sh.ahmd.wallflower.ui.frosted
import sh.ahmd.wallflower.ui.glyphs
import sh.ahmd.wallflower.ui.haptics

/**
 * The arrows key's pad ([BarKey.cluster]): pressed, the key brings it up
 * just above itself, ⏎ ↑ ⌫ over ← ↓ →, frosted so what's behind shows. It
 * stays up while the pane's typed into, till its × puts it away. Dragged by
 * the bar along its top, it stays where it's let go; the key, pressed while
 * it's away, brings it back, and pressed again puts it away.
 */
@Stable
internal class ArrowPad {
    var open by mutableStateOf(false)
        private set

    /** Its keys: the arrows key's. */
    var key by mutableStateOf(KeyBar.ARROWS)
        private set

    /** Where the arrows key is, in the window: the pad comes up above it. */
    var home by mutableStateOf(Rect.Zero)

    /** Where it was dragged to, its top left in the window; null while it's above the key. */
    var at by mutableStateOf<Offset?>(null)

    /** The arrows key pressed. */
    fun press(key: BarKey) {
        this.key = key
        when {
            !open -> open = true
            at != null -> at = null
            else -> open = false
        }
    }

    fun close() {
        open = false
        at = null
    }
}

/**
 * [pad], while it's open, over everything; its keys go to [onKey]. It grows
 * out of the arrows key, and its × shrinks it back into the key, or where it
 * was moved to, in place. Dragged, it ticks softly as it goes, as a fine
 * dial does.
 */
@Composable
internal fun ArrowPadUp(pad: ArrowPad, onKey: (BarKey) -> Unit) {
    val up = remember { MutableTransitionState(false) }
    up.targetState = pad.open
    if (!up.currentState && !up.targetState) return // put away, and done shrinking
    val haptics = haptics()
    val press by rememberUpdatedState(onKey)
    fun send(i: Int, again: Boolean) {
        val k = pad.key.keys.getOrNull(i) ?: return
        if (again) haptics.repeat() else haptics.key()
        press(BarKey(KeyBar.labelOf(listOf(k)), listOf(k)))
    }
    Keep {
        val host = LocalFloating.current
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val insets = WindowInsets.safeDrawing // the bars, and the keyboard while it's up
        var dragging by remember { mutableStateOf(false) }
        val left = remember { Spot() }
        val grow = rememberTransition(up, label = "pad")
        val scale by grow.animateFloat({ if (targetState) Motion.bounce() else Motion.effect() }, label = "scale") { if (it) 1f else SEED }
        val fade by grow.animateFloat({ Motion.effect() }, label = "fade") { if (it) 1f else 0f }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val size = with(density) { IntSize(PAD_WIDTH.roundToPx(), PAD_HEIGHT.roundToPx()) } // known before it's drawn, so it starts where it should
            val origin = host.at?.takeIf { it.isAttached }?.windowToLocal(Offset.Zero) ?: Offset.Zero
            val leftmost = insets.getLeft(density, direction).toFloat()
            val top = insets.getTop(density).toFloat()
            val right = (constraints.maxWidth - insets.getRight(density, direction) - size.width).toFloat().coerceAtLeast(leftmost)
            val bottom = (constraints.maxHeight - insets.getBottom(density) - size.height).toFloat().coerceAtLeast(top)
            val clamp = { o: Offset -> Offset(o.x.coerceIn(leftmost, right), o.y.coerceIn(top, bottom)) }
            val key = pad.home.center + origin // the arrows key's middle
            val home = with(density) { Offset(key.x - size.width / 2f, pad.home.top + origin.y - GAP.toPx() - size.height) }
            // Put away, it forgets where it was moved to, but shrinks away from there.
            val target = if (pad.open) clamp(pad.at?.plus(origin) ?: home) else left.at
            val atHome = if (pad.open) pad.at == null else left.home
            SideEffect {
                if (pad.open) {
                    left.at = target
                    left.home = atHome
                }
            }
            val shown by animateOffsetAsState(target, if (dragging) snap() else Motion.spatial(), label = "pad")
            val now by rememberUpdatedState(shown)
            val shift by rememberUpdatedState(origin)
            val bounds by rememberUpdatedState(clamp)
            Column(
                Modifier
                    .offset { shown.round() }
                    .size(PAD_WIDTH, PAD_HEIGHT)
                    .graphicsLayer {
                        alpha = fade
                        scaleX = scale
                        scaleY = scale
                        // Above the key it grows from the key's middle; moved away, from its own.
                        transformOrigin = if (atHome) TransformOrigin((key.x - shown.x) / size.width, (key.y - shown.y) / size.height) else TransformOrigin.Center
                    }
                    .clip(PANEL)
                    .frosted(10.dp)
                    .background(Palette.bg1.copy(alpha = 0.55f))
                    .border(1.dp, Palette.border, PANEL)
                    .padding(start = INSET, end = INSET, bottom = INSET),
            ) {
                // The bar it's dragged by, and its ×.
                Box(
                    Modifier
                        .width(PLACE * 3 + KEY_GAP * 2)
                        .height(BAR)
                        .pointerInput(Unit) {
                            var to = Offset.Zero
                            var travelled = 0f
                            val notch = DETENT.toPx()
                            detectDragGestures(
                                onDragStart = {
                                    dragging = true
                                    to = now
                                    travelled = 0f
                                    haptics.tick()
                                },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                            ) { change, by ->
                                change.consume()
                                val was = to
                                to = bounds(to + by)
                                pad.at = to - shift
                                travelled += (to - was).getDistance() // as far as it moved: held at an edge, it's still
                                if (travelled >= notch) {
                                    travelled %= notch
                                    haptics.detent()
                                }
                            }
                        },
                ) {
                    Box(Modifier.align(Alignment.Center).size(28.dp, 4.dp).clip(CircleShape).background(Palette.grey.copy(alpha = 0.6f)))
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .size(BAR)
                            .clip(CircleShape)
                            .clickable {
                                haptics.tick()
                                pad.close()
                            },
                        contentAlignment = Alignment.Center,
                    ) { GlyphIcon(Glyph.Close, Palette.grey, size = 10.dp) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                    for (row in PLACES) {
                        Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                            for (i in row) {
                                val name = pad.key.keys.getOrNull(i)
                                if (name == null) Spacer(Modifier.size(PLACE)) else Place(name) { again -> send(i, again) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Where the pad last was while it was open, and whether that was above its key. */
private class Spot {
    var at = Offset.Zero
    var home = true
}

/** A key of the pad: it sends on tap, and the arrows repeat while held. */
@Composable
private fun Place(name: String, onPress: (again: Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val press by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }
    val repeats = name in KeyBar.repeating
    val ink = Palette.fg
    Box(
        Modifier
            .size(PLACE)
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) Palette.bg3.copy(alpha = 0.85f) else Palette.bg2.copy(alpha = 0.5f))
            .pointerInput(name) {
                detectTapGestures(onPress = {
                    pressed = true
                    var held = false
                    val hold = if (!repeats) null else scope.holding(true, onHeld = { held = true }, fire = { press(it) })
                    val released = tryAwaitRelease()
                    hold?.cancel()
                    pressed = false
                    if (released && !held) press(false)
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(faceOf(listOf(name)), color = ink, fontFamily = Mono, fontSize = 18.sp, inlineContent = glyphs(ink))
    }
}

/** Where the pad's keys go, row by row, by their place in it ([BarKey]): top left, up, top right; left, down, right. */
private val PLACES = listOf(listOf(2, 0, 5), listOf(1, 4, 3))
private val PLACE = 46.dp
private val KEY_GAP = 4.dp
private val BAR = 22.dp
private val GAP = 16.dp // above the key, clear of the composer's edge
private val INSET = 6.dp
private val PAD_WIDTH = PLACE * 3 + KEY_GAP * 2 + INSET * 2
private val PAD_HEIGHT = BAR + PLACE * 2 + KEY_GAP + INSET
private val PANEL = RoundedCornerShape(18.dp)

/** How small it is as it starts to grow: about the key's size. */
private const val SEED = 0.25f

/** How far it moves between detents. */
private val DETENT = 14.dp
