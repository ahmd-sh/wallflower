package sh.ahmd.wallflower.ui

import sh.ahmd.wallflower.BarKey
import sh.ahmd.wallflower.KeyBar
import sh.ahmd.wallflower.Step
import sh.ahmd.wallflower.program.Effect
import sh.ahmd.wallflower.program.Program
import sh.ahmd.wallflower.program.Recall
import sh.ahmd.wallflower.protocol.Mux
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
 * A pane's program, and what's done to it on the way somewhere ([Seeker],
 * [ModeWalk]): keys pressed, a message asked back, a prompt gone.
 * [PaneActions]; a test's own, in tests.
 */
internal interface PaneHand {
    val program: Program

    fun keys(keys: List<String>, bindings: Boolean = false)

    fun recall(message: Entry.Said, reading: Reading, line: InputLine?): Recall?

    fun settled(prompt: Prompt, line: InputLine?, held: String? = null)
}

/**
 * What the user can do to a pane, in its program's terms: [clearKeys] empty
 * a row of its input line (the user's, or the program's), [stopKeys] stop it,
 * and where the keyboard types straight in, [sendKeys] are the send button's
 * and [returnKeys] the keyboard's return key.
 */
internal class PaneActions(
    private val session: Session,
    val pane: Pane,
    override val program: Program,
    private val clearKeys: List<String>,
    private val stopKeys: List<String>,
    val sendKeys: List<String>,
    val returnKeys: List<String>,
) : PaneHand {
    override fun keys(keys: List<String>, bindings: Boolean) {
        session.keys(pane.id, keys, bindings)
    }

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

    fun stop() {
        session.stopped(pane.id)
        keys(stopKeys)
    }

    /**
     * Brings the user's [message] back into the program's input line, to edit,
     * if the program can: how, if it's on its way. [line]: what the line holds
     * now, which a command sent for it replaces.
     */
    override fun recall(message: Entry.Said, reading: Reading, line: InputLine?): Recall? {
        val latest = reading.entries.lastOrNull { it is Entry.Said && it.by == Party.User }?.id == message.id
        return when (val r = program.recall(latest, activityOf(pane) == Activity.Working)) {
            is Recall.Keys -> {
                session.stopped(pane.id) // as a stop does, the program may take the message back
                keys(r.keys)
                r
            }
            is Recall.Command -> r.takeIf { send(r.text, enter = true, line, unseen = true) }
            null -> null
        }
    }

    /**
     * Whether the pane's at work, so a stop means something: its program
     * says so, if it says (working, or waiting on the user); else a command
     * other than its shell runs there, but not one that took the whole
     * screen (an editor, a pager), whose keys are its own.
     */
    val busy: Boolean
        get() = activityOf(pane)?.let { it != Activity.Idle } ?: (pane.command.trimStart('-').substringAfterLast('/') !in SHELLS && !pane.alt)

    /** Whether anything empties its input line. */
    val clears: Boolean get() = clearKeys.isNotEmpty()

    /** Empties [line] in the pane; [unseen] while the mobile view covers the program. */
    fun clear(line: InputLine, unseen: Boolean) = keys(program.clear(line, clearKeys, unseen))

    /** Sends composed text in place of whatever [line] held; false if it couldn't go. */
    fun send(text: String, enter: Boolean, line: InputLine?, unseen: Boolean): Boolean =
        session.submit(pane.id, text, enter, line?.let { program.clear(it, clearKeys, unseen) }.orEmpty())

    /**
     * A shortcut, picked from the bar. Text it starts with that Enter sends
     * goes in place of what [line] holds, as the box's does, so a command
     * runs as itself whatever's in the line (and in vim mode's NORMAL, where
     * it can't be seen). Its other keys and text go where the cursor is: as
     * if typed on a keyboard attached to the multiplexer if it says, so its
     * key bindings apply (the user's prefix, then a key), where it can.
     */
    fun shortcut(key: BarKey, line: InputLine?) {
        val bound = key.bindings && session.mux.value?.let { Mux.BINDINGS in it.can } == true
        KeyBar.steps(key.keys).forEachIndexed { i, step ->
            when (step) {
                is Step.Keys -> keys(step.keys, bound)
                is Step.Text -> when {
                    !step.enter -> if (bound) keys(listOf(step.text), bindings = true) else type(step.text)
                    i == 0 -> send(step.text, enter = true, line, unseen = true)
                    else -> session.submit(pane.id, step.text)
                }
            }
        }
    }

    /** Compacts its conversation, if its program can; it's under the mobile view, so unseen. */
    val compact: ((InputLine?) -> Unit)? = program.compact?.let { command ->
        { line -> send(command, enter = true, line, unseen = true) }
    }

    /** Has the program take the suggestion in its input line into the line, whole, if it can. */
    val acceptSuggestion: (() -> Unit)? = program.acceptSuggestion?.let { keys -> { keys(keys) } }

    /** A picture entry of the pane's mobile view, its bytes. */
    suspend fun picture(id: String): ByteArray? = session.picture(pane.id, id)

    /** An option picked from a prompt the pane offered. */
    fun picked(prompt: Prompt, option: Prompt.Option, held: String? = null) = affect(program.picked(prompt, option), held)

    /** A prompt the pane offered has gone, answered here or at the computer, leaving its input [line] as it is. */
    override fun settled(prompt: Prompt, line: InputLine?, held: String?) = affect(program.settled(prompt, line), held)

    /** What picking did; [held]: the message the user held to edit, if that's what it was for. */
    private fun affect(effect: Effect?, held: String?) {
        when (effect) {
            is Effect.Rewound -> session.rewound(pane.id, effect.said, held)
            null -> Unit
        }
    }
}

/** Commands that are a shell waiting at its prompt. */
private val SHELLS = setOf("zsh", "bash", "fish", "sh", "dash", "ksh", "mksh", "tcsh", "csh", "nu", "xonsh", "elvish", "pwsh", "login")
