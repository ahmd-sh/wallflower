package sh.ahmd.wallflower

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import sh.ahmd.wallflower.program.Program
import sh.ahmd.wallflower.program.Programs

/**
 * A key on the bar: what it shows, and the keys it sends by wallflower's
 * names (`Escape`, `C-c`, or literal text). A [sticky] key waits for the next
 * key, from the bar or the keyboard, and goes with it. With [bindings], the
 * multiplexer's key bindings apply, as if typed on a keyboard attached to it;
 * `prefix` is its prefix key.
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
        fun typed(c: Char) = BarKey(c.toString(), listOf(when (c) { ' ' -> "Space"; '\n' -> "Enter"; else -> c.toString() }))
    }
}

/** The bar for some programs. */
@Serializable
data class KeyProfile(
    val name: String,
    /** As panes report them (claude, zsh, hntui), or as the gateway names their program (shell). */
    val programs: List<String> = emptyList(),
    val keys: List<BarKey>,
    /** What the send button sends when the box is empty. */
    val stop: List<String> = listOf("C-c"),
    /** What empties a row of the app's input line, as the user set it; null leaves it to the program (Program.clearKeys). */
    val clear: List<String>? = null,
    /** What the send button presses where the keyboard types straight into the pane, as the user set it; null is Enter. */
    val send: List<String>? = null,
    /** What the keyboard's return key presses there, as the user set it; null leaves it to the program. */
    val returnKey: List<String>? = null,
) {
    fun sendKeys(): List<String> = send ?: ENTER

    /** Whether it's the bar for a pane running [command], or the program the gateway named [program]. */
    fun matches(command: String?, program: String? = null): Boolean =
        programs.any { it.equals(command, ignoreCase = true) || it.equals(program, ignoreCase = true) }

    /** A new line, in a program that takes several (Program.newlineKeys); else Enter. */
    fun returnKeys(program: Program): List<String> = returnKey ?: program.newlineKeys ?: ENTER
}

private val ENTER = listOf("Enter")

/**
 * Which keys sit next to the composer. The focused pane's program picks the
 * profile, first match wins, else [default]. The programs wallflower ships
 * bring their own ([Program.keyBar]) until the user changes them.
 */
@Serializable
data class KeyBar(
    val profiles: List<KeyProfile>,
    val default: KeyProfile,
) {
    fun profileFor(command: String, program: String? = null): KeyProfile =
        profiles.firstOrNull { it.matches(command, program) } ?: default

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

        /** How [keys] show on a key: ^c, ⌥⏎, ⇧↑, esc :w ⏎. */
        fun labelOf(keys: List<String>): String = keys.joinToString(" ") { key ->
            var rest = key
            val held = buildString {
                while (rest.length > 2 && rest[1] == '-' && rest[0] in MODIFIERS) {
                    append(MODIFIERS.getValue(rest[0]))
                    rest = rest.drop(2)
                }
            }
            held + (NAMED[rest] ?: rest)
        }

        private val MODIFIERS = mapOf('C' to "^", 'M' to "⌥", 'S' to "⇧")
        private val NAMED = mapOf(
            "Enter" to "⏎", "Escape" to "esc", "Tab" to "tab", "BTab" to "⇧tab", "BSpace" to "⌫", "Space" to "space",
            "Up" to "↑", "Down" to "↓", "Left" to "←", "Right" to "→", "Home" to "home", "End" to "end",
            "PageUp" to "pgup", "PageDown" to "pgdn", "DC" to "del", "IC" to "ins", "prefix" to "pfx",
        )

        /** Held down, these repeat. */
        val repeating = setOf("Up", "Down", "Left", "Right", "BSpace", "PageUp", "PageDown")

        private fun keys(vararg labels: String) = labels.map { l -> common.first { it.label == l } }

        /** A key by name, as the bar shows it. */
        private fun key(name: String) = common.firstOrNull { !it.sticky && it.keys == listOf(name) } ?: BarKey(labelOf(listOf(name)), listOf(name))

        val defaults = KeyBar(
            profiles = Programs.builtin.entries.filter { it.value.keyBar != null }
                .groupBy({ it.value.keyBar!! }, { it.key })
                .map { (d, ids) -> KeyProfile(d.name, ids, d.keys.map(::key), stop = d.stop) },
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
