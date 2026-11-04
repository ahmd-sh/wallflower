package sh.ahmd.wallflower.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.ahmd.wallflower.protocol.SessionInfo
import sh.ahmd.wallflower.session.Place
import sh.ahmd.wallflower.session.Session

/** A session in a multiplexer, as the page lists it. */
private data class Listed(val mux: String, val info: SessionInfo)

/**
 * The Mac's sessions, in every multiplexer it has (the phone's first), its own marked.
 * A tap moves there over the same connection; holding one offers rename and
 * close (not the phone's own: move away first). New starts one in a
 * multiplexer and moves there.
 */
@Composable
internal fun SessionsScreen(session: Session, onBack: () -> Unit) {
    val list by session.sessions.collectAsStateWithLifecycle()
    val place by session.place.collectAsStateWithLifecycle()
    LaunchedEffect(session) { session.listSessions() }
    var starting by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<Listed?>(null) }
    var closing by remember { mutableStateOf<Listed?>(null) }
    starting?.let { mux ->
        RenameDialog(
            "",
            title = "new $mux session",
            action = "start",
            blank = true,
            onRename = {
                session.newSession(mux, it)
                starting = null
                onBack()
            },
            onDismiss = { starting = null },
        )
    }
    renaming?.let { s ->
        RenameDialog(
            s.info.name,
            title = "rename session",
            onRename = {
                session.renameSession(s.mux, s.info.id, it)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    closing?.let { s ->
        Confirm(
            title = "close ${s.info.name}?",
            body = if (s.info.windows == 1) {
                "Its tab closes, and the programs running in it end."
            } else {
                "Its ${s.info.windows} tabs close, and the programs running in them end."
            },
            action = "close",
            onConfirm = {
                session.killSession(s.mux, s.info.id)
                closing = null
            },
            onDismiss = { closing = null },
        )
    }
    Page("sessions", onBack) {
        val muxes = list?.muxes
        if (muxes == null) {
            Body("asking the Mac…")
            return@Page
        }
        for (m in muxes.sortedByDescending { it.name == place?.mux }) {
            Section(m.name)
            m.error?.let { Body(it) }
            for (s in m.sessions) {
                val here = place == Place(m.name, s.id)
                SessionRow(
                    s,
                    here,
                    onClick = {
                        if (!here) session.switchTo(m.name, s.id)
                        onBack()
                    },
                    onRename = { renaming = Listed(m.name, s) },
                    onClose = if (here) null else ({ closing = Listed(m.name, s) }),
                )
            }
            if (m.error == null) Button("new session") { starting = m.name }
        }
    }
}

@Composable
private fun SessionRow(info: SessionInfo, here: Boolean, onClick: () -> Unit, onRename: () -> Unit, onClose: (() -> Unit)?) {
    val haptics = haptics()
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.bg1)
                .combinedClickable(
                    hapticFeedbackEnabled = false,
                    onClick = {
                        haptics.tick()
                        onClick()
                    },
                    onLongClick = { menu = true },
                )
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                info.name,
                color = Palette.fg,
                fontFamily = words,
                fontSize = wordSize(13.sp, 16.sp),
                fontWeight = if (grotesque) FontWeight.SemiBold else FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
            if (here) Box(Modifier.padding(start = 8.dp).size(6.dp).clip(CircleShape).background(Palette.green))
            Text(tabs(info.windows), color = Palette.grey, style = Type.label, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 12.dp))
            Text(if (here) "here" else "›", color = Palette.grey, fontFamily = Mono, fontSize = if (here) 12.sp else 14.sp)
        }
        PopupMenu(
            expanded = menu,
            onDismiss = { menu = false },
            actions = listOfNotNull(
                MenuAction("rename") { menu = false; onRename() },
                onClose?.let { MenuAction("close", Palette.red) { menu = false; it() } },
            ),
        )
    }
}

private fun tabs(n: Int) = if (n == 1) "1 tab" else "$n tabs"
