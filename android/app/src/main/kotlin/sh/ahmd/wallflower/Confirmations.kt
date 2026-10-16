package sh.ahmd.wallflower

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether closing a pane, or a window, asks first. "Don't ask again" turns one off; settings turns it back on. */
class Confirmations(private val prefs: SharedPreferences) {
    private val _panes = MutableStateFlow(prefs.getBoolean(PANES, true))
    val panes: StateFlow<Boolean> = _panes.asStateFlow()

    private val _windows = MutableStateFlow(prefs.getBoolean(WINDOWS, true))
    val windows: StateFlow<Boolean> = _windows.asStateFlow()

    fun askForPanes(ask: Boolean) {
        _panes.value = ask
        prefs.edit().putBoolean(PANES, ask).apply()
    }

    fun askForWindows(ask: Boolean) {
        _windows.value = ask
        prefs.edit().putBoolean(WINDOWS, ask).apply()
    }

    private companion object {
        const val PANES = "confirm_close_pane"
        const val WINDOWS = "confirm_close_window"
    }
}
