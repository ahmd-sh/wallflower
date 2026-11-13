package sh.ahmd.wallflower.ui.composer

import android.content.Context
import android.text.InputType
import android.text.Selection
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.type
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

/**
 * The phone's keyboard typing straight into a pane, as a terminal takes it:
 * text as it's typed, every other key by its name, from the keyboard on
 * screen or one plugged in. To the keyboard it's a text field, so glide
 * typing, voice typing and its suggestions work (no autocorrect); each
 * change it makes goes to the pane at once (see [KeyboardEdit]).
 */
@Stable
internal class Typing {
    internal val focus = FocusRequester()
    internal var node: TypingNode? = null
    internal var wantsKeyboard = false

    /** Brings the keyboard up to type with. */
    fun start() {
        wantsKeyboard = true
        node?.showIfTyping()
        focus.requestFocus()
    }

    /** A key went that the keyboard didn't type: what it typed is the pane's now, not its to change. */
    fun forget() {
        node?.restart()
    }
}

/**
 * What a change of the keyboard's text comes to in a pane, which only
 * takes keys at its cursor (the text's end): [deletes] ⌫ back to where old
 * and new part, a character (code point) at a time, then the rest [typed].
 */
internal data class KeyboardEdit(val deletes: Int, val typed: String) {
    companion object {
        fun of(old: String, new: String): KeyboardEdit {
            val o = old.codePoints().toArray()
            val n = new.codePoints().toArray()
            var same = 0
            while (same < o.size && same < n.size && o[same] == n[same]) same++
            return KeyboardEdit(o.size - same, String(n, same, n.size - same))
        }
    }
}

/**
 * What a key typed straight into a pane is: [Text] typed as it is, a [Key]
 * by its name, or the keyboard on screen's [Return] key, which does
 * what the program's profile says.
 */
internal sealed interface Typed {
    data class Text(val text: String) : Typed
    data class Key(val name: String) : Typed
    data object Return : Typed
}

/**
 * A key pressed, by its Android [code] and the character it types ([char],
 * without ctrl or alt; 0 for none), as wallflower names keys: keys that
 * don't type text, or any key with ctrl or alt held, by name (`Enter`,
 * `C-c`, `M-b`, `S-Up`); anything else as its text. Null for keys a
 * terminal has no use for.
 */
internal fun typed(code: Int, char: Int, ctrl: Boolean, alt: Boolean, shift: Boolean): Typed? {
    val named = NAMES[code]
    val name = when {
        named != null -> if (named == "Tab" && shift) "BTab" else named
        char == 0 || char and KeyCharacterMap.COMBINING_ACCENT != 0 -> return null
        !ctrl && !alt -> return Typed.Text(String(Character.toChars(char)))
        char == ' '.code -> "Space"
        else -> String(Character.toChars(char))
    }
    val prefix = buildString {
        if (ctrl) append("C-")
        if (alt) append("M-")
        if (shift && named != null && named in SHIFTABLE) append("S-")
    }
    return Typed.Key(prefix + name)
}

private val NAMES = mapOf(
    KeyEvent.KEYCODE_ENTER to "Enter", KeyEvent.KEYCODE_NUMPAD_ENTER to "Enter",
    KeyEvent.KEYCODE_DEL to "BSpace", KeyEvent.KEYCODE_FORWARD_DEL to "DC",
    KeyEvent.KEYCODE_TAB to "Tab", KeyEvent.KEYCODE_ESCAPE to "Escape",
    KeyEvent.KEYCODE_DPAD_UP to "Up", KeyEvent.KEYCODE_DPAD_DOWN to "Down",
    KeyEvent.KEYCODE_DPAD_LEFT to "Left", KeyEvent.KEYCODE_DPAD_RIGHT to "Right",
    KeyEvent.KEYCODE_MOVE_HOME to "Home", KeyEvent.KEYCODE_MOVE_END to "End",
    KeyEvent.KEYCODE_PAGE_UP to "PageUp", KeyEvent.KEYCODE_PAGE_DOWN to "PageDown",
    KeyEvent.KEYCODE_INSERT to "IC",
) + (1..12).associate { KeyEvent.KEYCODE_F1 + it - 1 to "F$it" }

/** Named keys a held shift changes; for the rest (Enter, ⌫, Escape) it's the same key. */
private val SHIFTABLE = setOf("Up", "Down", "Left", "Right", "Home", "End", "PageUp", "PageDown", "DC", "IC") + (1..12).map { "F$it" }

/**
 * Takes the keyboard while focused (see [Typing.focus]), handing what's
 * typed to [onTyped].
 */
internal fun Modifier.typing(typing: Typing, onTyped: (Typed) -> Unit): Modifier =
    focusRequester(typing.focus) then TypingElement(typing, onTyped) then focusable()

private data class TypingElement(val typing: Typing, val onTyped: (Typed) -> Unit) : ModifierNodeElement<TypingNode>() {
    override fun create() = TypingNode(typing, onTyped)

    override fun update(node: TypingNode) {
        if (node.typing !== typing) {
            if (node.typing.node === node) node.typing.node = null
            node.typing = typing
            typing.node = node
        }
        node.onTyped = onTyped
    }
}

internal class TypingNode(var typing: Typing, var onTyped: (Typed) -> Unit) :
    Modifier.Node(), FocusEventModifierNode, PlatformTextInputModifierNode, KeyInputModifierNode {
    private var session: Job? = null
    private var view: View? = null

    override fun onAttach() {
        typing.node = this
    }

    override fun onDetach() {
        if (typing.node === this) typing.node = null
        end()
    }

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused == (session != null)) return
        if (!focusState.isFocused) return end()
        session = coroutineScope.launch {
            establishTextInputSession {
                this@TypingNode.view = view
                launch { showIfTyping() } // runs once the input method has started
                startInputMethod(Request(view))
            }
        }
    }

    /** Shows the keyboard, if asked to and the input method has started. */
    fun showIfTyping() {
        val v = view ?: return
        if (!typing.wantsKeyboard) return
        typing.wantsKeyboard = false
        v.imm.showSoftInput(v, 0)
    }

    /** The keyboard starts afresh, with nothing it typed: a new connection, empty. */
    fun restart() {
        val v = view ?: return
        v.post { if (view === v) v.imm.restartInput(v) }
    }

    /** Lets go of the keyboard: Compose puts it away, unless something else takes it up. */
    private fun end() {
        session?.cancel()
        session = null
        view = null
    }

    override fun onKeyEvent(event: ComposeKeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val e = event.nativeKeyEvent
        if (!press(e, onScreen = false)) return false
        if (e.keyCode != KeyEvent.KEYCODE_DEL && (e.keyCode in NAMES || e.isCtrlPressed || e.isAltPressed)) restart() // an arrow, ctrl with a key
        return true
    }

    override fun onPreKeyEvent(event: ComposeKeyEvent) = false

    /** A key pressed, [onScreen] on the keyboard's, whose Enter is its return key; a plugged-in keyboard's is Enter. */
    private fun press(e: KeyEvent, onScreen: Boolean): Boolean {
        if (onScreen && e.keyCode == KeyEvent.KEYCODE_ENTER && !e.isCtrlPressed && !e.isAltPressed) {
            onTyped(Typed.Return)
            return true
        }
        val char = e.getUnicodeChar(e.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
        val t = typed(e.keyCode, char, e.isCtrlPressed, e.isAltPressed, e.isShiftPressed) ?: return false
        onTyped(t)
        return true
    }

    /**
     * What the keyboard is told it types into: a line of web text, which
     * keeps its suggestions, glide and voice typing but not autocorrect
     * (Gboard corrects plain text whatever its flags say), and isn't learnt
     * from (passwords go through here). Its text is what it's typed since it
     * last started afresh; the pane has all of it ([sent]).
     */
    private inner class Request(private val host: View) : PlatformTextInputMethodRequest {
        override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
            outAttributes.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
            outAttributes.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            outAttributes.initialSelStart = 0
            outAttributes.initialSelEnd = 0
            return Connection(host)
        }
    }

    private inner class Connection(private val host: View) : BaseInputConnection(host, true) {
        private var sent = ""
        private var batch = 0

        override fun beginBatchEdit(): Boolean {
            batch++
            return super.beginBatchEdit()
        }

        override fun endBatchEdit(): Boolean {
            batch = maxOf(batch - 1, 0)
            val more = super.endBatchEdit()
            sync()
            return more
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int) = super.commitText(text, newCursorPosition).also { sync() }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int) = super.setComposingText(text, newCursorPosition).also { sync() }

        override fun setComposingRegion(start: Int, end: Int) = super.setComposingRegion(start, end).also { sync() }

        override fun finishComposingText() = super.finishComposingText().also { sync() }

        override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?) = super.commitCompletion(text).also { sync() }

        override fun commitCorrection(correctionInfo: android.view.inputmethod.CorrectionInfo?) = super.commitCorrection(correctionInfo).also { sync() }

        // Some keyboards delete by asking for text before the cursor to go, even with none of their own: that's the pane's line.
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            beyond(beforeLength)
            return super.deleteSurroundingText(beforeLength, afterLength).also { sync() }
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            val content = editable
            val cursor = content?.let { Selection.getSelectionStart(it) } ?: 0
            val have = if (content != null && cursor > 0) Character.codePointCount(content, 0, cursor) else 0
            repeat(maxOf(beforeLength - have, 0)) { onTyped(Typed.Key("BSpace")) }
            return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength).also { sync() }
        }

        /** ⌫s asked for past the start of what the keyboard typed, which go to the pane's own line. */
        private fun beyond(count: Int) {
            val cursor = editable?.let { Selection.getSelectionStart(it) } ?: 0
            repeat(maxOf(count - maxOf(cursor, 0), 0)) { onTyped(Typed.Key("BSpace")) }
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            onTyped(Typed.Return)
            restart()
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            if (press(event, onScreen = true) && event.keyCode != KeyEvent.KEYCODE_DEL) restart()
            return true
        }

        /** The keyboard's text as it is now, gone to the pane as keys. */
        private fun sync() {
            if (batch > 0) return
            val content = editable ?: return
            val now = content.toString()
            if (now == sent) return
            val e = KeyboardEdit.of(sent, now)
            sent = now
            repeat(e.deletes) { onTyped(Typed.Key("BSpace")) }
            when {
                e.typed.isEmpty() -> {}
                e.typed == "\n" -> {
                    onTyped(Typed.Return)
                    restart()
                }
                else -> onTyped(Typed.Text(e.typed))
            }
            host.imm.updateSelection(host, Selection.getSelectionStart(content), Selection.getSelectionEnd(content), getComposingSpanStart(content), getComposingSpanEnd(content))
        }
    }
}

private val View.imm get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
