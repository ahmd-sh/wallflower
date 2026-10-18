package sh.ahmd.wallflower.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity

/**
 * The height an area above the keyboard will settle at while the keyboard
 * slides, from where it says it'll stop as it starts to move, so terminals
 * learn how tall they'll be; 0 while it's still. [area] is its height now.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberSettleHeight(area: () -> Int): State<Int> {
    val settle = remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val target = WindowInsets.imeAnimationTarget
    val navigation = WindowInsets.navigationBars
    LaunchedEffect(Unit) {
        var lastTarget = -1
        snapshotFlow { target.getBottom(density) to ime.getBottom(density) }.collect { (to, now) ->
            fun above(inset: Int) = maxOf(inset - navigation.getBottom(density), 0) // imePadding's share of it
            settle.intValue = when {
                to == now -> 0
                settle.intValue == 0 || to != lastTarget -> area() + above(now) - above(to)
                else -> settle.intValue
            }
            lastTarget = to
        }
    }
    return settle
}
