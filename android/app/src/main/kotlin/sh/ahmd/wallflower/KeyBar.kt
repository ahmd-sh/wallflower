package sh.ahmd.wallflower

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import sh.ahmd.wallflower.program.Program
import sh.ahmd.wallflower.program.Programs
import sh.ahmd.wallflower.program.Shortcut

/**
 * A key on the bar: what it shows, and the keys it sends by wallflower's
 * names (`Escape`, `C-c`, or literal text). A [sticky] key waits for the next
 * key, from the bar or the keyboard, and goes with it. With [bindings], the
 * multiplexer's key bindings apply, as if typed on a keyboard attached to it;
 * `prefix` is its prefix key. A [cluster] gathers keys, each its own, laid
 * out as arrow keys with two more above: [keys] up, left, the one top left,
 * right, down, and the one top right. A [shortcuts] key sends none of its
 * own: it offers the pane's shortcuts ([KeyProfile.shortcutsFor]).
 */
@Serializable
data class BarKey(
    val label: String,
    val keys: List<String>,
    val sticky: Boolean = false,
    val bindings: Boolean = false,
    val cluster: Boolean = false,
    val shortcuts: Boolean = false,
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

    /** Whether [label] is the one wallflower gives these keys, rather than one the user wrote. */
    val ownLabel: Boolean get() = label == KeyBar.labelOf(keys) || KeyBar.common.any { it.label == label && it.keys == keys }

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
    /** The user's own shortcuts, after the program's. */
    val shortcuts: List<BarKey> = emptyList(),
    /** The program's shortcuts the user hid, by label. */
    val hidden: List<String> = emptyList(),
) {
    fun sendKeys(): List<String> = send ?: ENTER

    /** Whether it's the bar for a pane running [command], or the program the gateway named [program]. */
    fun matches(command: String?, program: String? = null): Boolean =
        programs.any { it.equals(command, ignoreCase = true) || it.equals(program, ignoreCase = true) }

    /** A new line, in a program that takes several (Program.newlineKeys); else Enter. */
    fun returnKeys(program: Program): List<String> = returnKey ?: program.newlineKeys ?: ENTER

    /** What held ctrl, or the shortcuts key, offers in a pane running [program]: its shortcuts the user didn't hide, then theirs. */
    fun shortcutsFor(program: Program): List<BarKey> = program.shortcuts.map(KeyBar::shortcut).filter { it.label !in hidden } + shortcuts
}

/** A shortcut's keys as they go: keys pressed, or [Text] typed, with Enter a moment after it if [Text.enter]. */
sealed interface Step {
    data class Keys(val keys: List<String>) : Step

    data class Text(val text: String, val enter: Boolean) : Step
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
    /** The profile for a pane's program: one of these, or the bar its plugin ships (a bar saved before it shipped has none for it), or the default. */
    fun profileFor(command: String, program: String? = null): KeyProfile =
        profiles.firstOrNull { it.matches(command, program) }
            ?: (Programs.supplied(program) ?: program?.lowercase()?.let { Programs.builtin[it] })?.keyBar?.let { d ->
                // Its shortcuts are the default's, which its settings are until the user makes it a profile.
                KeyProfile(d.name, listOfNotNull(program), d.keys.map(::key), stop = d.stop, shortcuts = default.shortcuts, hidden = default.hidden)
            }
            ?: default

    companion object {
        /** The arrows, with enter above left and backspace above right, in one key; `arrows` by name (the order is saved with a bar). */
        val ARROWS = BarKey("arrows", listOf("Up", "Left", "Enter", "Right", "Down", "BSpace"), cluster = true)

        /** The pane's shortcuts, for a bar without ctrl to hold; `shortcuts` by name. */
        val SHORTCUTS = BarKey("shortcuts", emptyList(), shortcuts = true)

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
            ARROWS,
            SHORTCUTS,
            BarKey("pfx", listOf("prefix"), sticky = true, bindings = true),
            BarKey("ctrl", listOf("C-"), sticky = true),
            BarKey("alt", listOf("M-"), sticky = true),
        )

        /** How [keys] show on a key: ^c, ⌥⏎, ⇧↑, esc :w ⏎. */
        fun labelOf(keys: List<String>): String = keys.joinToString(" ") { key ->
            val (held, rest) = held(key)
            held.joinToString("") { MODIFIERS.getValue(it) } + (NAMED[rest] ?: rest)
        }

        /** What [key] sends, in words; a sticky one, with a key. */
        fun nameOf(key: BarKey): String = when {
            key.cluster -> "arrows, enter, backspace"
            key.shortcuts -> "shortcuts"
            else -> nameOf(key.keys) + if (key.sticky) " + key" else ""
        }

        /** [keys] in words: ctrl c, shift tab, escape :w enter. */
        fun nameOf(keys: List<String>): String = keys.joinToString(" ") { key ->
            val (held, rest) = held(key)
            (held.map { SAID.getValue("$it-") } + (SAID[rest] ?: rest)).joinToString(" ")
        }

        /** The modifiers held with a key (C, M, S) and the key: C-M-Left is C, M and Left. */
        fun held(key: String): Pair<List<Char>, String> {
            var rest = key
            val held = ArrayList<Char>()
            while (rest.length > 2 && rest[1] == '-' && rest[0] in MODIFIERS) {
                held += rest[0]
                rest = rest.drop(2)
            }
            return held to rest
        }

        private val MODIFIERS = mapOf('C' to "^", 'M' to "⌥", 'S' to "⇧")
        private val NAMED = mapOf(
            "Enter" to "⏎", "Escape" to "esc", "Tab" to "tab", "BTab" to "⇧tab", "BSpace" to "⌫", "Space" to "space",
            "Up" to "↑", "Down" to "↓", "Left" to "←", "Right" to "→", "Home" to "home", "End" to "end",
            "PageUp" to "pgup", "PageDown" to "pgdn", "DC" to "del", "IC" to "ins", "prefix" to "pfx",
        )
        private val SAID = mapOf(
            "C-" to "ctrl", "M-" to "alt", "S-" to "shift",
            "Enter" to "enter", "Escape" to "escape", "Tab" to "tab", "BTab" to "shift tab", "BSpace" to "backspace", "Space" to "space",
            "Up" to "up", "Down" to "down", "Left" to "left", "Right" to "right", "Home" to "home", "End" to "end",
            "PageUp" to "page up", "PageDown" to "page down", "DC" to "delete", "IC" to "insert", "prefix" to "prefix",
        )

        /** Whether [name] is a key by wallflower's names (Enter, C-c, F5, prefix), rather than text to type. */
        fun isKey(name: String): Boolean {
            val (held, rest) = held(name)
            return rest in KEYS || held.isNotEmpty() && rest.length == 1
        }

        private val KEYS = NAMED.keys + (1..12).map { "F$it" }

        /**
         * Keys as the user writes them, space-separated: words that are
         * keys by name go as they are, and the words between them as one
         * text: `/model opus Enter` types /model opus, then presses Enter.
         */
        fun keysOf(sends: String): List<String> {
            val out = ArrayList<String>()
            var text: String? = null
            for (word in sends.split(' ').filter(String::isNotEmpty)) {
                if (isKey(word)) {
                    text?.let(out::add)
                    text = null
                    out += word
                } else {
                    text = text?.let { "$it $word" } ?: word
                }
            }
            text?.let(out::add)
            return out
        }

        /**
         * How [keys] go: keys pressed together, and text typed, Enter after
         * it pressed a moment later, as the box's text is sent, so a program
         * that reads them at once (Claude Code) doesn't take a long line and
         * its Enter for a paste.
         */
        fun steps(keys: List<String>): List<Step> {
            val out = ArrayList<Step>()
            val pressed = ArrayList<String>()
            var i = 0
            while (i < keys.size) {
                val key = keys[i]
                if (isKey(key)) {
                    pressed += key
                    i++
                    continue
                }
                if (pressed.isNotEmpty()) out += Step.Keys(pressed.toList())
                pressed.clear()
                val enter = keys.getOrNull(i + 1) == "Enter"
                out += Step.Text(key, enter)
                i += if (enter) 2 else 1
            }
            if (pressed.isNotEmpty()) out += Step.Keys(pressed)
            return out
        }

        /** A program's shortcut, as the bar offers it. */
        fun shortcut(s: Shortcut) = BarKey(s.label ?: labelOf(s.keys), s.keys)

        /** Held down, these repeat. */
        val repeating = setOf("Up", "Down", "Left", "Right", "BSpace", "PageUp", "PageDown")

        private fun keys(vararg labels: String) = labels.map { l -> common.first { it.label == l } }

        /** A key by name, as the bar shows it: `arrows`, `shortcuts` and the sticky ones (`ctrl`, `alt`, `pfx`) by theirs. */
        private fun key(name: String) = common.firstOrNull { (it.sticky || it.cluster || it.shortcuts) && it.label == name }
            ?: common.firstOrNull { !it.sticky && !it.cluster && it.keys == listOf(name) } ?: BarKey(labelOf(listOf(name)), listOf(name))

        val defaults = KeyBar(
            profiles = Programs.builtin.entries.filter { it.value.keyBar != null }
                .groupBy({ it.value.keyBar!! }, { it.key })
                .map { (d, ids) -> KeyProfile(d.name, ids, d.keys.map(::key), stop = d.stop) },
            default = KeyProfile("default", keys = keys("esc", "tab", "⇧tab", "^c", "arrows")),
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
