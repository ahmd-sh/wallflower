package sh.ahmd.wallflower.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.floor
import kotlinx.coroutines.flow.collectLatest

/** Switches a pane between its terminal and its read view. */
@Composable
internal fun ViewToggle(reading: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.bg1)
            .border(1.dp, Palette.border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Crossfade(reading, animationSpec = Motion.effect(), label = "view") {
            GlyphIcon(if (it) Glyph.Terminal else Glyph.Chat, Palette.fg, size = 14.dp)
        }
    }
}

/**
 * Which of the window's [count] panes is showing: swipe the terminal sideways
 * for the others, tap for all of them. The light follows [position] as panes
 * move, stretching from dot to dot, and glides when the pane changes at once.
 */
@Composable
internal fun PaneDots(count: Int, position: () -> Float, onClick: () -> Unit) {
    val n = count.coerceAtLeast(1).toFloat()
    val at = remember { Animatable(position()) }
    LaunchedEffect(count) {
        snapshotFlow { position() }.collectLatest { to ->
            val from = at.value
            var d = (to - from).mod(n)
            if (d > n / 2f) d -= n
            if (abs(d) <= 0.5f) {
                at.snapTo(from + d) // following a swipe
            } else {
                at.snapTo(from.mod(n))
                at.animateTo(to.mod(n), Motion.spatial())
            }
        }
    }
    val dot = 6.dp
    val gap = 5.dp
    Canvas(
        Modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.bg1)
            .border(1.dp, Palette.border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .size(width = dot * count + gap * (count - 1).coerceAtLeast(0), height = dot),
    ) {
        val d = dot.toPx()
        val step = d + gap.toPx()
        val r = CornerRadius(d / 2)
        for (i in 0 until count) drawRoundRect(Palette.bg3, Offset(i * step, 0f), Size(d, d), r)
        val pos = at.value.mod(n)
        val i = floor(pos).toInt().coerceIn(0, count - 1)
        val f = pos - floor(pos)
        if (i == count - 1 && f > 0f) {
            // Round the end: the light leaves the last dot as it comes back at the first.
            drawRoundRect(Palette.fg, Offset(i * step, 0f), Size(d, d), r, alpha = 1f - f)
            drawRoundRect(Palette.fg, Offset(0f, 0f), Size(d, d), r, alpha = f)
        } else {
            val left = i * step + step * (f * 2f - 1f).coerceIn(0f, 1f)
            val right = i * step + d + step * (f * 2f).coerceIn(0f, 1f)
            drawRoundRect(Palette.fg, Offset(left, 0f), Size(right - left, d), r)
        }
    }
}
