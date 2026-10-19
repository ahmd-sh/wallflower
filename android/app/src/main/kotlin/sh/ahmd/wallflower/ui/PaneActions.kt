package sh.ahmd.wallflower.ui

import sh.ahmd.wallflower.program.Effect
import sh.ahmd.wallflower.program.Program
import sh.ahmd.wallflower.protocol.Pane
import sh.ahmd.wallflower.screen.InputLine
import sh.ahmd.wallflower.screen.Prompt
import sh.ahmd.wallflower.session.Session

/**
 * What the user can do to a pane, in its program's terms: [clearKeys] empty
 * a row of its input line (the user's, or the program's), [stopKeys] stop it.
 */
internal class PaneActions(
    private val session: Session,
    val pane: Pane,
    val program: Program,
    private val clearKeys: List<String>,
    private val stopKeys: List<String>,
) {
    fun keys(keys: List<String>, bindings: Boolean = false) = session.keys(pane.id, keys, bindings)

    /** A scroll wheel's notch: its bytes for an app that tracks the mouse, else an arrow. */
    fun wheel(up: Boolean, bytes: ByteArray?) = if (bytes != null) session.bytes(pane.id, bytes) else keys(listOf(if (up) "Up" else "Down"))

    fun enter() = keys(listOf("Enter"))

    fun stop() = keys(stopKeys)

    /** Ends the program running in it, even one that won't take [stop]. */
    fun quit() = session.quit(pane.id)

    /** Whether anything empties its input line. */
    val clears: Boolean get() = clearKeys.isNotEmpty()

    /** Empties [line] in the pane; [unseen] while the read view covers the program. */
    fun clear(line: InputLine, unseen: Boolean) = keys(program.clear(line, clearKeys, unseen))

    /** Sends composed text in place of whatever [line] held; false if it couldn't go. */
    fun send(text: String, enter: Boolean, line: InputLine?, unseen: Boolean): Boolean =
        session.submit(pane.id, text, enter, line?.let { program.clear(it, clearKeys, unseen) }.orEmpty())

    /** Compacts its conversation, if its program can; it's under the read view, so unseen. */
    val compact: ((InputLine?) -> Unit)? = program.compact?.let { command ->
        { line -> send(command, enter = true, line, unseen = true) }
    }

    /** A picture entry of the pane's read view, its bytes. */
    suspend fun picture(id: String): ByteArray? = session.picture(pane.id, id)

    /** An option picked from a prompt the pane offered. */
    fun picked(prompt: Prompt, option: Prompt.Option) {
        when (val effect = program.picked(prompt, option)) {
            is Effect.Rewound -> session.rewound(pane.id, effect.said)
            null -> Unit
        }
    }
}
