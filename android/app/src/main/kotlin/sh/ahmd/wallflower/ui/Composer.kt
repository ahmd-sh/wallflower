package sh.ahmd.wallflower.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
import sh.ahmd.wallflower.terminal.InputLine
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import sh.ahmd.wallflower.BarKey
import sh.ahmd.wallflower.KeyBar

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
 * typing; a tap brings either into the box to edit. What's in the box
 * replaces the line when sent, so the box is exactly what runs. The button
 * sends (held: types it in without Enter), enters the app's line when only
 * that has text, or stops.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class) // edit changes for sticky keys; whether the keyboard is up
@Composable
fun Composer(
    state: TextFieldState,
    program: String,
    keys: List<BarKey>,
    connected: Boolean,
    line: InputLine?,
    onClear: (() -> Unit)?,
    onKey: (BarKey) -> Unit,
    onSend: (text: String, enter: Boolean) -> Boolean,
    onEnter: () -> Unit,
    onStop: () -> Unit,
    onEditKeys: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // The box keeps hold while the keyboard hides, as it does itself for voice typing. Moving to another
    // pane's draft with the keyboard shut, it lets go first, so the draft doesn't raise the keyboard.
    val imeVisible by rememberUpdatedState(WindowInsets.isImeVisible)
    val focusManager = LocalFocusManager.current
    var box by remember { mutableStateOf(state) }
    LaunchedEffect(state) {
        if (!imeVisible) focusManager.clearFocus()
        box = state
    }
    val pending = line?.text.orEmpty()
    val ghost = pending.ifEmpty { line?.suggestion.orEmpty() }
    val haptics = haptics()
    var armed by remember { mutableStateOf<BarKey?>(null) }
    val press: (BarKey) -> Unit = { key ->
        val sticky = armed
        when {
            key.sticky -> armed = if (sticky == key) null else key
            sticky != null -> {
                armed = null
                onKey(sticky.then(key))
            }
            else -> onKey(key)
        }
    }
    // While a sticky key waits, the next character typed goes with it instead of into the box.
    val intercept = remember(armed) {
        armed?.let {
            InputTransformation {
                val typed = buildString {
                    for (i in 0 until changes.changeCount) {
                        val range = changes.getRange(i)
                        append(asCharSequence(), range.min, range.max)
                    }
                }
                revertAllChanges()
                val c = typed.firstOrNull()
                if (c == null) armed = null else press(BarKey.typed(c))
            }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp, top = 4.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Palette.bg1)
            .padding(top = 14.dp, bottom = 8.dp),
    ) {
        Box(Modifier.fillMaxWidth().animateContentSize(Motion.spatial()).padding(horizontal = 18.dp)) {
            BasicTextField(
                state = box,
                inputTransformation = intercept,
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
                        if (box.text.isEmpty() && (armed != null || ghost.isEmpty())) {
                            val hint = armed?.let { "${it.label} + key" } ?: "send to ${program.ifEmpty { "pane" }}"
                            Text(hint, color = if (armed != null) Palette.orange else Palette.dim, fontFamily = Mono, fontSize = 15.sp)
                        }
                        field()
                    }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            if (box.text.isEmpty() && armed == null && ghost.isNotEmpty()) {
                // The app's line or its suggestion, over the empty box: tap to bring it in and edit it.
                val suggested = pending.isEmpty()
                val clearable = !suggested && onClear != null
                // Only the words take the tap: beside them, the box takes it, to type afresh.
                Row(Modifier.padding(end = if (clearable) 30.dp else 0.dp), verticalAlignment = Alignment.Top) {
                    if (suggested) GlyphIcon(Glyph.Spark, Palette.dim, Modifier.padding(top = 5.dp, end = 8.dp), size = 11.dp)
                    Text(
                        ghost,
                        color = if (suggested) Palette.dim else Palette.grey,
                        fontFamily = Mono,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                haptics.tick()
                                box.edit {
                                    replace(0, length, ghost)
                                    placeCursorAtEnd()
                                }
                                focus.requestFocus()
                                keyboard?.show()
                            },
                    )
                }
                if (clearable) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .clip(CircleShape)
                            .clickable {
                                haptics.confirm()
                                onClear?.invoke()
                            }
                            .padding(4.dp),
                    ) { GlyphIcon(Glyph.Close, Palette.grey, size = 13.dp) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp, start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyRow(keys, armed, press, onEditKeys, Modifier.weight(1f))
            SendButton(
                action = when {
                    box.text.isNotEmpty() -> Action.Send
                    pending.isNotEmpty() -> Action.Enter
                    else -> Action.Stop
                },
                connected = connected,
                onSend = { enter -> if (onSend(box.text.toString(), enter)) box.clearText() },
                onEnter = onEnter,
                onStop = onStop,
            )
        }
    }
}

@Composable
private fun KeyRow(keys: List<BarKey>, armed: BarKey?, onKey: (BarKey) -> Unit, onEdit: () -> Unit, modifier: Modifier) {
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
        items(keys) { KeyChip(it, armed == it, onKey) }
        item {
            Text(
                "✎",
                color = Palette.grey,
                fontFamily = Mono,
                fontSize = 14.sp,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onEdit).padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

/** Sends on tap; keys like the arrows repeat while held. A sticky key toggles, and feels like it. */
@Composable
private fun KeyChip(key: BarKey, armed: Boolean, onKey: (BarKey) -> Unit) {
    val haptics = haptics()
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
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
    val background by animateColorAsState(
        when {
            armed -> Palette.orange
            pressed -> Palette.bg3
            else -> Palette.bg2
        },
        Motion.effect(),
        label = "key",
    )
    val ink by animateColorAsState(if (armed) Palette.bg0 else Palette.fg, Motion.effect(), label = "key")
    Text(
        key.label,
        color = ink,
        fontFamily = Mono,
        fontSize = 13.sp,
        maxLines = 1,
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .pointerInput(key) {
                detectTapGestures(onPress = {
                    pressed = true
                    var repeated = false
                    val repeat = if (repeats) {
                        scope.launch {
                            delay(REPEAT_AFTER_MS)
                            fire(false)
                            repeated = true
                            while (isActive) {
                                delay(REPEAT_EVERY_MS)
                                fire(true)
                            }
                        }
                    } else {
                        null
                    }
                    val released = tryAwaitRelease() // false if the row scrolled instead
                    repeat?.cancel()
                    pressed = false
                    if (released && !repeated) fire(false)
                })
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

private enum class Action { Send, Enter, Stop }

/** Its color follows whether there's anything to send; the mark turns into the next one. */
@Composable
private fun SendButton(action: Action, connected: Boolean, onSend: (enter: Boolean) -> Unit, onEnter: () -> Unit, onStop: () -> Unit) {
    val haptics = haptics()
    val background by animateColorAsState(
        when {
            !connected -> Palette.bg2
            action == Action.Stop -> Palette.bg3
            else -> Palette.green
        },
        Motion.effect(),
        label = "send",
    )
    val ink by animateColorAsState(
        when {
            !connected -> Palette.dim
            action == Action.Stop -> Palette.fg
            else -> Palette.bg0
        },
        Motion.effect(),
        label = "send",
    )
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(background)
            .combinedClickable(
                enabled = connected,
                onLongClick = {
                    // Held: type it in without Enter, to finish with the app's own completion.
                    if (action == Action.Send) {
                        haptics.hold()
                        onSend(false)
                    }
                },
                onClick = {
                    haptics.confirm()
                    when (action) {
                        Action.Send -> onSend(true)
                        Action.Enter -> onEnter()
                        Action.Stop -> onStop()
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            action,
            transitionSpec = { (fadeIn(Motion.effect()) + scaleIn(Motion.effect(), 0.4f)) togetherWith (fadeOut(Motion.effect()) + scaleOut(Motion.effect(), 0.4f)) },
            label = "mark",
        ) { a -> Mark(a, { ink }) }
    }
}

@Composable
private fun Mark(action: Action, ink: () -> Color) {
    Canvas(Modifier.size(40.dp)) {
        val color = ink()
        val c = center
        val h = 7.dp.toPx()
        val w = 2.2.dp.toPx()
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) =
            drawLine(color, Offset(c.x + x0 * h, c.y + y0 * h), Offset(c.x + x1 * h, c.y + y1 * h), w, StrokeCap.Round)
        when (action) {
            Action.Send -> {
                line(0f, 1f, 0f, -1f)
                line(-0.8f, -0.2f, 0f, -1f)
                line(0.8f, -0.2f, 0f, -1f)
            }
            Action.Enter -> {
                line(0.8f, -0.9f, 0.8f, 0.25f)
                line(0.8f, 0.25f, -0.85f, 0.25f)
                line(-0.85f, 0.25f, -0.3f, -0.3f)
                line(-0.85f, 0.25f, -0.3f, 0.8f)
            }
            Action.Stop -> {
                val half = 5.5.dp.toPx()
                drawRoundRect(color, Offset(c.x - half, c.y - half), Size(half * 2, half * 2), CornerRadius(1.5.dp.toPx()))
            }
        }
    }
}

private const val REPEAT_AFTER_MS = 400L
private const val REPEAT_EVERY_MS = 50L
