package sh.ahmd.wallflower.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.ceil
import kotlin.math.floor
import sh.ahmd.wallflower.protocol.Window
import sh.ahmd.wallflower.read.Activity
import sh.ahmd.wallflower.read.activityOf
import sh.ahmd.wallflower.session.Status
import sh.ahmd.wallflower.session.label

/**
 * The tabs (the session's windows), with new tab first and the connection last. It's
 * local: it scrolls natively and a tap switches immediately, the Mac learns
 * afterwards. The highlight glides to the tab chosen. Holding a tab offers
 * rename and close. The connection's dot opens its menu, which says which Mac
 * ([machine], at [address]) and [session] this is, and goes to the others.
 */
@Composable
internal fun TabBar(
    windows: List<Window>,
    selected: String?,
    status: Status,
    machine: String,
    address: String,
    session: String?,
    onSelect: (Window) -> Unit,
    onNew: () -> Unit,
    onRename: (Window) -> Unit,
    onClose: (Window) -> Unit,
    onRetry: () -> Unit,
    onSessions: () -> Unit,
    onSettings: () -> Unit,
    onExit: () -> Unit,
) {
    val haptics = haptics()
    val list = rememberLazyListState()
    val index = windows.indexOfFirst { it.id == selected }
    LaunchedEffect(index) {
        if (index >= 0) {
            val visible = list.layoutInfo.visibleItemsInfo
            if (visible.none { it.index == index && it.offset >= 0 && it.offset + it.size <= list.layoutInfo.viewportEndOffset }) {
                list.animateScrollToItem(index, scrollOffset = -48)
            }
        }
    }
    val at = remember { Animatable(index.coerceAtLeast(0).toFloat()) }
    LaunchedEffect(index) { if (index >= 0) at.animateTo(index.toFloat(), Motion.spatial()) }
    var menu by remember { mutableStateOf<String?>(null) }
    val colors = Palette
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val line = 1.dp.toPx()
                drawRect(colors.border, Offset(0f, size.height - line), Size(size.width, line))
            }
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(Glyph.Plus, Palette.fg, Modifier.padding(start = 6.dp), onClick = onNew)
        LazyRow(
            state = list,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .drawBehind {
                    // The tabs' backgrounds, where the list put them, and the highlight between them.
                    val info = list.layoutInfo
                    val start = -info.viewportStartOffset.toFloat()
                    val radius = CornerRadius(8.dp.toPx())
                    fun span(i: Int) = info.visibleItemsInfo.firstOrNull { it.index == i }?.let { (start + it.offset) to it.size.toFloat() }
                    for (item in info.visibleItemsInfo) {
                        drawRoundRect(colors.bg1, Offset(start + item.offset, 0f), Size(item.size.toFloat(), size.height), radius)
                    }
                    if (index < 0) return@drawBehind
                    val v = at.value
                    val a = span(floor(v).toInt())
                    val b = span(ceil(v).toInt())
                    val (x, w) = if (a != null && b != null) {
                        val f = v - floor(v)
                        lerp(a.first, b.first, f) to lerp(a.second, b.second, f)
                    } else {
                        a ?: b ?: return@drawBehind
                    }
                    drawRoundRect(colors.bg3, Offset(x, 0f), Size(w, size.height), radius)
                },
            contentPadding = PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(windows, key = { it.id }) { w ->
                val on = w.id == selected
                val number by animateColorAsState(if (on) Palette.orange else Palette.dim, Motion.effect(), label = "tab")
                val name by animateColorAsState(if (on) Palette.fg else Palette.grey, Motion.effect(), label = "tab")
                val agent = activityOf(w)
                Box {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .combinedClickable(
                                hapticFeedbackEnabled = false, // the menu's opening is felt, as the setting allows
                                onClick = {
                                    if (!on) haptics.tick()
                                    onSelect(w)
                                },
                                onLongClick = { menu = w.id },
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${w.index}", color = number, style = Type.label, fontWeight = FontWeight.Bold)
                        Text(" ${w.label()}", color = name, style = Type.label, maxLines = 1)
                        if (agent != null) AgentDot(agent, Modifier.padding(start = 6.dp))
                    }
                    PopupMenu(
                        expanded = menu == w.id,
                        onDismiss = { menu = null },
                        actions = listOf(
                            MenuAction("rename") { menu = null; onRename(w) },
                            MenuAction("close", Palette.red) { menu = null; onClose(w) },
                        ),
                    )
                }
            }
        }
        ConnectionButton(status, machine, address, session, onRetry, onSessions, onSettings, onExit)
    }
}

/** What a program is doing, as a coloured dot, which breathes while it works. */
@Composable
internal fun AgentDot(activity: Activity, modifier: Modifier = Modifier) {
    val color by animateColorAsState(if (activity == Activity.Waiting) Palette.orange else Palette.purple, Motion.effect(), label = "agent")
    val breath = if (activity == Activity.Working) {
        rememberInfiniteTransition(label = "agent").animateFloat(1f, 0.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "breath")
    } else {
        null
    }
    Box(modifier.size(6.dp).graphicsLayer { alpha = breath?.value ?: 1f }.clip(CircleShape).background(color))
}

@Composable
private fun BarButton(glyph: Glyph, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(glyph, color, size = 14.dp) }
}

/** What a window's programs are doing that needs an eye: one waiting for the user before any working. */
private fun activityOf(w: Window): Activity? {
    val doing = w.panes.mapNotNull { activityOf(it) }
    return doing.firstOrNull { it == Activity.Waiting } ?: doing.firstOrNull { it == Activity.Working }
}
