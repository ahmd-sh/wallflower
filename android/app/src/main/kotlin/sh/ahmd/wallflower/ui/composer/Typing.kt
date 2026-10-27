package sh.ahmd.wallflower.ui.composer

import android.content.Context
import android.text.InputType
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
 * no suggestions or autocorrect, text as it's typed, every other key by its
 * tmux name, from the keyboard on screen or one plugged in.
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
}

/** What a key typed straight into a pane is: [Text] typed as it is, or a [Key] by its tmux name. */
internal sealed interface Typed {
    data class Text(val text: String) : Typed
    data class Key(val name: String) : Typed
}

/**
 * A key pressed, by its Android [code] and the character it types ([char],
 * without ctrl or alt; 0 for none), as tmux takes it: keys that don't type
 * text, or any key with ctrl or alt held, by name (`Enter`, `C-c`, `M-b`,
 * `S-Up`); anything else as its text. Null for keys a terminal has no use for.
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

    /** Lets go of the keyboard: Compose puts it away, unless something else takes it up. */
    private fun end() {
        session?.cancel()
        session = null
        view = null
    }

    override fun onKeyEvent(event: ComposeKeyEvent): Boolean =
        event.type == KeyEventType.KeyDown && press(event.nativeKeyEvent)

    override fun onPreKeyEvent(event: ComposeKeyEvent) = false

    private fun press(e: KeyEvent): Boolean {
        val char = e.getUnicodeChar(e.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
        val t = typed(e.keyCode, char, e.isCtrlPressed, e.isAltPressed, e.isShiftPressed) ?: return false
        onTyped(t)
        return true
    }

    /**
     * What the keyboard is told it types into: a field that wants exact
     * characters, as Termux asks for. Text comes as it's committed (the
     * keyboard may hold a word back while it composes it), and the
     * connection's own text is emptied each time, so it never has anything
     * to delete: ⌫ and Enter come as keys.
     */
    private inner class Request(private val host: View) : PlatformTextInputMethodRequest {
        override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
            outAttributes.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            outAttributes.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
            return object : BaseInputConnection(host, true) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    super.commitText(text, newCursorPosition)
                    flush()
                    return true
                }

                override fun finishComposingText(): Boolean {
                    super.finishComposingText()
                    flush()
                    return true
                }

                // Some keyboards delete by asking for text before the cursor to go.
                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    repeat(beforeLength) { onTyped(Typed.Key("BSpace")) }
                    return super.deleteSurroundingText(beforeLength, afterLength)
                }

                override fun performEditorAction(editorAction: Int): Boolean {
                    onTyped(Typed.Key("Enter"))
                    return true
                }

                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    if (event.action == KeyEvent.ACTION_DOWN) press(event)
                    return true
                }

                private fun flush() {
                    val content = editable ?: return
                    if (content.isNotEmpty()) onTyped(Typed.Text(content.toString()))
                    content.clear()
                }
            }
        }
    }
}

private val View.imm get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
