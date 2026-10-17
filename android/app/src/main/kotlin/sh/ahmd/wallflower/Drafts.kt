package sh.ahmd.wallflower

import android.content.SharedPreferences
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * What's typed in the composer, per pane. It outlives reconnects, locking the
 * phone and the app being killed, and takes back text a dropped connection
 * may not have delivered. Used on the main thread.
 */
class Drafts(private val prefs: SharedPreferences, private val scope: CoroutineScope) {
    private val saved: MutableMap<String, String> =
        runCatching { Json.decodeFromString<Map<String, String>>(prefs.getString(KEY, null) ?: "{}") }
            .getOrDefault(emptyMap())
            .toMutableMap()
    private val states = HashMap<String, TextFieldState>()

    fun of(pane: String): TextFieldState = states.getOrPut(pane) {
        TextFieldState(saved[pane].orEmpty()).also { state ->
            scope.launch {
                snapshotFlow { state.text.toString() }.drop(1).collectLatest {
                    delay(SAVE_DELAY_MS)
                    if (it.isEmpty()) saved.remove(pane) else saved[pane] = it
                    prefs.edit().putString(KEY, Json.encodeToString(saved)).apply()
                }
            }
        }
    }

    /** Puts back text that may not have been sent, ahead of anything typed since. */
    fun restore(pane: String, text: String) {
        of(pane).edit {
            if (length > 0) replace(0, 0, text + "\n") else append(text)
            placeCursorAtEnd()
        }
    }

    /** Puts [text] in at [pane]'s cursor as a word of its own, as a file dropped on a terminal is. */
    fun insert(pane: String, text: String) {
        of(pane).edit {
            val at = selection.max
            val s = asCharSequence()
            val before = if (at > 0 && !s[at - 1].isWhitespace()) " " else ""
            val after = if (at < length && s[at].isWhitespace()) "" else " "
            replace(at, at, before + text + after)
            selection = TextRange(at + before.length + text.length + after.length)
        }
    }

    private companion object {
        const val KEY = "drafts"
        const val SAVE_DELAY_MS = 400L
    }
}
