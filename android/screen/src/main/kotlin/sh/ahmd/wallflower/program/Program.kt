package sh.ahmd.wallflower.program

import sh.ahmd.wallflower.screen.InputLine
import sh.ahmd.wallflower.screen.Prompt
import sh.ahmd.wallflower.screen.Screen

/**
 * The phone's half of a program's plugin: what wallflower can read off the
 * program's screen, and how to drive it beyond typing. The Mac's half says
 * which panes run it, and names it by the same id (see [Programs]).
 */
interface Program {
    /** The program's input line, if wallflower can see one on its screen. */
    fun line(screen: Screen): InputLine?

    /** Choices the program offers on its screen, to answer by touch. */
    fun prompt(screen: Screen): Prompt? = null

    /** What the program says on its screen it's doing, in its own words, while it works; null if it doesn't say. */
    fun status(screen: Screen): String? = null

    /**
     * What the program's notification says on the phone: a desktop
     * notification ([title], [body]), or its terminal's bell (no [body]),
     * given its [screen] as it came if the program [readsScreenToNotify]
     * (null if it couldn't be read); null: nothing. A bell says nothing
     * unless the program has it say something: shells and editors ring it
     * at a mistyped key.
     */
    fun notice(title: String?, body: String?, screen: Screen?): Notice? = body?.let { Notice(title, it) }

    /** Whether [notice] needs the program's screen, which takes opening its pane a moment. */
    val readsScreenToNotify: Boolean get() = false

    /** What empties a row of the input line; empty if nothing does, and the box adds to the line. */
    val clearKeys: List<String>

    /**
     * Keys that empty [line], [keys] being what empties a row (the
     * program's, or the user's own): once per row it takes, and once more.
     * [unseen]: the program can't be seen, as under its mobile view.
     */
    fun clear(line: InputLine, keys: List<String> = clearKeys, unseen: Boolean = false): List<String> =
        List(line.rows * 2 + 1) { keys }.flatten()

    /** What starts a new line of what's being typed without sending it, if the program takes several; a shell runs the line. */
    val newlineKeys: List<String>? get() = null

    /** What the program is sent to compact its conversation, if it can. */
    val compact: String? get() = null

    /**
     * What takes the suggestion in the program's empty input line into the
     * line, whole, if it shows suggestions: cut short to fit the screen
     * ("…"), what the screen says isn't all of one.
     */
    val acceptSuggestion: List<String>? get() = null

    /** Commands the composer's + offers for the program's panes, which start what's sent. */
    val commands: List<Command> get() = emptyList()

    /**
     * What brings a message the user sent back into the input line, to edit
     * and send again, if the program can: [latest] being their newest, which
     * the program is still on while [working].
     */
    fun recall(latest: Boolean, working: Boolean): Recall? = null

    /**
     * Where [prompt], opened by [recall]'s command, is in walking to the
     * user's message [said]; null if it isn't the prompt that lists them.
     */
    fun walk(prompt: Prompt, said: String): Walk? = null

    /** What picking [option] of [prompt] does besides answering it, if wallflower needs to know. */
    fun picked(prompt: Prompt, option: Prompt.Option): Effect? = null

    /**
     * What answering [prompt] did besides, whoever answered it (at the Mac,
     * say), as the input [line] the program shows once it's gone tells; null
     * if nothing wallflower needs to know.
     */
    fun settled(prompt: Prompt, line: InputLine?): Effect? = null

    /**
     * What a tab shows for the program's window, from the terminal title it
     * sets ([title]), in place of a name the multiplexer gave the window
     * after the program's command; null keeps that name.
     */
    fun label(title: String): String? = null

    /** The key bar its panes get until the user sets them one; programs with the same one share it. */
    val keyBar: KeyDefaults? get() = null
}

/**
 * A key bar a program ships: its [name], its [keys] by wallflower's names,
 * and what the send button presses when there's nothing to send ([stop]).
 */
data class KeyDefaults(val name: String, val keys: List<String>, val stop: List<String> = listOf("C-c"))

/** A command a program takes, as the + offers it: its [text], and what it's [about]. */
data class Command(val text: String, val about: String)

/** What brings back a message the user sent: [Keys], or a [Command] sent as if typed. */
sealed interface Recall {
    data class Keys(val keys: List<String>) : Recall

    data class Command(val text: String) : Recall
}

/**
 * A prompt listing the user's messages, walked to one: the cursor's [on]
 * one that reads as it, the keys that go [up] to the one before (null at the
 * top), and those that [pick] the one under the cursor.
 */
data class Walk(val on: Boolean, val up: List<String>?, val pick: List<String>)

/** A notification as the phone shows it. */
data class Notice(val title: String?, val body: String)

/** Something picking an option did that the program won't record until later. */
sealed interface Effect {
    /** Its conversation went back to before the latest of the user's messages that [said] matches. */
    class Rewound(val said: (String) -> Boolean) : Effect
}

/**
 * A program the phone has no half of its own for, as a plugin the user gave
 * the gateway describes it, in data, since the phone loads no code: its key
 * bar, commands, and the keys that start a new line and empty one. It reads
 * a line only from prompt marks (OSC 133), if it makes them.
 */
class Supplied(
    override val keyBar: KeyDefaults?,
    override val commands: List<Command>,
    override val newlineKeys: List<String>?,
    override val clearKeys: List<String>,
) : Program by Shell(clearKeys, keyBar)

/** The programs wallflower ships, and those the gateway's plugins describe, by the id the gateway names a pane's program by. */
object Programs {
    private val shells = KeyDefaults("shell", listOf("Tab", "Up", "Down", "Left", "Right", "C-r", "C-c", "C-d", "Escape"))
    private val other = Shell()

    val builtin: Map<String, Program> = linkedMapOf(
        "claude" to Claude,
        "zsh" to Shell(listOf("C-x", "C-u"), shells), // kill-buffer, as the dotfiles' zshrc binds it
        "shell" to Shell(keyBar = shells),
    )

    @Volatile
    private var supplied: Map<String, Program> = emptyMap()

    /** The programs the gateway's plugins describe, by id, in place of those before. */
    fun supply(programs: Map<String, Program>) {
        supplied = programs.mapKeys { it.key.lowercase() }
    }

    /** The ids of the programs the gateway's plugins describe. */
    fun suppliedIds(): Set<String> = supplied.keys - builtin.keys

    /** A program a plugin of the user's describes, by its id; null if it's one wallflower ships, or none is. */
    fun supplied(id: String?): Program? = id?.lowercase()?.takeIf { it !in builtin }?.let { supplied[it] }

    /** A pane's program, by its id; one nothing describes (or none) is a plain terminal's. */
    fun of(id: String?): Program = id?.lowercase()?.let { builtin[it] ?: supplied[it] } ?: other
}
