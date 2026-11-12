package sh.ahmd.wallflower.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import sh.ahmd.wallflower.BarKey
import sh.ahmd.wallflower.KeyBar

/** How [key] shows: as [faceOf] its keys, unless the user wrote its label. */
internal fun faceOf(key: BarKey): AnnotatedString = when {
    key.cluster -> buildAnnotatedString { appendInlineContent(Glyph.Arrows.name, key.label) }
    key.shortcuts -> buildAnnotatedString { appendInlineContent(Glyph.Bolt.name, key.label) }
    key.ownLabel -> faceOf(key.keys)
    else -> AnnotatedString(key.label)
}

/** [keys] with icons for those that have one (esc, tab, ⏎, the arrows, ctrl, shift…) and the rest as the bar names them (alt, which keyboards mark differently). Shown with [glyphs]. */
internal fun faceOf(keys: List<String>): AnnotatedString = buildAnnotatedString {
    keys.forEachIndexed { i, key ->
        if (i > 0) append(' ')
        val (held, rest) = KeyBar.held(key)
        for (m in held) KEYS["$m-"]?.let { appendInlineContent(it.name, KeyBar.nameOf(listOf("$m-")) + " ") } ?: append(KeyBar.modifierLabel(m))
        val glyph = KEYS[rest]
        if (glyph != null) appendInlineContent(glyph.name, KeyBar.nameOf(listOf(rest))) else append(KeyBar.labelOf(listOf(rest)))
    }
}

/** The icons in a face, drawn in [color] at the size of its letters. */
internal fun glyphs(color: Color): Map<String, InlineTextContent> = (KEYS.values + Glyph.Arrows + Glyph.Bolt).associate { g ->
    g.name to InlineTextContent(Placeholder(1.15.em, 1.15.em, PlaceholderVerticalAlign.Center)) { GlyphIcon(g, color, Modifier.fillMaxSize()) }
}

/** Where [face]'s icons go, as [glyphs] draws them, for measuring it without drawing it. */
internal fun glyphPlaceholders(face: AnnotatedString): List<AnnotatedString.Range<Placeholder>> =
    face.getStringAnnotations(INLINE_CONTENT, 0, face.length).map { AnnotatedString.Range(Placeholder(1.15.em, 1.15.em, PlaceholderVerticalAlign.Center), it.start, it.end) }

/** The tag appendInlineContent marks its places with. */
private const val INLINE_CONTENT = "androidx.compose.foundation.text.inlineContent"

/** Calls [onUp] when a press here lets go, however it ended. */
internal fun Modifier.onLetGo(onUp: () -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
        } while (event.changes.any { it.pressed })
        onUp()
    }
}

/** What a held key is, in words, just above it. */
@Composable
internal fun HeldName(name: String) {
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    Popup(popupPositionProvider = remember(gap) { Above(gap) }) {
        Text(
            name,
            color = Palette.fg,
            fontFamily = words,
            fontSize = wordSize(12.sp, 14.sp),
            maxLines = 1,
            modifier = Modifier.chrome().padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** Centered over what it's for, [gap] above it, kept on screen. */
internal class Above(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset(
        (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        anchorBounds.top - popupContentSize.height - gap,
    )
}

private val KEYS = mapOf(
    "C-" to Glyph.Ctrl, "S-" to Glyph.Shift,
    "Tab" to Glyph.Tab, "BTab" to Glyph.BackTab, "Enter" to Glyph.Enter, "BSpace" to Glyph.Backspace, "Space" to Glyph.Space,
    "Up" to Glyph.Up, "Down" to Glyph.Down, "Left" to Glyph.Left, "Right" to Glyph.Right,
)
