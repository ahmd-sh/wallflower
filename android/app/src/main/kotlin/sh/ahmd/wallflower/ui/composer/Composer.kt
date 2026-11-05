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
import sh.ahmd.wallflower.program.Command
import sh.ahmd.wallflower.screen.InputLine
import sh.ahmd.wallflower.session.uploadPaths
import sh.ahmd.wallflower.ui.Mono
import sh.ahmd.wallflower.ui.Motion
import sh.ahmd.wallflower.ui.Palette
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
 * ([onAcceptSuggestion]), and it comes from there. What's in the box replaces the line
 * when sent, so the box is exactly what runs. The button sends (held: types
 * it in without Enter), enters the app's line when only that has text, or
 * stops.
 *
 * + sends photos or files to the Mac ([attachments]); each shows above the
 * box on its way, then its path goes in at the cursor, shown as its name.
 * What the draft holds shows above it too, each with × to take it out.
 * + also offers the program's [commands], which start the draft; one it
 * starts with is marked.
 *
 * With [typing], the keyboard types straight into the pane instead, as a
 * terminal's does ([onType], other keys to [onKey] by name, its return key
 * as [returnKeys]): no box, no +, just the key bar and the button
 * ([onEnter], pressing [sendKeys], which it shows if they aren't Enter).
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
    commands: List<Command> = emptyList(),
    onAcceptSuggestion: (() -> Unit)? = null,
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
    val sticky = rememberStickyKeys(onKey)
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
            .then(if (typing == null) Modifier else Modifier.typing(typing) { sticky.type(it, returnKeys, onType) })
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
                    onCommand = { c ->
                        val rest = state.text.toString().replace(leadingCommand, "").trimStart()
                        take(if (rest.isEmpty()) c.text + " " else c.text + " " + rest)
                    },
                )
            }
            KeyRow(keys, sticky.armed, sticky::press, onEditKeys, Modifier.weight(1f))
            SendButton(
                action = when {
                    typing != null -> Action.Run
                    !empty -> Action.Send
                    pending.isNotEmpty() -> Action.Enter
                    else -> Action.Stop
                },
                connected = connected,
                label = if (typing == null || sendKeys == listOf("Enter")) null else KeyBar.labelOf(sendKeys),
                onSend = { enter -> if (onSend(state.text.toString(), enter)) state.clearText() },
                onEnter = onEnter,
                onStop = onStop,
            )
        }
    }
}

/** How long a suggestion asked for whole has to come in before what was seen of it does. */
private const val ACCEPT_WAIT_MS = 1_500L

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
