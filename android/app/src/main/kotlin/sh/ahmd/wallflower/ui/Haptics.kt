package sh.ahmd.wallflower.ui

import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.pow

/**
 * What a touch feels like, named by what happened rather than by effect, so
 * each kind feels the same wherever it happens. Quiet when settings turn it
 * off, and when the phone's touch feedback is off.
 */
class Haptics(private val view: View, private val on: StateFlow<Boolean>) {
    private val vibrator: Vibrator? = view.context.getSystemService(VibratorManager::class.java)?.defaultVibrator

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

    /**
     * Something sliding open or shut over [millis]: a soft swell that peaks
     * as it moves fastest and eases off as it settles. Where the phone can't
     * shape one, a few light ticks over the same time; failing that, one.
     */
    fun slide(millis: Long) {
        val v = vibrator
        if (!on.value || v == null || millis <= 0) return
        val effect = envelope(v, millis) ?: ticks(v, millis) ?: return play(HapticFeedbackConstants.SEGMENT_TICK)
        v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
    }

    private fun envelope(v: Vibrator, millis: Long): VibrationEffect? {
        if (android.os.Build.VERSION.SDK_INT < 36 || !v.areEnvelopeEffectsSupported()) return null
        val info = v.envelopeEffectInfo
        val step = maxOf(info.minControlPointDurationMillis, millis / SLIDE_POINTS)
        val points = (millis / step).toInt().coerceIn(2, info.maxSize - 1)
        val e = VibrationEffect.BasicEnvelopeBuilder().setInitialSharpness(SLIDE_SHARPNESS)
        for (i in 1 until points) e.addControlPoint(SLIDE_PEAK * swell(i.toFloat() / points), SLIDE_SHARPNESS, step)
        return e.addControlPoint(0f, SLIDE_SHARPNESS, step).build()
    }

    private fun ticks(v: Vibrator, millis: Long): VibrationEffect? {
        if (!v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)) return null
        val c = VibrationEffect.startComposition()
        var at = 0L
        for (u in floatArrayOf(0.08f, 0.22f, 0.42f, 0.68f)) {
            val t = (millis * u).toLong()
            c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, swell(u), (t - at).toInt())
            at = t
        }
        return c.compose()
    }

    /** How strongly it's felt [u] of the way through: up to all of it early on, then easing off to almost nothing. */
    private fun swell(u: Float): Float {
        val x = u / SWELL_PEAK
        return (x * kotlin.math.exp(1f - x)).toDouble().pow(0.7).toFloat()
    }

    private fun play(effect: Int) {
        if (on.value) view.performHapticFeedback(effect)
    }
}

private const val SLIDE_POINTS = 12L
private const val SLIDE_PEAK = 0.32f // of the motor's strength: gentle
private const val SLIDE_SHARPNESS = 0.2f // low: soft and round rather than crisp
private const val SWELL_PEAK = 0.18f // of the way through

val LocalHaptics = staticCompositionLocalOf<Haptics?> { null }

/** The app's haptics, or always-on ones bound to this view where none were provided (previews). */
@Composable
fun haptics(): Haptics {
    val view = LocalView.current
    return LocalHaptics.current ?: remember(view) { Haptics(view, MutableStateFlow(true)) }
}
