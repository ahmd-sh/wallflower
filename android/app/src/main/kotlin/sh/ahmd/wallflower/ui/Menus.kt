package sh.ahmd.wallflower.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.PopupProperties

/** A menu's choice: what it says, in what color (unspecified, the words') and [font], what it's about beneath, and what it does. */
class MenuAction(
    val label: String,
    val color: Color = Color.Unspecified,
    val font: FontFamily? = null,
    val detail: String? = null,
    val onClick: () -> Unit,
)

/**
 * A menu dropping from what opened it, rounded and compact, its choices
 * ruled apart beneath an optional [header]. Felt as it opens and shuts. A
 * choice is tapped, or slid onto from what opened it ([opens], [slides]).
 */
@Composable
internal fun PopupMenu(
    menu: Opener,
    actions: List<MenuAction>,
    header: (@Composable () -> Unit)? = null,
    /** Leave the keyboard up while it's open, rather than taking focus. */
    keepKeyboard: Boolean = false,
) {
    val haptics = haptics()
    var felt by remember { mutableStateOf(menu.open) }
    LaunchedEffect(menu.open) {
        if (menu.open != felt) haptics.slide(if (menu.open) MENU_IN_MS else MENU_OUT_MS)
        felt = menu.open
    }
    DropdownMenu(
        expanded = menu.open,
        onDismissRequest = menu::dismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = Palette.bg1,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, Palette.border),
        properties = PopupProperties(focusable = !keepKeyboard),
    ) {
        Column(Modifier.widthIn(min = 150.dp).padding(horizontal = 4.dp)) {
            header?.let {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { it() }
                Rule(Modifier.padding(horizontal = 12.dp))
            }
            actions.forEachIndexed { i, action ->
                if (i > 0) Rule(Modifier.padding(horizontal = 12.dp))
                val pick = {
                    haptics.tick()
                    action.onClick()
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (menu.over == i) Palette.bg3 else Color.Transparent)
                        .choice(menu, i, pick)
                        .clickable(onClick = pick)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Text(action.label, color = action.color.takeOrElse { Palette.fg }, style = Type.body, fontFamily = action.font)
                    action.detail?.let { Text(it, color = Palette.grey, style = Type.label) }
                }
            }
        }
    }
}

/**
 * Whether a menu is [open], and where its choices are, so the finger that
 * opened it can slide onto one, [over] it, and let go to pick it; slid off
 * them and let go, it shuts. A touch outside a menu that leaves the
 * keyboard up puts it away and goes on to what's under it, so a press on
 * what opened it, while it's open or just after, is to shut it.
 */
@Stable
internal class Opener(private val haptics: Haptics) {
    var open by mutableStateOf(false)

    /** The choice a sliding finger is over; -1 for none. */
    var over by mutableIntStateOf(-1)
        private set
    private var shut = 0L
    private val places = HashMap<Int, LayoutCoordinates>()
    private val picks = HashMap<Int, () -> Unit>()

    /** A touch outside put it away. */
    fun dismiss() {
        open = false
        shut = SystemClock.uptimeMillis()
    }

    /** As a press on what opens it starts: whether it's to shut it. */
    fun shutting() = open || SystemClock.uptimeMillis() - shut < SHUT_MS

    fun place(i: Int, at: LayoutCoordinates, pick: () -> Unit) {
        places[i] = at
        picks[i] = pick
    }

    fun forget(i: Int) {
        places.remove(i)
        picks.remove(i)
    }

    /** The finger is [to] on screen (null: it can't be said), sliding. */
    fun slide(to: Offset?) {
        val now = if (to == null) -1 else places.entries.firstOrNull { (_, c) -> c.isAttached && Rect(c.positionOnScreen(), c.size.toSize()).contains(to) }?.key ?: -1
        if (now != over && now >= 0) haptics.tick()
        over = now
    }

    /** The sliding finger let go: what it's over is picked, and the menu shuts. */
    fun letGo() {
        val i = over
        over = -1
        open = false
        if (i >= 0) picks[i]?.invoke()
    }
}

@Composable
internal fun rememberOpener(): Opener {
    val haptics = haptics()
    return remember(haptics) { Opener(haptics) }
}

/** Choice [i] of [menu], which [pick]s it, slid onto as well as tapped. */
@Composable
internal fun Modifier.choice(menu: Opener, i: Int, pick: () -> Unit): Modifier {
    val picking by rememberUpdatedState(pick)
    DisposableEffect(menu, i) { onDispose { menu.forget(i) } }
    return onGloballyPositioned { at -> menu.place(i, at) { picking() } }
}

/**
 * On what opens [menu] with a tap: pressed, it opens ([onOpen] first), or
 * shuts if it's open; the finger can slide on from it to a choice.
 */
@Composable
internal fun Modifier.opens(menu: Opener, enabled: Boolean = true, onOpen: () -> Unit = {}): Modifier {
    val opening by rememberUpdatedState(onOpen)
    val anchor = remember { Anchor() }
    return this
        .onGloballyPositioned { anchor.at = it }
        .semantics {
            role = Role.Button
            if (!enabled) disabled()
            onClick {
                if (!menu.open) opening()
                menu.open = !menu.open
                true
            }
        }
        .pointerInput(menu, enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                if (menu.shutting()) {
                    menu.open = false
                    waitForUpOrCancellation()
                    return@awaitEachGesture
                }
                opening()
                menu.open = true
                follow(menu, down.id, down.position, anchor)
            }
        }
}

/** On what opens [menu] with a hold (its own [androidx.compose.foundation.combinedClickable]): the finger that held it can slide on to a choice. */
@Composable
internal fun Modifier.slides(menu: Opener): Modifier {
    val anchor = remember { Anchor() }
    return this
        .onGloballyPositioned { anchor.at = it }
        .pointerInput(menu) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (!menu.open) follow(menu, down.id, null, anchor)
            }
        }
}

private class Anchor {
    var at: LayoutCoordinates? = null
}

/**
 * Follows the finger [id] on what opens [menu], once the menu's open (from
 * [start], or from when it opens): its moves are the menu's, not for what
 * scrolls beneath, and if it slides, letting go picks what it's over.
 */
private suspend fun AwaitPointerEventScope.follow(menu: Opener, id: PointerId, start: Offset?, anchor: Anchor) {
    var from = start
    var moved = false
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == id } ?: break
        if (from == null && menu.open) from = change.position
        from?.let { f ->
            if (!moved && (change.position - f).getDistance() > viewConfiguration.touchSlop) moved = true
            if (moved) menu.slide(anchor.at?.takeIf { it.isAttached }?.localToScreen(change.position))
            change.consume()
        }
        if (!change.pressed) break
    }
    if (moved) menu.letGo()
}

/** How soon after a touch outside put a menu away a press on what opened it is taken to have been for that. */
private const val SHUT_MS = 300L

/** How long a menu takes to open and to shut (Material's). */
private const val MENU_IN_MS = 120L
private const val MENU_OUT_MS = 75L
