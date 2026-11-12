package sh.ahmd.wallflower.ui.mobile

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Where a band of light passing along something, over and over, is: from
 * wholly before it (-0.5) to wholly past it (1.5), by its width. None while
 * it's not [live], or where animations are off.
 */
@Composable
internal fun sheenAt(live: Boolean): State<Float>? {
    val still by produceState(false) { value = (coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) <= 0f }
    if (!live || still) return null
    return rememberInfiniteTransition(label = "sheen").animateFloat(-0.5f, 1.5f, infiniteRepeatable(tween(1_600, easing = LinearEasing)), label = "sheen")
}

/** What it draws in [base], lit [light] where the band [at] is passing ([sheenAt]); all [base] with none. */
internal fun Modifier.sheen(at: State<Float>?, base: Color, light: Color) = graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val x = (at?.value ?: -0.5f) * size.width
        drawRect(Brush.horizontalGradient(listOf(base, light, base), startX = x - size.width / 3, endX = x + size.width / 3), blendMode = BlendMode.SrcIn)
    }
