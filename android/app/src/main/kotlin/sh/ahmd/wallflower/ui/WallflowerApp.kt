package sh.ahmd.wallflower.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.ahmd.wallflower.protocol.Keys
import sh.ahmd.wallflower.protocol.Select
import sh.ahmd.wallflower.protocol.Window
import sh.ahmd.wallflower.session.Session
import sh.ahmd.wallflower.session.Status
import sh.ahmd.wallflower.terminal.TerminalView

@Composable
fun WallflowerApp(session: Session) {
    val status by session.status.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val terminals by session.terminals.collectAsStateWithLifecycle()
    val pane = state?.let { Session.activePane(it) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.bg0)
            .systemBarsPadding()
            .imePadding(),
    ) {
        WindowTabs(state?.windows.orEmpty(), status) { session.send(Select(window = it.id)) }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            var view by remember { mutableStateOf<TerminalView?>(null) }
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp).clipToBounds(),
                factory = { ctx ->
                    TerminalView(ctx).apply {
                        onGridSize = { cols, rows -> session.start(cols, rows); session.resize(cols, rows) }
                        view = this
                    }
                },
                update = { it.terminal = pane?.let { p -> terminals[p.id] } },
            )
            DisposableEffect(view, pane?.id) {
                val v = view
                val id = pane?.id
                session.onOutput = { if (it == id) v?.requestFrame() }
                onDispose { session.onOutput = null }
            }
        }

        if (pane != null) {
            KeyRow { keys -> session.send(Keys(pane.id, keys)) }
            Composer { text -> session.type(pane.id, text) }
        }
    }
}

@Composable
private fun WindowTabs(windows: List<Window>, status: Status, onSelect: (Window) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(status)
        for (w in windows) {
            val agent = w.panes.map { it.agent }.firstOrNull { it.isNotEmpty() }
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (w.active) Palette.bg3 else Palette.bg1)
                    .clickable { onSelect(w) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${w.index}",
                    color = if (w.active) Palette.orange else Palette.dim,
                    fontFamily = Mono,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    " ${w.name}",
                    color = if (w.active) Palette.fg else Palette.grey,
                    fontFamily = Mono,
                    fontSize = 12.sp,
                )
                if (agent != null) {
                    Box(Modifier.padding(start = 6.dp).size(6.dp).clip(CircleShape).background(agentColor(agent)))
                }
            }
        }
    }
}

@Composable
private fun StatusDot(status: Status) {
    val color = when (status) {
        is Status.Connected -> Palette.green
        Status.Connecting -> Palette.yellow
        is Status.Disconnected -> Palette.red
    }
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
    if (status is Status.Disconnected) {
        Text(status.reason, color = Palette.red, fontFamily = Mono, fontSize = 11.sp)
    }
}

private fun agentColor(state: String): Color = when (state) {
    "waiting" -> Palette.orange
    "working" -> Palette.blue
    "done" -> Palette.green
    "error" -> Palette.red
    else -> Palette.grey
}

private val keys = listOf(
    "esc" to listOf("Escape"),
    "tab" to listOf("Tab"),
    "⇧tab" to listOf("BTab"),
    "^c" to listOf("C-c"),
    "↑" to listOf("Up"),
    "↓" to listOf("Down"),
    "←" to listOf("Left"),
    "→" to listOf("Right"),
    "⏎" to listOf("Enter"),
)

@Composable
private fun KeyRow(onKeys: (List<String>) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((label, k) in keys) {
            Text(
                label,
                color = Palette.fg,
                fontFamily = Mono,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Palette.bg2)
                    .clickable { onKeys(k) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/**
 * A plain text field, so the keyboard's autocorrect, swipe and voice input
 * all work. Send types the text and presses Enter.
 */
@Composable
private fun Composer(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val send = {
        onSend(text + "\r")
        text = ""
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(color = Palette.fg, fontFamily = Mono, fontSize = 15.sp),
            cursorBrush = SolidColor(Palette.orange),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = true,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { send() }),
            maxLines = 6,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.bg1)
                .heightIn(min = 44.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
        Text(
            "⏎",
            color = Palette.bg0,
            fontFamily = Mono,
            fontSize = 18.sp,
            modifier = Modifier
                .padding(start = 8.dp)
                .clip(CircleShape)
                .background(Palette.green)
                .clickable { send() }
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}
