package sh.ahmd.wallflower.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animate
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import android.net.Uri
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.ahmd.wallflower.Uploads
import sh.ahmd.wallflower.session.Session
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
 * typing; a swipe right brings either into the box to edit (a tap is just
 * the box's, to type something else). What's in the box
 * replaces the line when sent, so the box is exactly what runs. The button
 * sends (held: types it in without Enter), enters the app's line when only
 * that has text, or stops.
 *
 * + sends photos or files to the Mac ([onAttach]); each shows above the box
 * on its way, then its path goes in at the cursor, shown as its name. What
 * the draft holds shows above it too, photos by their [thumbnail], each
 * with × to take it out.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class) // edit changes for sticky keys; whether the keyboard is up
@Composable
fun Composer(
    state: TextFieldState,
    program: String,
    keys: List<BarKey>,
    connected: Boolean,
    line: InputLine?,
    uploads: List<Uploads.Item>,
    uploadDir: String?,
    thumbnail: (String) -> File?,
    onAttach: (List<Uri>) -> Unit,
    onDismissUpload: (String) -> Unit,
    onClear: (() -> Unit)?,
    onKey: (BarKey) -> Unit,
    onSend: (text: String, enter: Boolean) -> Boolean,
    onEnter: () -> Unit,
    onStop: () -> Unit,
    onEditKeys: () -> Unit,
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
    val paths = remember(uploadDir) { uploadDir?.let(Session::uploadPaths) }
    val named = remember(paths) { paths?.let(::UploadNames) }
    val whole = remember(paths) { paths?.let(::WholeUploads) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp, top = 4.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Palette.bg1)
            .animateContentSize(Motion.spatial())
            .padding(top = 14.dp, bottom = 8.dp),
    ) {
        val text = state.text.toString()
        val attached = remember(text, paths) { paths?.findAll(text)?.map { it.value }?.distinct()?.toList().orEmpty() }
        if (attached.isNotEmpty() || uploads.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (path in attached) {
                    key(path) {
                        Attached(path, remember(path) { thumbnail(path) }) {
                            haptics.tick()
                            state.edit {
                                val at = asCharSequence().indexOf(path)
                                if (at >= 0) cutUpload(at, at + path.length)
                            }
                        }
                    }
                }
                for (u in uploads) key(u.id) { UploadChip(u) { onDismissUpload(u.id) } }
            }
            uploads.firstOrNull { it.error != null }?.let { u ->
                Text(
                    "${u.name}: ${u.error}",
                    color = Palette.red,
                    fontFamily = words,
                    fontSize = wordSize(11.sp, 12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().animateContentSize(Motion.spatial()).padding(horizontal = 18.dp)) {
            BasicTextField(
                state = state,
                inputTransformation = intercept ?: whole,
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
                        if (state.text.isEmpty() && (armed != null || ghost.isEmpty())) {
                            val hint = armed?.let { "${it.label} + key" } ?: "send to ${program.ifEmpty { "pane" }}"
                            Text(hint, color = if (armed != null) Palette.orange else Palette.dim, fontFamily = Mono, fontSize = 15.sp)
                        }
                        field()
                    }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            if (state.text.isEmpty() && armed == null && ghost.isNotEmpty()) {
                // The app's line or its suggestion, over the empty box. A tap is the box's, to type afresh;
                // swiped right it fills with colour as it goes, and once full (a tick) it comes in to edit.
                val suggested = pending.isEmpty()
                val clearable = !suggested && onClear != null
                val reach = with(LocalDensity.current) { TAKE_REACH.toPx() }
                var pulled by remember { mutableFloatStateOf(0f) }
                var past by remember { mutableStateOf(false) }
                val base = if (suggested) Palette.dim else Palette.grey
                val fill = (pulled / reach).coerceIn(0f, 1f)
                val filled = Brush.horizontalGradient(
                    0f to Palette.fg,
                    (fill - FILL_EDGE).coerceAtLeast(0f) to Palette.fg,
                    (fill + FILL_EDGE).coerceAtMost(1f) to base,
                    1f to base,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(end = if (clearable) 30.dp else 0.dp)
                        .pointerInput(Unit) {
                            detectTapGestures {
                                focus.requestFocus()
                                keyboard?.show()
                            }
                        }
                        .draggable(
                            rememberDraggableState { dx ->
                                pulled = (pulled + dx).coerceIn(0f, reach * 1.5f)
                                if ((pulled >= reach) != past) {
                                    past = !past
                                    haptics.threshold(past)
                                }
                            },
                            Orientation.Horizontal,
                            onDragStopped = {
                                if (past) {
                                    state.edit {
                                        replace(0, length, ghost)
                                        placeCursorAtEnd()
                                    }
                                    focus.requestFocus()
                                    keyboard?.show()
                                }
                                past = false
                                animate(pulled, 0f, animationSpec = Motion.spatial()) { v, _ -> pulled = v }
                            },
                        ),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (suggested) GlyphIcon(Glyph.Spark, if (fill > 0f) Palette.fg else base, Modifier.padding(top = 5.dp, end = 8.dp), size = 11.dp)
                    Text(
                        ghost,
                        style = TextStyle(brush = if (fill > 0f) filled else SolidColor(base), fontFamily = Mono, fontSize = 15.sp, lineHeight = 21.sp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
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
            Attach(enabled = connected, typing = { imeVisible }, onAttach = onAttach, onBack = { refocus = true })
            KeyRow(keys, armed, press, onEditKeys, Modifier.weight(1f))
            SendButton(
                action = when {
                    state.text.isNotEmpty() -> Action.Send
                    pending.isNotEmpty() -> Action.Enter
                    else -> Action.Stop
                },
                connected = connected,
                onSend = { enter -> if (onSend(state.text.toString(), enter)) state.clearText() },
                onEnter = onEnter,
                onStop = onStop,
            )
        }
    }
}

/** + : photos, through the system's picker, or any file. The keyboard stays up for it, and comes back after. */
@Composable
private fun Attach(enabled: Boolean, typing: () -> Boolean, onAttach: (List<Uri>) -> Unit, onBack: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var wasTyping by remember { mutableStateOf(false) }
    val picked: (List<Uri>) -> Unit = {
        if (it.isNotEmpty()) onAttach(it)
        if (wasTyping) onBack()
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACH), picked)
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), picked)
    Box(Modifier.padding(end = 6.dp)) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Palette.bg2)
                .border(1.dp, Palette.border, CircleShape)
                .clickable(enabled = enabled) { // the menu's opening is felt
                    wasTyping = typing()
                    open = true
                },
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(Glyph.Plus, if (enabled) Palette.fg else Palette.dim, size = 14.dp) }
        PopupMenu(
            expanded = open,
            onDismiss = { open = false },
            keepKeyboard = true,
            actions = listOf(
                MenuAction("photos") {
                    open = false
                    photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                MenuAction("files") {
                    open = false
                    files.launch(arrayOf("*/*"))
                },
            ),
        )
    }
}

/** A file in the draft: a photo as itself, anything else by its name; × takes it out. */
@Composable
private fun Attached(path: String, thumb: File?, onRemove: () -> Unit) {
    val image by produceState<ImageBitmap?>(null, thumb) {
        value = thumb?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
    }
    Tile(image, path.substringAfterLast('/')) { Remove(Modifier.align(Alignment.TopEnd), onRemove) }
}

/** A file on its way, and how far along; failed, tap to let it go (why is said beneath). */
@Composable
private fun UploadChip(u: Uploads.Item, onDismiss: () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val thumb by produceState<ImageBitmap?>(null, u.uri) {
        value = withContext(Dispatchers.IO) { runCatching { resolver.loadThumbnail(u.uri, android.util.Size(192, 192), null).asImageBitmap() }.getOrNull() }
    }
    Tile(thumb, u.name, Modifier.clickable(enabled = u.error != null, onClick = onDismiss)) {
        Box(Modifier.matchParentSize().background(if (u.error != null) Palette.red.copy(alpha = 0.3f) else Palette.bg0.copy(alpha = 0.55f)))
        if (u.error != null) {
            Remove(Modifier.align(Alignment.TopEnd), onDismiss)
        } else {
            CircularProgressIndicator(
                progress = { if (u.size > 0) u.sent.toFloat() / u.size else 0f },
                modifier = Modifier.align(Alignment.Center).size(22.dp),
                color = Palette.fg,
                trackColor = Palette.bg3,
                strokeWidth = 2.5.dp,
            )
        }
    }
}

/** A square for a file: its picture, or a paperclip and its name. */
@Composable
private fun Tile(image: ImageBitmap?, name: String, modifier: Modifier = Modifier, over: @Composable BoxScope.() -> Unit) {
    Box(modifier.size(TILE).clip(RoundedCornerShape(12.dp)).background(Palette.bg2)) {
        if (image != null) {
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(CLIP, color = Palette.grey, fontFamily = Mono, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center).padding(bottom = 10.dp))
            Text(
                name,
                color = Palette.fg,
                fontFamily = Mono,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 5.dp, end = 5.dp, bottom = 6.dp),
            )
        }
        over()
    }
}

@Composable
private fun Remove(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .padding(4.dp)
            .size(20.dp)
            .clip(CircleShape)
            .background(Palette.bg0.copy(alpha = 0.8f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(Glyph.Close, Palette.fg, size = 9.dp) }
}

/** An upload's path in the box shows as its name, marked as a file. */
private class UploadNames(private val paths: Regex) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        val found = paths.findAll(asCharSequence().toString()).toList()
        var shift = 0
        val names = ArrayList<IntRange>()
        for (m in found) {
            val name = "$CLIP " + m.value.substringAfterLast('/')
            val start = m.range.first + shift
            replace(start, m.range.last + 1 + shift, name)
            names += start until start + name.length
            shift += name.length - m.value.length
        }
        for (r in names) addStyle(SpanStyle(color = Palette.aqua, background = Palette.bg2), r.first, r.last + 1)
    }
}

/** Deleting into an upload's path deletes all of it, as one thing: it shows as one. */
@OptIn(ExperimentalFoundationApi::class)
private class WholeUploads(private val paths: Regex) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount != 1) return
        val now = changes.getRange(0)
        val was = changes.getOriginalRange(0)
        if (!now.collapsed || was.collapsed) return // only a deletion
        val hit = paths.findAll(originalText.toString()).firstOrNull { it.range.first < was.max && was.min <= it.range.last } ?: return
        revertAllChanges()
        cutUpload(hit.range.first, hit.range.last + 1)
    }
}

/** Takes an upload's path out, with the space after it, or its line if it had one to itself. */
private fun TextFieldBuffer.cutUpload(start: Int, end: Int) {
    val s = asCharSequence()
    val alone = start == 0 || s[start - 1] == '\n'
    val until = when {
        end < length && s[end] == ' ' -> end + 1
        end < length && s[end] == '\n' && alone -> end + 1
        else -> end
    }
    replace(start, until, "")
    selection = TextRange(start)
}

private const val CLIP = "\uF0C6" // the font's paperclip
private const val MAX_ATTACH = 10
private val TILE = 56.dp
private val TAKE_REACH = 72.dp // how far a swipe takes the app's line or suggestion
private const val FILL_EDGE = 0.04f // the softness of the fill's edge, of the text's width

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
                hapticFeedbackEnabled = false, // its own feel, below, which heeds the setting
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
