package sh.ahmd.wallflower.ui.composer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.ahmd.wallflower.ui.Mono
import sh.ahmd.wallflower.ui.Motion
import sh.ahmd.wallflower.ui.Palette
import sh.ahmd.wallflower.ui.haptics

/** What the send button does: send the box, run what's typed straight into the pane, enter the app's own line, or stop the app. */
internal enum class Action { Send, Run, Enter, Stop }

/** Its color follows whether there's anything to send; the mark (or the [label] of the keys it presses) turns into the next one. */
@Composable
internal fun SendButton(action: Action, connected: Boolean, label: String?, onSend: (enter: Boolean) -> Unit, onEnter: () -> Unit, onStop: () -> Unit) {
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
                        Action.Run, Action.Enter -> onEnter()
                        Action.Stop -> onStop()
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            action to label,
            transitionSpec = { (fadeIn(Motion.effect()) + scaleIn(Motion.effect(), 0.4f)) togetherWith (fadeOut(Motion.effect()) + scaleOut(Motion.effect(), 0.4f)) },
            label = "mark",
        ) { (a, l) ->
            if (l == null) {
                Mark(a, { ink })
            } else {
                Text(l, color = ink, fontFamily = Mono, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
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
            Action.Send, Action.Run -> {
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
