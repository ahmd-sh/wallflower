package sh.ahmd.wallflower.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import sh.ahmd.wallflower.AppModel
import sh.ahmd.wallflower.protocol.MuxSessions
import sh.ahmd.wallflower.protocol.SessionInfo
import sh.ahmd.wallflower.session.Blocked
import sh.ahmd.wallflower.session.Host
import sh.ahmd.wallflower.session.Session
import sh.ahmd.wallflower.session.Status
import sh.ahmd.wallflower.session.called

/** A session in a multiplexer, as the page lists it. */
private data class Listed(val mux: String, val info: SessionInfo)

/**
 * The Mac, while the phone's in none of its sessions: how the connection
 * is, and the sessions in every multiplexer it has, each saying whether it
 * runs and what can be done (the multiplexer it was last in first). A tap
 * goes into one; holding one offers rename and close; new starts one, and
 * the multiplexer with it where it can. The list keeps up with the Mac on
 * its own. While the connection's down, it says how it's coming back, and
 * when it's waiting for the user, what to do.
 */
@Composable
internal fun HomeScreen(
    session: Session,
    host: Host,
    model: AppModel,
    unlock: (onDone: (Boolean) -> Unit) -> Unit,
    onSettings: () -> Unit,
    onExit: () -> Unit,
) {
    val status by session.status.collectAsStateWithLifecycle()
    val list by session.sessions.collectAsStateWithLifecycle()
    val starting by session.starting.collectAsStateWithLifecycle()
    val left by session.left.collectAsStateWithLifecycle()
    LaunchedEffect(session) { session.start() }
    val machine = host.called
    val address = if (host.dev) "development transport" else "${host.user}@${host.address}:${host.port}"

    var naming by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<Listed?>(null) }
    var closing by remember { mutableStateOf<Listed?>(null) }
    naming?.let { mux ->
        RenameDialog(
            "",
            title = "new $mux session",
            action = "start",
            blank = true,
            onRename = {
                session.newSession(mux, it)
                naming = null
            },
            onDismiss = { naming = null },
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

    val blocked = (status as? Status.NeedsUser)?.blocked
    LaunchedEffect(blocked) {
        if (blocked is Blocked.KeyLocked) unlock { ok -> if (ok) session.retry() }
    }
    // Down a moment, as across a network change, it isn't worth saying.
    val down = status == Status.Connecting || status is Status.Disconnected
    var saysDown by remember { mutableStateOf(false) }
    LaunchedEffect(down) {
        if (down) delay(DOWN_UNSAID_MS)
        saysDown = down
    }

    Column(Modifier.fillMaxSize().background(Palette.bg0).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(machine, color = Palette.fg, fontFamily = words, fontSize = wordSize(16.sp, 22.sp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(statusLine(status, null), color = statusColor(status), style = Type.label)
            }
            ConnectionButton(status, machine, address, null, session::retry, null, onSettings, onExit)
        }
        val lastBlocked = rememberLast(blocked)
        AnimatedVisibility(blocked != null, enter = fadeIn(Motion.effect()) + expandVertically(Motion.spatial()), exit = fadeOut(Motion.effect()) + shrinkVertically(Motion.spatial())) {
            lastBlocked?.let {
                BlockedCard(
                    blocked = it,
                    machine = machine,
                    unlockSeconds = model.unlockSeconds.value,
                    authorizeCommand = model::authorizeCommand,
                    onUnlock = { unlock { ok -> if (ok) session.retry() } },
                    onTrust = { k -> model.hostKeys.trust(k); session.retry() },
                    onForget = { k -> model.hostKeys.forget(k.host); session.retry() },
                    onRetry = session::retry,
                    onSetup = onSettings,
                )
            }
        }
        val muxes = list?.muxes
        if (muxes == null) {
            Box(Modifier.weight(1f)) {
                if (status is Status.Connected) {
                    Text("asking $machine…", color = Palette.grey, style = Type.body, modifier = Modifier.align(Alignment.Center))
                } else {
                    Connecting(status, machine, address, session::retry)
                }
            }
            return@Column
        }
        val lastDown = rememberLast(status.takeIf { down })
        AnimatedVisibility(saysDown, enter = fadeIn(Motion.effect()) + expandVertically(Motion.spatial()), exit = fadeOut(Motion.effect()) + shrinkVertically(Motion.spatial())) {
            lastDown?.let { Reconnecting(it, machine, session::retry) }
        }
        val shown by animateFloatAsState(if (saysDown || blocked != null) 0.45f else 1f, Motion.effect(), label = "down")
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .graphicsLayer { alpha = shown }
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            left?.let { Text(it, color = Palette.grey, style = Type.caption) }
            for (m in muxes.sortedByDescending { it.name == host.mux }) {
                MuxSection(
                    m,
                    machine,
                    starting = starting == m.name,
                    onOpen = { session.switchTo(m.name, it.id) },
                    onNew = { naming = m.name },
                    onRename = { renaming = Listed(m.name, it) },
                    onClose = { closing = Listed(m.name, it) },
                )
            }
        }
    }
}

/**
 * A multiplexer's sessions: whether it runs, and what can be done, as it
 * says. The app knows none of them by name: what one can do is its own.
 */
@Composable
private fun MuxSection(
    m: MuxSessions,
    machine: String,
    starting: Boolean,
    onOpen: (SessionInfo) -> Unit,
    onNew: () -> Unit,
    onRename: (SessionInfo) -> Unit,
    onClose: (SessionInfo) -> Unit,
) {
    val error = m.error
    val can = { what: String -> what in m.can && error == null }
    Section(m.name)
    when {
        error != null -> Body(error)
        !m.running && can(MuxSessions.CAN_NEW) -> Body("not running: a new session starts it")
        !m.running -> Body("not running on $machine")
        m.sessions.isEmpty() -> Body("no sessions")
    }
    for (s in m.sessions) {
        SessionRow(
            s,
            onClick = { onOpen(s) },
            onRename = if (can(MuxSessions.CAN_RENAME)) ({ onRename(s) }) else null,
            onClose = if (can(MuxSessions.CAN_KILL)) ({ onClose(s) }) else null,
        )
    }
    if (can(MuxSessions.CAN_NEW)) Button(if (starting) "starting…" else "new session") { if (!starting) onNew() }
}

@Composable
private fun SessionRow(info: SessionInfo, onClick: () -> Unit, onRename: (() -> Unit)?, onClose: (() -> Unit)?) {
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
                    onLongClick = if (onRename == null && onClose == null) null else ({ menu = true }),
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
            Text(tabs(info.windows), color = Palette.grey, style = Type.label, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 12.dp))
            Text("›", color = Palette.grey, fontFamily = Mono, fontSize = 14.sp)
        }
        PopupMenu(
            expanded = menu,
            onDismiss = { menu = false },
            actions = listOfNotNull(
                onRename?.let { MenuAction("rename") { menu = false; it() } },
                onClose?.let { MenuAction("close", Palette.red) { menu = false; it() } },
            ),
        )
    }
}

private fun tabs(n: Int) = if (n == 1) "1 tab" else "$n tabs"
