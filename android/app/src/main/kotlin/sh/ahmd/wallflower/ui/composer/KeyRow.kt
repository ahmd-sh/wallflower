package sh.ahmd.wallflower.ui.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import sh.ahmd.wallflower.ui.FloatAbove
import sh.ahmd.wallflower.ui.frosted
import sh.ahmd.wallflower.ui.Mono
import sh.ahmd.wallflower.ui.Opener
import sh.ahmd.wallflower.ui.chrome
import sh.ahmd.wallflower.ui.choice
import sh.ahmd.wallflower.ui.opens
import sh.ahmd.wallflower.ui.rememberOpener
import sh.ahmd.wallflower.ui.glyphs
import sh.ahmd.wallflower.ui.Type
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

/**
 * The key bar: its keys, scrolling sideways, then a pencil to edit them.
 * Held, each says what it is; the shortcuts key, pressed, offers the
 * pane's [shortcuts], and one picked goes to [onShortcut].
 */
@Composable
internal fun KeyRow(
    keys: List<BarKey>,
    armed: BarKey?,
    shortcuts: List<BarKey>,
    onKey: (BarKey) -> Unit,
    onShortcut: (BarKey) -> Unit,
    onEdit: () -> Unit,
    onEditShortcuts: () -> Unit,
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
                it.cluster -> ClusterChip(it, onKey)
                it.shortcuts -> ShortcutsChip(it, shortcuts, onShortcut, onEditShortcuts)
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

/** Pressed, the pane's shortcuts come up over it, to slide onto or tap; see [ShortcutsUp]. */
@Composable
private fun ShortcutsChip(key: BarKey, shortcuts: List<BarKey>, onShortcut: (BarKey) -> Unit, onEdit: () -> Unit) {
    val haptics = haptics()
    val menu = rememberOpener()
    var chip by remember { mutableStateOf(Rect.Zero) }
    Box {
        Pill(
            faceOf(key),
            on = false,
            pressed = menu.open,
            modifier = Modifier.height(36.dp).onGloballyPositioned { chip = it.boundsInWindow() },
            input = Modifier.opens(menu) { haptics.tick() },
        )
        ShortcutsUp(menu, chip, shortcuts, onShortcut, onEdit)
    }
}

/**
 * While [menu] is open, the pane's [shortcuts] float over the key that
 * opened it ([anchor]), frosted as the arrows are, then a pencil to change
 * them. Slid onto from that key and let go of, one goes and they shut; let
 * go of on the key, they stay up for a tap on one. What the one slid onto
 * sends shows above them.
 */
@OptIn(ExperimentalLayoutApi::class) // FlowRow
@Composable
private fun ShortcutsUp(menu: Opener, anchor: Rect, shortcuts: List<BarKey>, onShortcut: (BarKey) -> Unit, onEdit: () -> Unit) {
    if (!menu.open) return
    val haptics = haptics()
    fun pick(key: BarKey) {
        haptics.key()
        menu.open = false
        onShortcut(key)
    }
    fun edit() {
        menu.open = false
        onEdit()
    }
    FloatAbove(anchor, gap = 16.dp, onDismiss = menu::dismiss) {
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            shortcuts.getOrNull(menu.over)?.let {
                Text(
                    KeyBar.nameOf(it),
                    color = Palette.fg,
                    style = Type.label,
                    maxLines = 1,
                    modifier = Modifier.chrome().padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                shortcuts.forEachIndexed { i, s ->
                    ShortcutPlace(menu.over == i, Modifier.choice(menu, i) { pick(s) }, { pick(s) }) {
                        Text(faceOf(s), color = Palette.fg, fontFamily = Mono, fontSize = 14.sp, maxLines = 1, inlineContent = glyphs(Palette.fg))
                    }
                }
                ShortcutPlace(menu.over == shortcuts.size, Modifier.choice(menu, shortcuts.size) { edit() }, ::edit) {
                    GlyphIcon(Glyph.Edit, Palette.grey)
                }
            }
        }
    }
}

/** A shortcut floating over the bar: frosted, lit while slid onto or pressed, and [onTap] tapped. */
@Composable
private fun ShortcutPlace(lit: Boolean, modifier: Modifier, onTap: () -> Unit, content: @Composable () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .height(SHORTCUT)
            .clip(RoundedCornerShape(12.dp))
            .frosted(8.dp)
            .background(if (lit || pressed) Palette.bg3.copy(alpha = 0.85f) else Palette.bg2.copy(alpha = 0.45f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { tap() },
                )
            }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private val SHORTCUT = 40.dp

/**
 * Keys gathered in one ([BarKey.cluster]): pressed, they bubble up above
 * it, ⏎ ↑ ⌫ over ← ↓ →, frosted so what's behind them shows. Slid onto
 * and let go of, one goes and they close; tapped, they stay up to press,
 * the arrows repeating while held, till a tap anywhere else, or on this
 * key again.
 */
@Composable
private fun ClusterChip(key: BarKey, onKey: (BarKey) -> Unit) {
    val haptics = haptics()
    val keys = rememberOpener()
    var chip by remember { mutableStateOf(Rect.Zero) }
    fun send(i: Int, again: Boolean = false) {
        val k = key.keys.getOrNull(i) ?: return
        if (again) haptics.repeat() else haptics.key()
        onKey(BarKey(KeyBar.labelOf(listOf(k)), listOf(k)))
    }
    Box {
        Pill(
            faceOf(key),
            on = false,
            pressed = keys.open,
            modifier = Modifier.height(36.dp).onGloballyPositioned { chip = it.boundsInWindow() },
            input = Modifier.opens(keys) { haptics.tick() },
        )
        if (keys.open) {
            FloatAbove(chip, gap = 16.dp, onDismiss = keys::dismiss) { // clear of the composer's edge
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in PLACES) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (i in row) {
                                val name = key.keys.getOrNull(i)
                                if (i < 0 || name == null) {
                                    Spacer(Modifier.size(PLACE))
                                } else {
                                    Place(name, lit = keys.over == i, Modifier.choice(keys, i) { send(i) }) { again -> send(i, again) }
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
            .frosted(8.dp)
            .background(if (lit || pressed) Palette.bg3.copy(alpha = 0.85f) else Palette.bg2.copy(alpha = 0.45f))
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

/** Where a cluster's keys go, row by row, by their place in it ([BarKey]): top left, up, top right; left, down, right. */
private val PLACES = listOf(listOf(2, 0, 5), listOf(1, 4, 3))
private val PLACE = 46.dp

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
