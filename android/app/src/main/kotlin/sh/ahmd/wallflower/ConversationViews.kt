package sh.ahmd.wallflower

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Conversation views: whether panes whose program records its conversation
 * (Claude Code) offer one, and which panes show theirs over the terminal.
 */
class ConversationViews(private val prefs: SharedPreferences) {
    private val _offered = MutableStateFlow(prefs.getBoolean(OFFERED, true))
    val offered: StateFlow<Boolean> = _offered.asStateFlow()

    private val _panes = MutableStateFlow(prefs.getStringSet(PANES, null).orEmpty().toSet())
    val panes: StateFlow<Set<String>> = _panes.asStateFlow()

    fun offer(on: Boolean) {
        _offered.value = on
        prefs.edit().putBoolean(OFFERED, on).apply()
    }

    fun toggle(pane: String) {
        val next = if (pane in _panes.value) _panes.value - pane else (_panes.value + pane).toList().takeLast(MAX_PANES).toSet()
        _panes.value = next
        prefs.edit().putStringSet(PANES, next).apply()
    }

    private companion object {
        const val OFFERED = "conversations_offered"
        const val PANES = "conversation_panes"
        const val MAX_PANES = 64
    }
}
