package sh.ahmd.wallflower.ui.composer

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import sh.ahmd.wallflower.BarKey
import sh.ahmd.wallflower.KeyBar
import sh.ahmd.wallflower.ui.Mono
import sh.ahmd.wallflower.ui.Motion
import sh.ahmd.wallflower.ui.Palette
import sh.ahmd.wallflower.ui.haptics

/** The key bar: its keys, scrolling sideways, then ✎ to edit them. */
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

private const val REPEAT_AFTER_MS = 400L
private const val REPEAT_EVERY_MS = 50L
