package sh.ahmd.wallflower.ui.composer

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import sh.ahmd.wallflower.ui.Above
import sh.ahmd.wallflower.ui.Mono
import sh.ahmd.wallflower.ui.chrome
import sh.ahmd.wallflower.ui.glyphs
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

/** The key bar: its keys, scrolling sideways, then a pencil to edit them. Held, each says what it is. */
@Composable
internal fun KeyRow(keys: List<BarKey>, armed: BarKey?, onKey: (BarKey) -> Unit, onEdit: () -> Unit, modifier: Modifier) {
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
        items(keys) { if (it.cluster) ClusterChip(it, onKey) else KeyChip(it, armed == it, onKey) }
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
                    val hold = scope.launch {
                        delay(if (repeats) REPEAT_AFTER_MS else longPress)
                        held = true
                        if (!repeats) {
                            haptics.hold()
                            return@launch
                        }
                        fire(false)
                        while (isActive) {
                            delay(REPEAT_EVERY_MS)
                            fire(true)
                        }
                    }
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

/**
 * Keys gathered in one ([BarKey.cluster]): pressed, they bubble up above
 * it, ↑ ← ⏎ → ↓ with ⌫ above right. Slid onto and let go of, one goes and
 * they close; tapped, they stay up to press, the arrows repeating while
 * held, till a tap anywhere else, or on this key again.
 */
@Composable
private fun ClusterChip(key: BarKey, onKey: (BarKey) -> Unit) {
    val haptics = haptics()
    var open by remember { mutableStateOf(false) }
    var sliding by remember { mutableStateOf(false) }
    var over by remember { mutableIntStateOf(-1) }
    var dismissed by remember { mutableLongStateOf(0L) }
    var chip by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val places = remember { arrayOfNulls<Rect>(PLACES.flatten().count { it >= 0 }) } // on screen, by place
    fun send(i: Int, again: Boolean = false) {
        val k = key.keys.getOrNull(i) ?: return
        if (again) haptics.repeat() else haptics.key()
        onKey(BarKey(KeyBar.labelOf(listOf(k)), listOf(k)))
    }
    Box {
        Pill(
            faceOf(key),
            on = false,
            pressed = open,
            modifier = Modifier.height(36.dp).onGloballyPositioned { chip = it },
            input = Modifier.pointerInput(key) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    // A tap on it while they're up puts them away (a touch outside may have already).
                    if (open || SystemClock.uptimeMillis() - dismissed < TOGGLE_MS) {
                        open = false
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }
                    open = true
                    haptics.tick()
                    var moved = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!moved && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            moved = true
                            sliding = true
                        }
                        if (moved) {
                            val at = chip?.takeIf { it.isAttached }?.localToScreen(change.position)
                            val now = if (at == null) -1 else places.indexOfFirst { it?.contains(at) == true }
                            if (now != over && now >= 0) haptics.tick()
                            over = now
                        }
                        change.consume()
                        if (!change.pressed) break
                    }
                    if (moved) {
                        if (over >= 0) send(over)
                        open = false
                    }
                    sliding = false
                    over = -1
                }
            },
        )
        if (open) {
            val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
            Popup(
                popupPositionProvider = remember(gap) { Above(gap) },
                onDismissRequest = {
                    open = false
                    dismissed = SystemClock.uptimeMillis()
                },
                properties = PopupProperties(focusable = false), // the keyboard stays up
            ) {
                Column(Modifier.chrome().padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in PLACES) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (i in row) {
                                val name = key.keys.getOrNull(i)
                                if (i < 0 || name == null) {
                                    Spacer(Modifier.size(PLACE))
                                } else {
                                    Place(name, lit = over == i, Modifier.onGloballyPositioned { places[i] = Rect(it.positionOnScreen(), it.size.toSize()) }) { again -> send(i, again) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A key of a cluster: it sends on tap, and the arrows repeat while held. */
@Composable
private fun Place(name: String, lit: Boolean, modifier: Modifier, onPress: (again: Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val press by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }
    val repeats = name in KeyBar.repeating
    val ink = Palette.fg
    Box(
        modifier
            .size(PLACE)
            .clip(RoundedCornerShape(12.dp))
            .background(if (lit || pressed) Palette.bg3 else Palette.bg2)
            .pointerInput(name) {
                detectTapGestures(onPress = {
                    pressed = true
                    var held = false
                    val hold = if (!repeats) null else scope.launch {
                        delay(REPEAT_AFTER_MS)
                        held = true
                        press(false)
                        while (isActive) {
                            delay(REPEAT_EVERY_MS)
                            press(true)
                        }
                    }
                    val released = tryAwaitRelease()
                    hold?.cancel()
                    pressed = false
                    if (released && !held) press(false)
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(faceOf(listOf(name)), color = ink, fontFamily = Mono, fontSize = 18.sp, inlineContent = glyphs(ink))
    }
}

/** Where a cluster's keys go, by their place in it: up, left, the middle, right, down, above right. */
private val PLACES = listOf(listOf(-1, 0, 5), listOf(1, 2, 3), listOf(-1, 4, -1))
private val PLACE = 46.dp

/** How soon after a touch outside put a cluster's keys away a tap on it is taken to have been for that. */
private const val TOGGLE_MS = 300L

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

private const val REPEAT_AFTER_MS = 400L
private const val REPEAT_EVERY_MS = 50L
