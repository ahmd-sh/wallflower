package sh.ahmd.wallflower

import android.content.SharedPreferences
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A message sent before, [entry], being edited in a pane's box in place of what was there, [stash]. */
@Serializable
data class Editing(val entry: String, val stash: String)

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

    /** Messages being edited, by pane. */
    val editing = mutableStateMapOf<String, Editing>().apply {
        putAll(runCatching { Json.decodeFromString<Map<String, Editing>>(prefs.getString(EDITING, null) ?: "{}") }.getOrDefault(emptyMap()))
    }

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

    /**
     * Puts [text] in at [pane]'s cursor as a word of its own, as a file
     * dropped on a terminal is, and the cursor on the line after it, to go on
     * writing; in the middle of a line, just after it.
     */
    fun insert(pane: String, text: String) {
        of(pane).edit {
            val at = selection.max
            val s = asCharSequence()
            val before = if (at > 0 && !s[at - 1].isWhitespace()) " " else ""
            val after = when {
                at == length -> "\n"
                s[at] == '\n' -> ""
                s[at].isWhitespace() -> ""
                else -> " "
            }
            replace(at, at, before + text + after)
            val end = at + before.length + text.length + after.length
            selection = TextRange(if (after.isEmpty() && end < length && asCharSequence()[end] == '\n') end + 1 else end)
        }
    }

    /** Puts [text], the message [entry], in [pane]'s box to edit, setting aside what's there until [endEdit]. */
    fun edit(pane: String, entry: String, text: String) {
        val box = of(pane)
        editing[pane] = Editing(entry, editing[pane]?.stash ?: box.text.toString())
        saveEditing()
        box.setTextAndPlaceCursorAtEnd(text)
    }

    /** Stops editing in [pane], putting back what was set aside. */
    fun endEdit(pane: String) {
        val e = editing.remove(pane) ?: return
        saveEditing()
        of(pane).setTextAndPlaceCursorAtEnd(e.stash)
    }

    private fun saveEditing() = prefs.edit().putString(EDITING, Json.encodeToString(editing.toMap())).apply()

    private companion object {
        const val KEY = "drafts"
        const val EDITING = "editing"
        const val SAVE_DELAY_MS = 400L
    }
}
