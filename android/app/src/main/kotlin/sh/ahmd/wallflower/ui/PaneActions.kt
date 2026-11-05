package sh.ahmd.wallflower.ui

import sh.ahmd.wallflower.program.Effect
import sh.ahmd.wallflower.program.Program
import sh.ahmd.wallflower.program.Recall
import sh.ahmd.wallflower.protocol.Pane
import sh.ahmd.wallflower.read.Activity
import sh.ahmd.wallflower.read.Entry
import sh.ahmd.wallflower.read.Party
import sh.ahmd.wallflower.read.Reading
import sh.ahmd.wallflower.read.activityOf
import sh.ahmd.wallflower.screen.InputLine
import sh.ahmd.wallflower.screen.Prompt
import sh.ahmd.wallflower.session.Session

/**
 * What the user can do to a pane, in its program's terms: [clearKeys] empty
 * a row of its input line (the user's, or the program's), [stopKeys] stop it,
 * and where the keyboard types straight in, [sendKeys] are the send button's
 * and [returnKeys] the keyboard's return key.
 */
internal class PaneActions(
    private val session: Session,
    val pane: Pane,
    val program: Program,
    private val clearKeys: List<String>,
    private val stopKeys: List<String>,
    val sendKeys: List<String>,
    val returnKeys: List<String>,
) {
    fun keys(keys: List<String>, bindings: Boolean = false) = session.keys(pane.id, keys, bindings)

    /** A scroll wheel's notch: its bytes for an app that tracks the mouse, else an arrow. */
    fun wheel(up: Boolean, bytes: ByteArray?) = if (bytes != null) session.bytes(pane.id, bytes) else keys(listOf(if (up) "Up" else "Down"))

    fun enter() = keys(listOf("Enter"))

    /** The send button, where the keyboard types straight in. */
    fun press() = keys(sendKeys)

    /** Text typed straight into the pane: as it is, but pasted if it has lines (from the clipboard), so they don't run one by one. */
    fun type(text: String) {
        if ('\n' in text && text.length > 1) {
            session.submit(pane.id, text, enter = false)
        } else {
            session.bytes(pane.id, text.replace('\n', '\r').encodeToByteArray())
        }
    }

    fun stop() = keys(stopKeys)

    /**
     * Brings the user's [message] back into the program's input line, to edit,
     * if the program can; true if it's on its way. [line]: what the line holds
     * now, which a command sent for it replaces.
     */
    fun recall(message: Entry.Said, reading: Reading, line: InputLine?): Boolean {
        val latest = reading.entries.lastOrNull { it is Entry.Said && it.by == Party.User }?.id == message.id
        return when (val r = program.recall(latest, activityOf(pane) == Activity.Working)) {
            is Recall.Keys -> {
                keys(r.keys)
                true
            }
            is Recall.Command -> send(r.text, enter = true, line, unseen = true)
            null -> false
        }
    }

    /** Whether anything empties its input line. */
    val clears: Boolean get() = clearKeys.isNotEmpty()

    /** Empties [line] in the pane; [unseen] while the mobile view covers the program. */
    fun clear(line: InputLine, unseen: Boolean) = keys(program.clear(line, clearKeys, unseen))

    /** Sends composed text in place of whatever [line] held; false if it couldn't go. */
    fun send(text: String, enter: Boolean, line: InputLine?, unseen: Boolean): Boolean =
        session.submit(pane.id, text, enter, line?.let { program.clear(it, clearKeys, unseen) }.orEmpty())

    /** Compacts its conversation, if its program can; it's under the mobile view, so unseen. */
    val compact: ((InputLine?) -> Unit)? = program.compact?.let { command ->
        { line -> send(command, enter = true, line, unseen = true) }
    }

    /** Has the program take the suggestion in its input line into the line, whole, if it can. */
    val acceptSuggestion: (() -> Unit)? = program.acceptSuggestion?.let { keys -> { keys(keys) } }

    /** A picture entry of the pane's mobile view, its bytes. */
    suspend fun picture(id: String): ByteArray? = session.picture(pane.id, id)

    /** An option picked from a prompt the pane offered. */
    fun picked(prompt: Prompt, option: Prompt.Option) {
        when (val effect = program.picked(prompt, option)) {
            is Effect.Rewound -> session.rewound(pane.id, effect.said)
            null -> Unit
        }
    }
}
