package sh.ahmd.wallflower.ui.composer

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import sh.ahmd.wallflower.BarKey
import sh.ahmd.wallflower.KeyBar
import sh.ahmd.wallflower.Step
import sh.ahmd.wallflower.program.Command
import sh.ahmd.wallflower.screen.InputLine
import sh.ahmd.wallflower.session.uploadPaths
import sh.ahmd.wallflower.ui.Mono
import sh.ahmd.wallflower.ui.Motion
import sh.ahmd.wallflower.ui.Palette
import sh.ahmd.wallflower.ui.faceOf
import sh.ahmd.wallflower.ui.haptics

/**
 * A plain text field, so the keyboard's autocorrect, swipe and voice input
 * all work, with the key bar and send button beneath it. Return is a
 * newline. A sticky key on the bar (pfx, ctrl) takes the next key, from the
 * bar or the keyboard.
 *
 * The box is the phone's own: nothing the app does writes into it, so a
 * draft stays put until sent. The app keeps its own input line ([line], when
 * wallflower can read it). While the box is empty it shows that line dimmed,
 * with × to clear it in the app ([onClear]), or else what the app suggests
 * typing; a swipe right brings either into the box to edit (a tap is just
 * the box's, to type something else). A suggestion cut short to fit the
 * screen ("…") comes in whole: the program takes it into its line
 * ([onAcceptSuggestion]), and it comes from there. So does a sent message
 * asked back to edit ([recalled]), once the program puts it back. What's in the box replaces the line
 * when sent, so the box is exactly what runs. The button sends (held: types
 * it in without Enter), or queues it while the program at work [queues] what
 * it's sent, or [holds] it while an answer comes first; enters the app's
 * line when only that has text; or stops: while the program's at work
 * ([stoppable]), or just after sending, since a stop can mean more to an
 * idle program (^C twice quits Claude Code).
 *
 * + sends photos or files to the computer ([attachments]); each shows above the
 * box on its way, then its path goes in at the cursor, shown as its name.
 * What the draft holds shows above it too, each with × to take it out.
 * + also offers the program's [commands], which start the draft; one it
 * starts with is marked.
 *
 * The shortcuts key offers the pane's [shortcuts]: one picked goes to
 * [onShortcut], but text alone, with no Enter to send it, starts the draft
 * as a command does (or goes in at the cursor, if it isn't one). The
 * arrows key brings up their [pad], which stays put for as long as it's up,
 * here and for the next pane, while it's been moved or the bar has the key.
 *
 * With [typing], the keyboard types straight into the pane instead, as a
 * terminal's does ([onType], other keys to [onKey] by name, its return key
 * as [returnKeys]): no box, no +, just the key bar and the button
 * ([onEnter], pressing [sendKeys], which it shows if they aren't Enter;
 * or stopping the pane while it's [working] and nothing's been typed for
 * it since it last took a line: a REPL's or a remote shell's line can't be read).
 */
@OptIn(ExperimentalLayoutApi::class) // whether the keyboard is up
@Composable
internal fun Composer(
    state: TextFieldState,
    program: String,
    keys: List<BarKey>,
    connected: Boolean,
    line: InputLine?,
    attachments: Attachments,
    onClear: (() -> Unit)?,
    onKey: (BarKey) -> Unit,
    typing: Typing?,
    sendKeys: List<String>,
    returnKeys: List<String>,
    onType: (String) -> Unit,
    onSend: (text: String, enter: Boolean) -> Boolean,
    onEnter: () -> Unit,
    onStop: () -> Unit,
    onEditKeys: () -> Unit,
    pad: ArrowPad,
    shortcuts: List<BarKey> = emptyList(),
    onShortcut: (BarKey) -> Unit = {},
    onAddShortcut: () -> Unit = {},
    commands: List<Command> = emptyList(),
    onAcceptSuggestion: (() -> Unit)? = null,
    recalled: Recalled? = null,
    stoppable: Boolean = true,
    working: Boolean = false,
    queues: Boolean = false,
    holds: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Closing the keyboard lets go of the box, so switching panes doesn't bring it back.
    val imeVisible = WindowInsets.isImeVisible
    val focusManager = LocalFocusManager.current
    LaunchedEffect(imeVisible) { if (!imeVisible) focusManager.clearFocus() }
    // Back from a picker the keyboard was up for, it comes back up, once the window has focus again.
    var refocus by remember { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(refocus, windowFocused) {
        if (refocus && windowFocused) {
            refocus = false
            focus.requestFocus()
            keyboard?.show()
        }
    }
    val pending = line?.text.orEmpty()
    val ghost = pending.ifEmpty { line?.suggestion.orEmpty() }
    // Typed straight into the pane since it last took a line: the button enters it, rather than stopping what's [working].
    var typed by remember(typing != null) { mutableStateOf(false) }
    val sticky = rememberStickyKeys { key ->
        typed = key.keys.lastOrNull() != "Enter"
        onKey(key)
    }
    val paths = remember(attachments.dir) { attachments.dir?.let(::uploadPaths) }
    val slash = commands.isNotEmpty()
    val colors = Palette
    val named = remember(paths, slash, colors) {
        listOfNotNull(CommandMark(colors.purple).takeIf { slash }, paths?.let { UploadNames(it, colors) }).reduceOrNull<OutputTransformation, OutputTransformation> { a, b -> Both(a, b) }
    }
    // A suggestion asked for whole: the program's line has it once it's taken it in.
    val haptics = haptics()
    var accepting by remember { mutableStateOf<String?>(null) }
    fun take(text: String) {
        state.edit {
            replace(0, length, text)
            placeCursorAtEnd()
        }
        focus.requestFocus()
        keyboard?.show()
    }
    /** A command starts the draft, in place of one it started with; other text goes in at the cursor. */
    fun start(text: String) {
        if (!text.startsWith('/')) {
            state.edit { replace(selection.min, selection.max, text) }
            focus.requestFocus()
            keyboard?.show()
            return
        }
        val rest = state.text.toString().replace(leadingCommand, "").trimStart()
        take(if (rest.isEmpty()) "$text " else "$text $rest")
    }
    LaunchedEffect(accepting, pending) {
        if (accepting != null && pending.isNotEmpty()) {
            accepting = null
            take(pending)
        }
    }
    LaunchedEffect(accepting) {
        val cut = accepting ?: return@LaunchedEffect
        delay(ACCEPT_WAIT_MS)
        if (accepting == cut) { // it didn't: what was seen of it, to finish
            accepting = null
            haptics.reject()
            take(cut.removeSuffix("…"))
        }
    }
    var recalling by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(recalled) {
        recalling = recalled?.text ?: return@LaunchedEffect
        delay(RECALL_WAIT_MS) // long enough to pick it from a list
        recalling = null
    }
    LaunchedEffect(recalling, pending) {
        // That message, not what's typed on the way (/rewind) or was there before.
        val want = recalling ?: return@LaunchedEffect
        if (pending.filterNot(Char::isWhitespace) == want.filterNot(Char::isWhitespace)) {
            recalling = null
            take(pending)
        }
    }
    // Just sent, it can be stopped before the program is heard to be working.
    var sent by remember { mutableStateOf(false) }
    LaunchedEffect(sent) {
        if (sent) {
            delay(SENT_STOPS_MS)
            sent = false
        }
    }
    val clustered = keys.any { it.cluster }
    LaunchedEffect(clustered) { if (!clustered && pad.at == null) pad.close() }
    ArrowPadUp(pad, sticky::press)
    val whole = remember(paths) { paths?.let(::WholeUploads) }
    val empty by remember(state) { derivedStateOf { state.text.isEmpty() } } // not every keystroke
    // From a pane written for to one typed into, or back, the keyboard stays up for the next.
    var wasTyping by remember { mutableStateOf(typing != null) }
    LaunchedEffect(typing) {
        if ((typing != null) == wasTyping) return@LaunchedEffect
        wasTyping = typing != null
        if (!imeVisible) return@LaunchedEffect
        if (typing != null) {
            typing.start()
        } else {
            focus.requestFocus()
            keyboard?.show()
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp, top = 4.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Palette.bg1)
            .animateContentSize(Motion.spatial())
            .then(
                if (typing == null) {
                    Modifier
                } else {
                    Modifier.typing(typing) {
                        sticky.type(it, returnKeys) { text ->
                            typed = true
                            onType(text)
                        }
                    }
                },
            )
            .padding(top = if (typing == null) 14.dp else 8.dp, bottom = 8.dp),
    ) {
        if (typing == null) {
            AttachmentsRow(state, paths, attachments)
            Box(Modifier.fillMaxWidth().animateContentSize(Motion.spatial()).padding(horizontal = 18.dp)) {
                val armed = sticky.armed
                BasicTextField(
                    state = state,
                    inputTransformation = sticky.intercept ?: whole,
                    outputTransformation = named,
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
                    textStyle = TextStyle(color = Palette.fg, fontFamily = Mono, fontSize = 15.sp, lineHeight = 21.sp),
                    cursorBrush = SolidColor(Palette.orange),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = true,
                        imeAction = ImeAction.Default,
                    ),
                    decorator = { field ->
                        Box {
                            if (empty && (armed != null || ghost.isEmpty())) {
                                val hint = armed?.let { "${it.label} + key" } ?: "send to ${program.ifEmpty { "pane" }}"
                                Text(hint, color = if (armed != null) Palette.orange else Palette.dim, fontFamily = Mono, fontSize = 15.sp)
                            }
                            field()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (empty && armed == null && ghost.isNotEmpty()) {
                    val type = {
                        focus.requestFocus()
                        keyboard?.show()
                    }
                    GhostLine(ghost, suggested = pending.isEmpty(), onClear = onClear, onTap = { type() }) {
                        if (pending.isEmpty() && ghost.endsWith('…') && onAcceptSuggestion != null) {
                            accepting = ghost
                            onAcceptSuggestion()
                        } else {
                            take(ghost)
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = if (typing == null) 10.dp else 0.dp, start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (typing == null) {
                AttachButton(
                    enabled = connected,
                    typing = { imeVisible },
                    onAttach = attachments.onAttach,
                    onBack = { refocus = true },
                    commands = commands,
                    onCommand = { c -> start(c.text) },
                )
            }
            KeyRow(
                keys,
                sticky.armed,
                shortcuts,
                pad,
                onKey = sticky::press,
                onShortcut = { key ->
                    sticky.drop()
                    val text = (KeyBar.steps(key.keys).singleOrNull() as? Step.Text)?.takeIf { !it.enter && typing == null }
                    if (text != null) {
                        start(text.text)
                    } else {
                        typed = key.keys.lastOrNull() != "Enter"
                        onShortcut(key)
                    }
                },
                onEdit = onEditKeys,
                onAddShortcut = onAddShortcut,
                modifier = Modifier.weight(1f),
            )
            val action = when {
                typing != null -> if (working && !typed && pending.isEmpty()) Action.Stop else Action.Run
                !empty && holds -> Action.Hold
                !empty && queues -> Action.Queue
                !empty -> Action.Send
                pending.isNotEmpty() -> Action.Enter
                stoppable || sent -> Action.Stop
                else -> Action.Idle
            }
            SendButton(
                action = action,
                connected = connected,
                label = if (action != Action.Run || sendKeys == listOf("Enter")) null else faceOf(sendKeys),
                onSend = { enter ->
                    if (onSend(state.text.toString(), enter)) {
                        state.clearText()
                        sent = enter
                    }
                },
                onEnter = {
                    typed = false
                    onEnter()
                },
                onStop = {
                    typed = false
                    onStop()
                },
            )
        }
    }
}

/** How long a suggestion asked for whole has to come in before what was seen of it does. */
private const val ACCEPT_WAIT_MS = 1_500L

/** How long after sending the button can stop what's sent, whatever the program was last heard doing. */
private const val SENT_STOPS_MS = 3_000L

/** A sent message asked back to edit, its [text], each time it's asked ([at]). */
internal data class Recalled(val text: String, val at: Long)

/** How long a message asked back has to come back into the program's line to be taken into the box. */
private const val RECALL_WAIT_MS = 60_000L

/** A command a draft starts with. */
private val leadingCommand = Regex("^/[^\\s]+\\s*")

/** A command the draft starts with, marked in [color]. */
private class CommandMark(private val color: Color) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        val m = leadingCommand.find(asCharSequence()) ?: return
        addStyle(SpanStyle(color = color), 0, m.value.trimEnd().length)
    }
}

/** Two transformations, one after the other. */
private class Both(private val first: OutputTransformation, private val then: OutputTransformation) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        with(first) { transformOutput() }
        with(then) { transformOutput() }
    }
}
