package sh.ahmd.wallflower.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import sh.ahmd.wallflower.program.Asked
import sh.ahmd.wallflower.ui.Palette
import sh.ahmd.wallflower.ui.Type
import sh.ahmd.wallflower.ui.haptics

/** The program working on its turn, as it puts it ([doing]) if it says, and for how long; gone when it's done. */
@Composable
internal fun Working(since: Long, doing: String?) {
    val live = LocalLive.current
    val seconds by produceState(elapsed(since), since, live) {
        while (live) {
            value = elapsed(since)
            delay(1_000 - System.currentTimeMillis() % 1_000)
        }
    }
    val shine = sheenAt(live)
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Running(Modifier)
        Text(
            (doing?.lowercase() ?: "working") + (if (since > 0) " · " + duration(seconds) else ""),
            style = Type.label,
            modifier = Modifier.padding(start = 8.dp).sheen(shine, Palette.grey, Palette.purple),
        )
    }
}

private fun elapsed(since: Long) = if (since > 0) ((System.currentTimeMillis() - since) / 1_000).coerceAtLeast(0) else 0

private fun duration(seconds: Long) = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3_600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3_600}h ${seconds % 3_600 / 60}m"
}

/**
 * The program needs an answer that isn't on its screen as a menu wallflower
 * can read; or it [asked] questions that wait to be brought up, which a tap
 * on them does ([onBring]), and then they're a menu.
 */
@Composable
internal fun Waiting(asked: Asked?, onBring: () -> Unit) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Palette.orange))
        if (asked == null) {
            Text(
                "waiting for you · answer in the terminal",
                color = Palette.grey,
                style = Type.label,
                modifier = Modifier.padding(start = 8.dp),
            )
            return@Row
        }
        Text("${asked.what} for you", color = Palette.grey, style = Type.label, modifier = Modifier.padding(start = 8.dp))
        val haptics = haptics()
        Text(
            "answer",
            color = Palette.orange,
            style = Type.label,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .padding(start = 10.dp)
                .clip(CircleShape)
                .background(Palette.orange.copy(alpha = 0.14f))
                .clickable {
                    haptics.tick()
                    onBring()
                }
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}
