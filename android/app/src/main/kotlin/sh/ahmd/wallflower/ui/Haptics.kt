package sh.ahmd.wallflower.ui

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What a touch feels like, named by what happened rather than by effect, so
 * each kind feels the same wherever it happens. Quiet when settings turn it
 * off, and when the phone's touch feedback is off.
 */
class Haptics(private val view: View, private val on: StateFlow<Boolean>) {
    /** A selection moved: a tab, a pane, an option. */
    fun tick() = play(HapticFeedbackConstants.SEGMENT_TICK)

    /** A drag crossed the point where letting go acts, either way. */
    fun threshold(reached: Boolean) =
        play(if (reached) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE)

    fun toggle(on: Boolean) = play(if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF)

    /** Something was done: sent, closed, copied. */
    fun confirm() = play(HapticFeedbackConstants.CONFIRM)

    /** Something didn't go through. */
    fun reject() = play(HapticFeedbackConstants.REJECT)

    fun hold() = play(HapticFeedbackConstants.LONG_PRESS)

    fun key() = play(HapticFeedbackConstants.KEYBOARD_TAP)

    /** A held key repeating: lighter, for many in a row. */
    fun repeat() = play(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)

    private fun play(effect: Int) {
        if (on.value) view.performHapticFeedback(effect)
    }
}

val LocalHaptics = staticCompositionLocalOf<Haptics?> { null }

/** The app's haptics, or always-on ones bound to this view where none were provided (previews). */
@Composable
fun haptics(): Haptics {
    val view = LocalView.current
    return LocalHaptics.current ?: remember(view) { Haptics(view, MutableStateFlow(true)) }
}
