package sh.ahmd.wallflower.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
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
}
