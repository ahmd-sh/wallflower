package sh.ahmd.wallflower.prefs

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How wallflower's own words look: in the terminal's font with [x] marks, or Bricolage Grotesque with checkboxes. */
enum class Look { Terminal, Grotesque }

/** A setting kept on the phone: [flow] to watch it, [set] when the user changes it. */
class Pref<T> internal constructor(initial: T, private val save: (T) -> Unit) {
    private val state = MutableStateFlow(initial)
    val flow: StateFlow<T> = state.asStateFlow()
    val value: T get() = state.value

    fun set(value: T) {
        state.value = value
        save(value)
    }
}

/** What the user chose about how wallflower looks and behaves, kept on the phone. */
class Preferences(private val prefs: SharedPreferences) {
    val look = pref(LOOK, Look.Grotesque, { Look.entries.firstOrNull { l -> l.name == getString(LOOK, null) } }, { putString(LOOK, it.name) })

    /** Whether touches answer with haptics (and the phone's touch feedback is on). */
    val haptics = boolean(HAPTICS, true)

    /** Whether closing a pane asks first; "don't ask again" turns it off, settings back on. */
    val askClosingPane = boolean(ASK_PANE, true)

    /** Whether closing a window (or its last pane) asks first. */
    val askClosingWindow = boolean(ASK_WINDOW, true)

    /** Panes whose program can be read show their read view rather than the terminal: one switch, for every pane. */
    val readView = boolean(READ_VIEW, prefs.getStringSet(OLD_READ_PANES, null).orEmpty().isNotEmpty())

    init {
        // Read views used to be switched per pane, and offered or not.
        if (prefs.contains(OLD_READ_PANES) || prefs.contains(OLD_OFFERED)) {
            prefs.edit().putBoolean(READ_VIEW, readView.value).remove(OLD_READ_PANES).remove(OLD_OFFERED).apply()
        }
    }

    private fun boolean(key: String, default: Boolean) =
        pref(key, default, { if (contains(key)) getBoolean(key, default) else null }, { putBoolean(key, it) })

    private fun <T> pref(key: String, default: T, read: SharedPreferences.() -> T?, write: SharedPreferences.Editor.(T) -> Unit) =
        Pref(prefs.read() ?: default) { v -> prefs.edit().apply { write(v) }.apply() }

    private companion object {
        const val LOOK = "look"
        const val HAPTICS = "haptics"
        const val ASK_PANE = "confirm_close_pane"
        const val ASK_WINDOW = "confirm_close_window"
        const val READ_VIEW = "read_view"
        const val OLD_READ_PANES = "conversation_panes"
        const val OLD_OFFERED = "conversations_offered"
    }
}
