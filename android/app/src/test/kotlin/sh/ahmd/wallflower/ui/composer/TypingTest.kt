package sh.ahmd.wallflower.ui.composer

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.ahmd.wallflower.BarKey

class TypingTest {
    private fun key(code: Int, char: Char? = null, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) =
        typed(code, char?.code ?: 0, ctrl, alt, shift)

    @Test
    fun aCharacterIsTypedAsItIs() {
        assertEquals(Typed.Text("a"), key(KeyEvent.KEYCODE_A, 'a'))
        assertEquals(Typed.Text("A"), key(KeyEvent.KEYCODE_A, 'A', shift = true))
        assertEquals(Typed.Text(" "), key(KeyEvent.KEYCODE_SPACE, ' '))
    }

    @Test
    fun keysThatDontTypeTextGoByName() {
        assertEquals(Typed.Key("Enter"), key(KeyEvent.KEYCODE_ENTER, '\n'))
        assertEquals(Typed.Key("BSpace"), key(KeyEvent.KEYCODE_DEL))
        assertEquals(Typed.Key("Escape"), key(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(Typed.Key("Up"), key(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(Typed.Key("F5"), key(KeyEvent.KEYCODE_F5))
    }

    @Test
    fun ctrlAndAltNameTheKeyTheyreHeldWith() {
        assertEquals(Typed.Key("C-c"), key(KeyEvent.KEYCODE_C, 'c', ctrl = true))
        assertEquals(Typed.Key("M-b"), key(KeyEvent.KEYCODE_B, 'b', alt = true))
        assertEquals(Typed.Key("C-Space"), key(KeyEvent.KEYCODE_SPACE, ' ', ctrl = true))
        assertEquals(Typed.Key("C-Left"), key(KeyEvent.KEYCODE_DPAD_LEFT, ctrl = true))
    }

    @Test
    fun shiftChangesOnlyKeysItMeansSomethingFor() {
        assertEquals(Typed.Key("BTab"), key(KeyEvent.KEYCODE_TAB, '\t', shift = true))
        assertEquals(Typed.Key("S-Up"), key(KeyEvent.KEYCODE_DPAD_UP, shift = true))
        assertEquals(Typed.Key("Enter"), key(KeyEvent.KEYCODE_ENTER, '\n', shift = true))
    }

    @Test
    fun keysWithNothingToTypeAreLeftAlone() {
        assertNull(key(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(key(KeyEvent.KEYCODE_SHIFT_LEFT))
    }
}

class StickyTypingTest {
    private val sent = mutableListOf<List<String>>()
    private val typed = mutableListOf<String>()
    private val sticky = StickyKeys { sent += it.keys }
    private val ctrl = BarKey("ctrl", listOf("C-"), sticky = true)

    @Test
    fun withNothingArmedTextIsTypedAndKeysSent() {
        sticky.type(Typed.Text("ls"), RETURN, typed::add)
        sticky.type(Typed.Key("Enter"), RETURN, typed::add)
        assertEquals(listOf("ls"), typed)
        assertEquals(listOf(listOf("Enter")), sent)
    }

    @Test
    fun anArmedKeyTakesTheFirstKeyTyped() {
        sticky.press(ctrl)
        sticky.type(Typed.Text("cd"), RETURN, typed::add)
        assertEquals(listOf(listOf("C-c")), sent)
        assertEquals(listOf("d"), typed)
        assertNull(sticky.armed)
    }

    @Test
    fun anArmedKeyGoesWithANamedKeyToo() {
        sticky.press(ctrl)
        sticky.type(Typed.Key("Left"), RETURN, typed::add)
        assertEquals(listOf(listOf("C-Left")), sent)
    }

    @Test
    fun theReturnKeyPressesWhatItsSetTo() {
        sticky.type(Typed.Return, RETURN, typed::add)
        assertEquals(listOf(RETURN), sent)
    }

    private companion object {
        val RETURN = listOf("C-j")
    }
}

class KeyboardEditTest {
    @Test
    fun typingOnTheEndTypesJustThat() {
        assertEquals(KeyboardEdit(0, " "), KeyboardEdit.of("ls", "ls "))
        assertEquals(KeyboardEdit(0, "hello"), KeyboardEdit.of("", "hello"))
        assertEquals(KeyboardEdit(0, ""), KeyboardEdit.of("same", "same"))
    }

    @Test
    fun aWordPutRightDeletesBackToWhereItParts() {
        // A suggestion picked, a glided word's other pick, voice typing hearing it better.
        assertEquals(KeyboardEdit(5, "ommit"), KeyboardEdit.of("git cmomit", "git commit"))
        assertEquals(KeyboardEdit(1, "ld"), KeyboardEdit.of("hello word", "hello world"))
        assertEquals(KeyboardEdit(3, ""), KeyboardEdit.of("abc", ""))
    }

    @Test
    fun deletesCountCharactersAsALineEditorDoes() {
        assertEquals(KeyboardEdit(2, ""), KeyboardEdit.of("hi 🇨🇦", "hi ")) // a flag is two code points
        assertEquals(KeyboardEdit(1, "é"), KeyboardEdit.of("cafe", "café"))
    }
}
