package sh.ahmd.wallflower.ui.composer

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import sh.ahmd.wallflower.ui.rememberOpener
import sh.ahmd.wallflower.ui.toggles
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import sh.ahmd.wallflower.BarKey
import sh.ahmd.wallflower.KeyBar
import sh.ahmd.wallflower.ui.Glyph
import sh.ahmd.wallflower.ui.GlyphIcon
import sh.ahmd.wallflower.ui.HeldName
import sh.ahmd.wallflower.ui.Palette
import sh.ahmd.wallflower.ui.Pill
import sh.ahmd.wallflower.ui.faceOf
import sh.ahmd.wallflower.ui.haptics
import sh.ahmd.wallflower.ui.onLetGo

/**
 * The key bar: its keys, scrolling sideways, then a pencil to edit them.
 * Held, each says what it is; the shortcuts key, tapped, offers the
 * pane's [shortcuts], and one picked goes to [onShortcut], or a new one
 * to add ([onAddShortcut]); the arrows key brings up their [pad].
 */
@Composable
internal fun KeyRow(
    keys: List<BarKey>,
    armed: BarKey?,
    shortcuts: List<BarKey>,
    pad: ArrowPad,
    onKey: (BarKey) -> Unit,
    onShortcut: (BarKey) -> Unit,
    onEdit: () -> Unit,
    onAddShortcut: () -> Unit,
    modifier: Modifier,
) {
    val list = rememberLazyListState()
    LazyRow(
        state = list,
        modifier = modifier
            .padding(end = 8.dp)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                // Fade out toward the send button while there's more to scroll to.
                if (list.canScrollForward) {
                    val fade = 20.dp.toPx()
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
                        topLeft = Offset(size.width - fade, 0f),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
        contentPadding = PaddingValues(end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(keys) {
            when {
                it.cluster -> ClusterChip(it, pad)
                it.shortcuts -> ShortcutsChip(it, shortcuts, onShortcut, onAddShortcut)
                else -> KeyChip(it, armed == it, onKey)
            }
        }
        item { EditKeys(onEdit) }
    }
}

/**
 * Sends on tap; keys like the arrows repeat while held. A sticky key
 * toggles, and feels like it. Held, a key says what it sends, and one that
 * doesn't repeat doesn't send.
 */
@Composable
private fun KeyChip(key: BarKey, armed: Boolean, onKey: (BarKey) -> Unit) {
    val haptics = haptics()
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    val repeats = key.keys.size == 1 && key.keys[0] in KeyBar.repeating
    val isArmed by rememberUpdatedState(armed)
    val fire = { again: Boolean ->
        when {
            key.sticky -> haptics.toggle(!isArmed)
            again -> haptics.repeat()
            else -> haptics.key()
        }
        onKey(key)
    }
    Box {
        Pill(
            faceOf(key),
            on = armed,
            pressed = pressed,
            modifier = Modifier.height(36.dp),
            input = Modifier.pointerInput(key) {
                val longPress = viewConfiguration.longPressTimeoutMillis
                detectTapGestures(onPress = {
                    pressed = true
                    val hold = scope.holding(repeats, if (repeats) REPEAT_AFTER_MS else longPress, onHeld = {
                        held = true
                        if (!repeats) haptics.hold()
                    }, fire = fire)
                    val released = tryAwaitRelease() // false if the row scrolled instead
                    hold.cancel()
                    pressed = false
                    if (released && !held) fire(false)
                    held = false
                })
            },
        )
        if (held) HeldName(KeyBar.nameOf(key))
    }
}

/** Tapped, the pane's shortcuts come up over it; see [ShortcutsUp]. */
@Composable
private fun ShortcutsChip(key: BarKey, shortcuts: List<BarKey>, onShortcut: (BarKey) -> Unit, onAdd: () -> Unit) {
    val haptics = haptics()
    val menu = rememberOpener()
    var chip by remember { mutableStateOf(Rect.Zero) }
    Box {
        Pill(
            faceOf(key),
            on = false,
            pressed = menu.open,
            modifier = Modifier.height(36.dp).onGloballyPositioned { chip = it.boundsInWindow() },
            input = Modifier.toggles(menu) { haptics.tick() },
        )
        ShortcutsUp(menu, chip, shortcuts, onShortcut, onAdd)
    }
}

/** Keys gathered in one ([BarKey.cluster]): tapped, they come up on their [pad], above it. */
@Composable
private fun ClusterChip(key: BarKey, pad: ArrowPad) {
    val haptics = haptics()
    Pill(
        faceOf(key),
        on = false,
        pressed = pad.open,
        modifier = Modifier.height(36.dp).onGloballyPositioned { pad.home = it.boundsInWindow() },
        input = Modifier.pointerInput(key) {
            detectTapGestures {
                haptics.tick()
                pad.press(key)
            }
        },
    )
}

/** The pencil that opens the bar's keys for editing. */
@Composable
private fun EditKeys(onEdit: () -> Unit) {
    val haptics = haptics()
    var held by remember { mutableStateOf(false) }
    Box(
        Modifier
            .clip(CircleShape)
            .onLetGo { held = false }
            .combinedClickable(
                hapticFeedbackEnabled = false, // its own feel, which heeds the setting
                onLongClick = {
                    haptics.hold()
                    held = true
                },
                onClick = onEdit,
            )
            .padding(10.dp),
    ) {
        GlyphIcon(Glyph.Edit, Palette.grey)
        if (held) HeldName("edit keys")
    }
}

/**
 * A key held down: after [after], [onHeld]; then, for one that [repeats],
 * [fire] (again after the first) 20 times a second, till the job's
 * cancelled, as letting go or the row scrolling does.
 */
internal fun CoroutineScope.holding(repeats: Boolean, after: Long = REPEAT_AFTER_MS, onHeld: () -> Unit, fire: (again: Boolean) -> Unit): Job =
    launch {
        delay(after)
        onHeld()
        if (!repeats) return@launch
        fire(false)
        while (isActive) {
            delay(REPEAT_EVERY_MS)
            fire(true)
        }
    }

private const val REPEAT_AFTER_MS = 400L
private const val REPEAT_EVERY_MS = 50L
