package sh.ahmd.wallflower

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How settings pages look: the terminal's font with [x] marks, or Bricolage Grotesque with checkboxes. */
enum class Look { Terminal, Grotesque }

class Appearance(private val prefs: SharedPreferences) {
    private val _look = MutableStateFlow(Look.entries.firstOrNull { it.name == prefs.getString(LOOK, null) } ?: Look.Terminal)
    val look: StateFlow<Look> = _look.asStateFlow()

    fun use(look: Look) {
        _look.value = look
        prefs.edit().putString(LOOK, look.name).apply()
    }

    private companion object {
        const val LOOK = "look"
    }
}
