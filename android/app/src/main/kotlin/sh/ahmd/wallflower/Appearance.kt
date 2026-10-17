package sh.ahmd.wallflower

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How wallflower's own words look: in the terminal's font with [x] marks, or Bricolage Grotesque with checkboxes. */
enum class Look { Terminal, Grotesque }

/** How wallflower looks, and feels: whether touches answer with haptics. */
class Appearance(private val prefs: SharedPreferences) {
    private val _look = MutableStateFlow(Look.entries.firstOrNull { it.name == prefs.getString(LOOK, null) } ?: Look.Grotesque)
    val look: StateFlow<Look> = _look.asStateFlow()

    fun use(look: Look) {
        _look.value = look
        prefs.edit().putString(LOOK, look.name).apply()
    }

    private val _haptics = MutableStateFlow(prefs.getBoolean(HAPTICS, true))
    val haptics: StateFlow<Boolean> = _haptics.asStateFlow()

    fun feel(on: Boolean) {
        _haptics.value = on
        prefs.edit().putBoolean(HAPTICS, on).apply()
    }

    private companion object {
        const val LOOK = "look"
        const val HAPTICS = "haptics"
    }
}
