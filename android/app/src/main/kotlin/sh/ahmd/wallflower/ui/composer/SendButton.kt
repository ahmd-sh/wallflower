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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.ahmd.wallflower.ui.Mono
import sh.ahmd.wallflower.ui.Motion
import sh.ahmd.wallflower.ui.Palette
import sh.ahmd.wallflower.ui.glyphs
import sh.ahmd.wallflower.ui.haptics

/**
 * What the send button does: [Send] the box, or [Queue] it for a program
 * at work to take later, or [Hold] it for an answer the program waits on
 * first; [Run] what's typed straight into the pane; [Enter] the app's own
 * line; [Stop] the app at work; or nothing, [Idle], with nothing to send
 * and nothing running.
 */
internal enum class Action { Send, Queue, Hold, Run, Enter, Stop, Idle }

/** The shape an action shows. */
private enum class Mark { Arrow, Queue, Enter, Stop }

private val Action.mark get() = when (this) {
    Action.Send, Action.Hold, Action.Run, Action.Idle -> Mark.Arrow
    Action.Queue -> Mark.Queue
    Action.Enter -> Mark.Enter
    Action.Stop -> Mark.Stop
}

/**
 * Its color says whether a tap sends (green), stops or waits (grey), or does
 * nothing; its mark (or the [label] of the keys it presses, a [faceOf])
 * turns into the next one with a little give.
 */
@Composable
internal fun SendButton(action: Action, connected: Boolean, label: AnnotatedString?, onSend: (enter: Boolean) -> Unit, onEnter: () -> Unit, onStop: () -> Unit) {
    val haptics = haptics()
    val live = connected && action != Action.Idle
    val background by animateColorAsState(
        when {
            !live -> Palette.bg2
            action == Action.Stop || action == Action.Hold -> Palette.bg3
            else -> Palette.green
        },
        Motion.effect(),
        label = "send",
    )
    val ink by animateColorAsState(
        when {
            !live -> Palette.dim
            action == Action.Hold -> Palette.grey
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
                enabled = live,
                hapticFeedbackEnabled = false, // its own feel, below, which heeds the setting
                onLongClick = {
                    // Held: type it in without Enter, to finish with the app's own completion.
                    if (action == Action.Send || action == Action.Queue) {
                        haptics.hold()
                        onSend(false)
                    }
                },
                onClick = {
                    if (action != Action.Hold) haptics.confirm() // held, it's felt as turned back
                    when (action) {
                        Action.Send, Action.Queue, Action.Hold -> onSend(true)
                        Action.Run, Action.Enter -> onEnter()
                        Action.Stop -> onStop()
                        Action.Idle -> Unit
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            action.mark to label,
            transitionSpec = { (fadeIn(Motion.effect()) + scaleIn(Motion.bounce(), 0.5f)) togetherWith (fadeOut(Motion.effect()) + scaleOut(Motion.effect(), 0.5f)) },
            label = "mark",
        ) { (m, l) ->
            if (l == null) {
                Mark(m, { ink })
            } else {
                Text(l, color = ink, fontFamily = Mono, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, inlineContent = glyphs(ink))
            }
        }
    }
}

@Composable
private fun Mark(mark: Mark, ink: () -> Color) {
    Canvas(Modifier.size(40.dp)) {
        val color = ink()
        val c = center
        val h = 7.dp.toPx()
        val w = 2.2.dp.toPx()
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) =
            drawLine(color, Offset(c.x + x0 * h, c.y + y0 * h), Offset(c.x + x1 * h, c.y + y1 * h), w, StrokeCap.Round)
        when (mark) {
            Mark.Arrow -> {
                line(0f, 1f, 0f, -1f)
                line(-0.8f, -0.2f, 0f, -1f)
                line(0.8f, -0.2f, 0f, -1f)
            }
            Mark.Queue -> {
                // Up onto what's waiting.
                line(0f, 0.35f, 0f, -1.1f)
                line(-0.7f, -0.4f, 0f, -1.1f)
                line(0.7f, -0.4f, 0f, -1.1f)
                line(-0.9f, 1f, 0.9f, 1f)
            }
            Mark.Enter -> {
                line(0.8f, -0.9f, 0.8f, 0.25f)
                line(0.8f, 0.25f, -0.85f, 0.25f)
                line(-0.85f, 0.25f, -0.3f, -0.3f)
                line(-0.85f, 0.25f, -0.3f, 0.8f)
            }
            Mark.Stop -> {
                val half = 5.5.dp.toPx()
                drawRoundRect(color, Offset(c.x - half, c.y - half), Size(half * 2, half * 2), CornerRadius(1.5.dp.toPx()))
            }
        }
    }
}
