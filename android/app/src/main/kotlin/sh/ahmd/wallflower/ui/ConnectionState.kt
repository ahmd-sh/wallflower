package sh.ahmd.wallflower.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import sh.ahmd.wallflower.AppModel
import sh.ahmd.wallflower.R
import sh.ahmd.wallflower.session.Blocked
import sh.ahmd.wallflower.session.HardwareKey
import sh.ahmd.wallflower.session.Session
import sh.ahmd.wallflower.session.Status

// The connection, as the session's screen shows it while it isn't up.

/** How the connection is, as a color: green connected, yellow on its way, orange waiting for the user. */
@Composable
@ReadOnlyComposable
internal fun statusColor(status: Status): Color = when (status) {
    Status.Connected -> Palette.green
    Status.Connecting, is Status.Disconnected -> Palette.yellow
    is Status.NeedsUser -> Palette.orange
}

/** Trying to connect this moment, rather than connected or waiting (to try again, or for the user). */
private val Status.attempting: Boolean get() = this == Status.Connecting || (this is Status.Disconnected && trying)

/** Down: trying to connect, or waiting to try again. */
internal val Status.down: Boolean get() = this == Status.Connecting || this is Status.Disconnected

/** Waiting to try again, which a retry cuts short. */
private val Status.waiting: Boolean get() = this is Status.Disconnected && !trying

/** The connection as a dot in its [statusColor]; while it tries to connect, rings ripple out of it. */
@Composable
internal fun ConnectionDot(status: Status, modifier: Modifier = Modifier) {
    val color by animateColorAsState(statusColor(status), Motion.effect(), label = "connection")
    val ripple = if (status.attempting) {
        rememberInfiniteTransition(label = "connection").animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = LinearOutSlowInEasing)), label = "ripple")
    } else {
        null
    }
    Canvas(modifier.size(14.dp)) {
        val dot = size.minDimension / 4
        ripple?.value?.let { f ->
            drawCircle(color.copy(alpha = 0.7f * (1 - f)), radius = dot + (size.minDimension / 2 - dot) * f, style = Stroke(1.2.dp.toPx()))
        }
        drawCircle(color, dot)
    }
}

/**
 * The connection's way in, at the end of the tab bar: its dot says how it
 * is. Its menu names the computer ([machine], at [address]) and the [session],
 * says when it tries next and why the last try failed, and offers a retry
 * then, the sessions (leaving this one, if [onSessions]), settings and
 * disconnecting.
 */
@Composable
internal fun ConnectionButton(
    status: Status,
    machine: String,
    address: String,
    session: String?,
    onRetry: () -> Unit,
    onSessions: (() -> Unit)?,
    onSettings: () -> Unit,
    onExit: () -> Unit,
    onFiles: (() -> Unit)? = null,
) {
    val menu = rememberOpener()
    Box(Modifier.padding(end = 6.dp)) {
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .opens(menu)
                .semantics { contentDescription = "connection to $machine" }
                .padding(horizontal = 9.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) { ConnectionDot(status) }
        PopupMenu(
            menu,
            actions = listOfNotNull(
                if (status.waiting) MenuAction("retry now", Palette.green) { menu.open = false; onRetry() } else null,
                onSessions?.let { MenuAction("sessions") { menu.open = false; it() } },
                onFiles?.let { MenuAction("files") { menu.open = false; it() } },
                MenuAction("settings") { menu.open = false; onSettings() },
                MenuAction("disconnect", Palette.red) { menu.open = false; onExit() },
            ),
            header = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(machine, color = Palette.fg, style = Type.body, fontWeight = if (grotesque) FontWeight.SemiBold else FontWeight.Bold)
                    Text(statusLine(status, session), color = statusColor(status), style = Type.label)
                    (status as? Status.Disconnected)?.let { Text("last try: ${it.reason}", color = Palette.grey, style = Type.caption, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    Text(address, color = Palette.dim, fontFamily = Mono, fontSize = 11.sp)
                }
            },
        )
    }
}

/** How the connection is, in words, and where it has the phone ([session]: its name and multiplexer), if anywhere. */
@Composable
internal fun statusLine(status: Status, session: String?): String = when (status) {
    Status.Connected -> listOfNotNull("connected", session).joinToString(" · ")
    Status.Connecting -> "connecting…"
    is Status.Disconnected -> {
        val left = if (status.trying) 0 else secondsUntil(status.retryAt)
        if (left > 0) "reconnecting in ${left}s" else "reconnecting…"
    }
    is Status.NeedsUser -> "waiting for you"
}

/**
 * The connection on its way back, over what was shown (dimmed beneath it
 * meanwhile): when it tries next, with a retry now, and after a while
 * down, what might be wrong.
 */
@Composable
private fun ReconnectingCard(status: Status, machine: String, onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.bg1)
            .border(1.dp, Palette.border, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConnectionDot(status)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text("reconnecting to $machine…", color = Palette.fg, style = Type.label)
            nextTry(status, machine)?.let { Text(it, color = Palette.grey, style = Type.caption) }
        }
        RetryNow(status, onRetry)
    }
}

/** Whether to say the connection's down: down a moment, as across a network change, it isn't worth saying. */
@Composable
internal fun rememberSaysDown(status: Status): Boolean {
    val down = status.down
    var says by remember { mutableStateOf(false) }
    LaunchedEffect(down) {
        if (down) delay(DOWN_UNSAID_MS)
        says = down
    }
    return says
}

/** [ReconnectingCard] while [shown], saying how it went the last time it was down as it goes. */
@Composable
internal fun Reconnecting(shown: Boolean, status: Status, machine: String, onRetry: () -> Unit) {
    val last = rememberLast(status.takeIf { it.down })
    AnimatedVisibility(shown, enter = fadeIn(Motion.effect()) + expandVertically(Motion.spatial()), exit = fadeOut(Motion.effect()) + shrinkVertically(Motion.spatial())) {
        last?.let { ReconnectingCard(it, machine, onRetry) }
    }
}

/**
 * What the connection to [machine] waits on the user for, if it does, as a
 * card that comes and goes ([BlockedCard]): a locked key is asked for at
 * once, with the app in front, as it locks or as the app comes back to it,
 * since connecting is the moment to ask for the fingerprint; turned down,
 * the card's unlock asks again.
 */
@Composable
internal fun Blocking(
    status: Status,
    machine: String,
    model: AppModel,
    session: Session,
    unlocker: Unlocker,
    onSetup: () -> Unit,
) {
    val blocked = (status as? Status.NeedsUser)?.blocked
    val retry by rememberUpdatedState { ok: Boolean -> if (ok) session.retry() }
    val front by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val inFront = front.isAtLeast(Lifecycle.State.RESUMED) // a prompt can't come up over a stopped app
    LaunchedEffect(blocked is Blocked.KeyLocked, inFront) { if (blocked is Blocked.KeyLocked && inFront) unlocker.ask { retry(it) } }
    val unlocked = { unlocker.unlock { retry(it) } }
    val last = rememberLast(blocked)
    AnimatedVisibility(blocked != null, enter = fadeIn(Motion.effect()) + expandVertically(Motion.spatial()), exit = fadeOut(Motion.effect()) + shrinkVertically(Motion.spatial())) {
        last?.let {
            BlockedCard(
                blocked = it,
                machine = machine,
                unlockSeconds = model.unlockSeconds.value,
                authorizeCommand = model::authorizeCommand,
                onUnlock = unlocked,
                onTrust = { k -> model.hostKeys.trust(k); session.retry() },
                onForget = { k -> model.hostKeys.forget(k.host); session.retry() },
                onRetry = session::retry,
                onSetup = onSetup,
            )
        }
    }
}

/** How long the connection is down before the screen says so. */
private const val DOWN_UNSAID_MS = 1_500L

/**
 * What shows before there's anything of the computer's to: the flower, swaying
 * while it tries to connect, the computer ([machine], at [address]), and when it
 * tries next, with a retry now. While it waits for the user, the card
 * above says what to do, and only the flower stays.
 */
@Composable
internal fun Connecting(status: Status, machine: String, address: String, onRetry: () -> Unit, modifier: Modifier = Modifier, opening: String? = null) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Flower(sway = status.attempting, Modifier.size(144.dp))
        val said by animateFloatAsState(if (status is Status.NeedsUser) 0f else 1f, Motion.effect(), label = "connecting")
        Column(
            Modifier.graphicsLayer { alpha = said },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(if (status == Status.Connected && opening != null) "opening $opening…" else "connecting to $machine…", color = Palette.fg, style = Type.body)
            Text(address, color = Palette.dim, fontFamily = Mono, fontSize = 11.sp)
            nextTry(status, machine)?.let { Text(it, color = Palette.grey, style = Type.caption, textAlign = TextAlign.Center) }
            RetryNow(status, onRetry)
        }
    }
}

/** wallflower's flower, swaying from its stem while [sway]. */
@Composable
internal fun Flower(sway: Boolean, modifier: Modifier = Modifier) {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(sway) {
        if (!sway) {
            angle.animateTo(0f, Motion.spatial())
            return@LaunchedEffect
        }
        while (true) {
            angle.animateTo(SWAY_DEGREES, tween(1_600, easing = FastOutSlowInEasing))
            angle.animateTo(-SWAY_DEGREES, tween(1_600, easing = FastOutSlowInEasing))
        }
    }
    Image(
        painterResource(R.drawable.flower),
        contentDescription = null,
        modifier = modifier.graphicsLayer {
            rotationZ = angle.value
            transformOrigin = TransformOrigin(0.5f, 0.73f) // the foot of its stem
        },
    )
}

/** When it tries next, and after a while down, what might be wrong; null before a first try has failed. */
@Composable
private fun nextTry(status: Status, machine: String): String? {
    val down = status as? Status.Disconnected ?: return null
    val left = if (down.trying) 0 else secondsUntil(down.retryAt)
    val next = if (left > 0) "next try in ${left}s" else "trying now…"
    return if (SystemClock.elapsedRealtime() - down.since < STILL_DOWN_MS) next else "$next · is $machine awake and online?"
}

/** Cuts the wait for the next try short; faded out, and in its place, while there's none to cut. */
@Composable
private fun RetryNow(status: Status, onRetry: () -> Unit) {
    val waiting = status.waiting
    val shown by animateFloatAsState(if (waiting) 1f else 0f, Motion.effect(), label = "retry")
    val haptics = haptics()
    Text(
        "retry now",
        color = Palette.green,
        style = Type.label,
        modifier = Modifier
            .graphicsLayer { alpha = shown }
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = waiting) {
                haptics.tick()
                onRetry()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/** Whole seconds until [at], on the session's clock, counting down to 0. */
@Composable
private fun secondsUntil(at: Long): Int {
    val left by produceState(secondsLeft(at), at) {
        while (value > 0) {
            delay(at - SystemClock.elapsedRealtime() - (value - 1) * 1_000L)
            value = secondsLeft(at)
        }
    }
    return left
}

private fun secondsLeft(at: Long) = ((at - SystemClock.elapsedRealtime() + 999) / 1_000).toInt().coerceAtLeast(0)

/** Down this long, it says what might be wrong. */
private const val STILL_DOWN_MS = 20_000L

private const val SWAY_DEGREES = 4f

/** What to do about a connection to [machine] that's waiting on the user, in plain words. */
@Composable
fun BlockedCard(
    blocked: Blocked,
    machine: String,
    unlockSeconds: Int?,
    authorizeCommand: () -> String,
    onUnlock: () -> Unit,
    onTrust: (Blocked.UnknownHost) -> Unit,
    onForget: (Blocked.HostKeyChanged) -> Unit,
    onRetry: () -> Unit,
    onSetup: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.bg2)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (blocked) {
            is Blocked.KeyLocked -> {
                Title("unlock to connect")
                Body("This phone's key to $machine is kept in its secure hardware. Your fingerprint or screen lock unlocks it for ${unlockLabel(unlockSeconds ?: HardwareKey.AUTH_SECONDS)}.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button("unlock", primary = true, onClick = onUnlock)
                    Button("settings", onClick = onSetup)
                }
            }
            is Blocked.UnknownHost -> {
                Title("is this $machine?")
                Body("It's the first time this phone connects to ${blocked.host}. Trust it if this matches $machine's own key, which this shows on it:")
                Code("ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub")
                Text(blocked.fingerprint, color = Palette.aqua, fontFamily = Mono, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button("trust", primary = true) { onTrust(blocked) }
                    Button("settings", onClick = onSetup)
                }
            }
            is Blocked.HostKeyChanged -> {
                Title("$machine's key changed", Palette.red)
                Body("${blocked.host} now shows a different key, ${blocked.fingerprint}. If you didn't reinstall $machine or change its keys, someone may be listening in: don't connect.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button("forget old key") { onForget(blocked) }
                    Button("settings", onClick = onSetup)
                }
            }
            is Blocked.NotAuthorized -> {
                Title("$machine doesn't know this phone yet")
                Body("Run this on $machine to let this phone in (it can only run wallflower), then retry:")
                val command = authorizeCommand()
                Code(command)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button("retry", primary = true, onClick = onRetry)
                    Button("copy") { copy(context, command) }
                    Button("share") { share(context, command) }
                }
            }
            is Blocked.Outdated -> {
                val macOlder = blocked.gateway < blocked.app
                Title(if (macOlder) "update wallflower on $machine" else "update this app")
                if (macOlder) {
                    Body("$machine's wallflower speaks protocol ${blocked.gateway}, this app ${blocked.app}. Update it with the line it was installed with (go install, from a checkout), then retry:")
                    Code(INSTALL)
                } else {
                    Body("$machine's wallflower speaks protocol ${blocked.gateway}, this app ${blocked.app}. Install this app's latest build, then retry.")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button("retry", primary = true, onClick = onRetry)
                    Button("settings", onClick = onSetup)
                }
            }
            is Blocked.Refused -> {
                Title("$machine turned the connection down")
                Body("Its wallflower said: ${blocked.reason}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button("retry", primary = true, onClick = onRetry)
                    Button("settings", onClick = onSetup)
                }
            }
        }
    }
}

@Composable
private fun Title(text: String, color: Color = Palette.fg) =
    Text(text, color = color, fontFamily = words, fontSize = wordSize(14.sp, 17.sp), fontWeight = if (grotesque) FontWeight.SemiBold else FontWeight.Bold)
