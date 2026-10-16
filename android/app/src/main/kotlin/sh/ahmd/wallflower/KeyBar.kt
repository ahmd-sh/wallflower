package sh.ahmd.wallflower

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A key on the bar: what it shows, and the tmux keys it sends (`Escape`, `C-c`, or literal text). */
@Serializable
data class BarKey(val label: String, val keys: List<String>)

/** The bar for some programs. */
@Serializable
data class KeyProfile(
    val name: String,
    /** As panes report them: claude, zsh, hntui. */
    val programs: List<String> = emptyList(),
    val keys: List<BarKey>,
    /** What the send button sends when the box is empty. */
    val stop: List<String> = listOf("C-c"),
)

/**
 * Which keys sit next to the composer. The focused pane's program picks the
 * profile, first match wins, else [default]; [zoomedOut], if set, wins while
 * looking at a whole window.
 */
@Serializable
data class KeyBar(
    val profiles: List<KeyProfile>,
    val default: KeyProfile,
    val zoomedOut: KeyProfile? = null,
) {
    fun profileFor(program: String, zoomedOut: Boolean = false): KeyProfile =
        this.zoomedOut.takeIf { zoomedOut }
            ?: profiles.firstOrNull { p -> p.programs.any { it.equals(program, ignoreCase = true) } }
            ?: default

    companion object {
        /** Keys offered when editing a bar. */
        val common = listOf(
            "esc" to "Escape", "tab" to "Tab", "⇧tab" to "BTab", "⏎" to "Enter",
            "↑" to "Up", "↓" to "Down", "←" to "Left", "→" to "Right",
            "home" to "Home", "end" to "End", "pgup" to "PageUp", "pgdn" to "PageDown",
            "⌫" to "BSpace", "space" to "Space", "⌥⏎" to "M-Enter",
            "^c" to "C-c", "^d" to "C-d", "^z" to "C-z", "^r" to "C-r", "^l" to "C-l",
            "^a" to "C-a", "^e" to "C-e", "^u" to "C-u", "^w" to "C-w", "^o" to "C-o",
            "^t" to "C-t", "^b" to "C-b",
            "y" to "y", "n" to "n", "q" to "q", "1" to "1", "2" to "2", "3" to "3",
            "/" to "/", "|" to "|", "~" to "~", "-" to "-", "`" to "`",
        ).map { (label, key) -> BarKey(label, listOf(key)) }

        /** Held down, these repeat. */
        val repeating = setOf("Up", "Down", "Left", "Right", "BSpace", "PageUp", "PageDown")

        private fun keys(vararg labels: String) = labels.map { l -> common.first { it.label == l } }

        val defaults = KeyBar(
            profiles = listOf(
                KeyProfile("claude code", listOf("claude"), keys("esc", "⇧tab", "↑", "↓", "⏎", "1", "2", "3", "^o", "^c"), stop = listOf("Escape")),
                KeyProfile("shell", listOf("zsh", "bash", "fish", "sh"), keys("tab", "↑", "↓", "←", "→", "^r", "^c", "^d", "esc")),
            ),
            default = KeyProfile("default", keys = keys("esc", "tab", "⇧tab", "^c", "↑", "↓", "←", "→", "⏎")),
        )
    }
}

class KeyBarStore(private val prefs: SharedPreferences) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _bar = MutableStateFlow(
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString<KeyBar>(it) }.getOrNull() } ?: KeyBar.defaults,
    )
    val bar: StateFlow<KeyBar> = _bar.asStateFlow()

    fun update(change: (KeyBar) -> KeyBar) {
        val bar = change(_bar.value)
        _bar.value = bar
        prefs.edit().putString(KEY, json.encodeToString(bar)).apply()
    }

    fun reset() {
        _bar.value = KeyBar.defaults
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "keybar"
    }
}
