package sh.ahmd.wallflower

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import sh.ahmd.wallflower.terminal.InputLine

/**
 * A key on the bar: what it shows, and the tmux keys it sends (`Escape`,
 * `C-c`, or literal text). A [sticky] key waits for the next key, from the
 * bar or the keyboard, and goes with it. With [bindings], tmux's key bindings
 * apply, as if typed on a keyboard attached to it; `prefix` is its prefix key.
 */
@Serializable
data class BarKey(
    val label: String,
    val keys: List<String>,
    val sticky: Boolean = false,
    val bindings: Boolean = false,
) {
    /** This sticky key with the next one: a modifier (`C-`, `M-`) joins it, anything else goes first. */
    fun then(next: BarKey): BarKey {
        val first = keys.firstOrNull().orEmpty()
        val joined = if (first.endsWith("-") && next.keys.isNotEmpty()) {
            listOf(first + next.keys[0]) + next.keys.drop(1)
        } else {
            keys + next.keys
        }
        return BarKey("$label ${next.label}", joined, bindings = bindings || next.bindings)
    }

    companion object {
        /** A character typed on the keyboard, as a key. */
        fun typed(c: Char) = BarKey(c.toString(), listOf(if (c == ' ') "Space" else c.toString()))
    }
}

/** The bar for some programs. */
@Serializable
data class KeyProfile(
    val name: String,
    /** As panes report them: claude, zsh, hntui. */
    val programs: List<String> = emptyList(),
    val keys: List<BarKey>,
    /** What the send button sends when the box is empty. */
    val stop: List<String> = listOf("C-c"),
    /** What empties the app's input line, sent once per row it takes and once more; null picks by program. */
    val clear: List<String>? = null,
) {
    fun clearKeys(): List<String> = clear ?: when {
        programs.any { it.equals("claude", ignoreCase = true) } -> listOf("C-u") // a row at a time
        programs.any { it.equals("zsh", ignoreCase = true) } -> listOf("C-x", "C-u") // kill-buffer, bound in the zshrc
        else -> emptyList()
    }

    /** Keys that empty [line], as last read, row by row. */
    fun clear(line: InputLine): List<String> = List(line.rows * 2 + 1) { clearKeys() }.flatten()
}

/**
 * Which keys sit next to the composer. The focused pane's program picks the
 * profile, first match wins, else [default].
 */
@Serializable
data class KeyBar(
    val profiles: List<KeyProfile>,
    val default: KeyProfile,
) {
    fun profileFor(program: String): KeyProfile =
        profiles.firstOrNull { p -> p.programs.any { it.equals(program, ignoreCase = true) } } ?: default

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
        ).map { (label, key) -> BarKey(label, listOf(key)) } + listOf(
            BarKey("pfx", listOf("prefix"), sticky = true, bindings = true),
            BarKey("ctrl", listOf("C-"), sticky = true),
            BarKey("alt", listOf("M-"), sticky = true),
        )

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
