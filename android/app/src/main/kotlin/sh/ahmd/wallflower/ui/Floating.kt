package sh.ahmd.wallflower.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Where things float over [content] in its own window, rather than in a
 * popup's, so they can see what's beneath them: [content] is drawn into a
 * [backdrop] as well as on screen, for them to blur ([frosted]). One thing
 * floats at a time ([FloatAbove]); a touch outside it puts it away and goes
 * on to what's under it.
 */
@Composable
internal fun FloatingHost(content: @Composable () -> Unit) {
    val backdrop = rememberGraphicsLayer()
    val host = remember(backdrop) { Floating(backdrop) }
    CompositionLocalProvider(LocalFloating provides host) { Hosting(host, content) }
}

@Composable
private fun Hosting(host: Floating, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { host.at = it }
            .pointerInput(host) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val shown = host.shown ?: return@awaitEachGesture
                    if (!host.bounds.contains(down.position)) shown.onDismiss()
                }
            },
    ) {
        Box(
            Modifier.fillMaxSize().drawWithContent {
                host.backdrop.record { this@drawWithContent.drawContent() }
                drawLayer(host.backdrop)
            },
        ) { content() }
        host.shown?.let { shown ->
            Box(
                Modifier
                    .layout { measurable, constraints ->
                        val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            val to = coordinates?.windowToLocal(shown.anchor.topLeft) ?: return@layout
                            val x = (to.x + shown.anchor.width / 2 - p.width / 2).roundToInt().coerceIn(0, (constraints.maxWidth - p.width).coerceAtLeast(0))
                            p.place(x, (to.y - shown.gap.toPx() - p.height).roundToInt())
                        }
                    }
                    .onGloballyPositioned { c -> host.at?.takeIf { it.isAttached }?.let { host.bounds = it.localBoundingBoxOf(c) } },
            ) { shown.content() }
        }
    }
}

@Stable
internal class Floating(val backdrop: GraphicsLayer) {
    var shown by mutableStateOf<Shown?>(null)
    var at: LayoutCoordinates? = null
    var bounds = Rect.Zero
}

/** What floats: [content], [gap] above [anchor] (in the window), and what a touch outside it does. */
internal class Shown(anchor: Rect, val gap: Dp, val onDismiss: () -> Unit, content: @Composable () -> Unit) {
    var anchor by mutableStateOf(anchor)
    var content by mutableStateOf(content)
}

internal val LocalFloating = staticCompositionLocalOf<Floating> { error("nothing floats outside a FloatingHost") }

/** [content] floating [gap] above [anchor] (its bounds in the window), while this is composed. */
@Composable
internal fun FloatAbove(anchor: Rect, gap: Dp, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val host = LocalFloating.current
    val dismiss by rememberUpdatedState(onDismiss)
    val shown = remember { Shown(anchor, gap, { dismiss() }, content) }
    SideEffect {
        shown.anchor = anchor
        shown.content = content
    }
    DisposableEffect(host, shown) {
        host.shown = shown
        onDispose { if (host.shown === shown) host.shown = null }
    }
}

/** Behind what follows, what's beneath it blurred by [radius]: frosted glass, in the shape of a clip before it. */
@Composable
internal fun Modifier.frosted(radius: Dp = 12.dp): Modifier {
    val host = LocalFloating.current
    val blur = rememberGraphicsLayer()
    var at by remember { mutableStateOf(Offset.Zero) } // where it is over the backdrop
    return this
        .onGloballyPositioned { c -> host.at?.takeIf { it.isAttached }?.let { at = it.localPositionOf(c, Offset.Zero) } }
        .drawBehind {
            val r = radius.toPx()
            val pad = ceil(r * 2) // what's just outside goes into the blur too
            if (blur.renderEffect == null) blur.renderEffect = BlurEffect(r, r, TileMode.Clamp)
            blur.record(IntSize((size.width + pad * 2).roundToInt(), (size.height + pad * 2).roundToInt())) {
                translate(pad - at.x, pad - at.y) { drawLayer(host.backdrop) }
            }
            translate(-pad, -pad) { drawLayer(blur) }
        }
}
