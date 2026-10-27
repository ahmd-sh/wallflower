package sh.ahmd.wallflower.program

import sh.ahmd.wallflower.screen.InputLine
import sh.ahmd.wallflower.screen.Prompt
import sh.ahmd.wallflower.screen.Screen

/**
 * What wallflower knows of a kind of program running in a pane: what it can
 * read off the program's screen, and how to drive it beyond typing.
 */
interface Program {
    /** The program's input line, if wallflower can see one on its screen. */
    fun line(screen: Screen): InputLine?

    /** Choices the program offers on its screen, to answer by touch. */
    fun prompt(screen: Screen): Prompt? = null

    /** What empties a row of the input line; empty if nothing does, and the box adds to the line. */
    val clearKeys: List<String>

    /**
     * Keys that empty [line], [keys] being what empties a row (the
     * program's, or the user's own): once per row it takes, and once more.
     * [unseen]: the program can't be seen, as under its read view.
     */
    fun clear(line: InputLine, keys: List<String> = clearKeys, unseen: Boolean = false): List<String> =
        List(line.rows * 2 + 1) { keys }.flatten()

    /** What starts a new line of what's being typed without sending it, if the program takes several; a shell runs the line. */
    val newlineKeys: List<String>? get() = null

    /** What the program is sent to compact its conversation, if it can. */
    val compact: String? get() = null

    /** What picking [option] of [prompt] does besides answering it, if wallflower needs to know. */
    fun picked(prompt: Prompt, option: Prompt.Option): Effect? = null
}

/** Something picking an option did that the program won't record until later. */
sealed interface Effect {
    /** Its conversation went back to before the latest of the user's messages that [said] matches. */
    class Rewound(val said: (String) -> Boolean) : Effect
}

/** The programs wallflower knows, by the command a pane reports. */
object Programs {
    private val zsh = Shell(listOf("C-x", "C-u")) // kill-buffer, as the dotfiles' zshrc binds it
    private val other = Shell()

    fun of(command: String): Program = when (command.lowercase()) {
        "claude" -> Claude
        "zsh" -> zsh
        else -> other
    }
}
