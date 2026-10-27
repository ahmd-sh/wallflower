package sh.ahmd.wallflower.ui.composer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import sh.ahmd.wallflower.BarKey

/**
 * The key bar's sticky keys (pfx, ctrl): one pressed is [armed], waiting
 * for the next key, from the bar or the keyboard, to go with it. While one
 * is armed, [intercept] takes the next character typed into the box, or
 * [type] the next key typed straight into the pane.
 */
@Stable
internal class StickyKeys(private val onKey: (BarKey) -> Unit) {
    var armed by mutableStateOf<BarKey?>(null)
        private set

    fun press(key: BarKey) {
        val sticky = armed
        when {
            key.sticky -> armed = if (sticky == key) null else key
            sticky != null -> {
                armed = null
                onKey(sticky.then(key))
            }
            else -> onKey(key)
        }
    }

    @OptIn(ExperimentalFoundationApi::class) // the edit's changes
    private val interceptor = InputTransformation {
        val typed = buildString {
            for (i in 0 until changes.changeCount) {
                val range = changes.getRange(i)
                append(asCharSequence(), range.min, range.max)
            }
        }
        revertAllChanges()
        val c = typed.firstOrNull()
        if (c == null) armed = null else press(BarKey.typed(c))
    }

    val intercept: InputTransformation? get() = if (armed != null) interceptor else null

    /**
     * What's typed straight into the pane, the keyboard's return key being
     * [returnKeys]: with one armed, its first key goes with it; text
     * otherwise goes to [onText].
     */
    fun type(typed: Typed, returnKeys: List<String>, onText: (String) -> Unit) {
        when (typed) {
            is Typed.Key -> press(BarKey(typed.name, listOf(typed.name)))
            Typed.Return -> press(BarKey("⏎", returnKeys))
            is Typed.Text -> {
                if (armed == null) return onText(typed.text)
                press(BarKey.typed(typed.text[0]))
                if (typed.text.length > 1) onText(typed.text.substring(1))
            }
        }
    }
}

@Composable
internal fun rememberStickyKeys(onKey: (BarKey) -> Unit): StickyKeys {
    val latest by rememberUpdatedState(onKey)
    return remember { StickyKeys { latest(it) } }
}
