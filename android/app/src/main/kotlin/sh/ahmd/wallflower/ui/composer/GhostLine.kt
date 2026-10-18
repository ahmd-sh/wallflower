package sh.ahmd.wallflower.ui.composer

import androidx.compose.animation.core.animate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.ahmd.wallflower.ui.Glyph
import sh.ahmd.wallflower.ui.GlyphIcon
import sh.ahmd.wallflower.ui.Mono
import sh.ahmd.wallflower.ui.Motion
import sh.ahmd.wallflower.ui.Palette
import sh.ahmd.wallflower.ui.haptics

/**
 * The app's line, or what it suggests typing ([suggested]), over the empty
 * box. A tap is the box's ([onTap]), to type afresh; swiped right it fills
 * with colour as it goes, and once full (a tick) it comes in to edit
 * ([onTake]). The app's own line has × to clear it there ([onClear]).
 */
@Composable
internal fun BoxScope.GhostLine(text: String, suggested: Boolean, onClear: (() -> Unit)?, onTap: () -> Unit, onTake: () -> Unit) {
    val haptics = haptics()
    val clearable = !suggested && onClear != null
    val reach = with(LocalDensity.current) { TAKE_REACH.toPx() }
    var pulled by remember { mutableFloatStateOf(0f) }
    var past by remember { mutableStateOf(false) }
    val base = if (suggested) Palette.dim else Palette.grey
    val fill = (pulled / reach).coerceIn(0f, 1f)
    val filled = Brush.horizontalGradient(
        0f to Palette.fg,
        (fill - FILL_EDGE).coerceAtLeast(0f) to Palette.fg,
        (fill + FILL_EDGE).coerceAtMost(1f) to base,
        1f to base,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(end = if (clearable) 30.dp else 0.dp)
            .pointerInput(Unit) { detectTapGestures { onTap() } }
            .draggable(
                rememberDraggableState { dx ->
                    pulled = (pulled + dx).coerceIn(0f, reach * 1.5f)
                    if ((pulled >= reach) != past) {
                        past = !past
                        haptics.threshold(past)
                    }
                },
                Orientation.Horizontal,
                onDragStopped = {
                    if (past) onTake()
                    past = false
                    animate(pulled, 0f, animationSpec = Motion.spatial()) { v, _ -> pulled = v }
                },
            ),
        verticalAlignment = Alignment.Top,
    ) {
        if (suggested) GlyphIcon(Glyph.Spark, if (fill > 0f) Palette.fg else base, Modifier.padding(top = 5.dp, end = 8.dp), size = 11.dp)
        Text(
            text,
            style = TextStyle(brush = if (fill > 0f) filled else SolidColor(base), fontFamily = Mono, fontSize = 15.sp, lineHeight = 21.sp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (clearable && onClear != null) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .clip(CircleShape)
                .clickable {
                    haptics.confirm()
                    onClear()
                }
                .padding(4.dp),
        ) { GlyphIcon(Glyph.Close, Palette.grey, size = 13.dp) }
    }
}

private val TAKE_REACH = 72.dp // how far a swipe takes the app's line or suggestion
private const val FILL_EDGE = 0.04f // the softness of the fill's edge, of the text's width
