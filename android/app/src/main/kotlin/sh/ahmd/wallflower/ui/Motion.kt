package sh.ahmd.wallflower.ui

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring

/**
 * How things move: springs, so a touch can interrupt an animation midway
 * and it carries on from where it is, never blocking what the touch does.
 * Things that move or change size use [spatial]; fades and colors the
 * quicker [effect]. Both follow the system's animation speed setting.
 */
object Motion {
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    fun <T> effect(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

    /** Something that arrives with a little give: past where it stops, and back. */
    fun <T> bounce(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)

    /** How long [spatial] takes to move something [distance] pixels, until it's within a pixel of where it stops. */
    fun settleMillis(distance: Float): Long =
        spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow, visibilityThreshold = 1f)
            .vectorize(Float.VectorConverter)
            .getDurationNanos(AnimationVector1D(0f), AnimationVector1D(distance), AnimationVector1D(0f)) / 1_000_000
}
