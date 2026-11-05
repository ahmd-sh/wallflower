package sh.ahmd.wallflower.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.ahmd.wallflower.BuildConfig
import sh.ahmd.wallflower.session.Host

/**
 * Setting wallflower up: what it is, in a line, then the Mac to reach and
 * this phone's key to let in there. With a way back ([onBack]) in place of
 * the welcome, it edits the Mac from settings.
 */
@Composable
fun SetupScreen(
    host: Host?,
    hasKey: Boolean,
    fingerprint: String?,
    authorizeCommand: () -> String,
    createKey: () -> String?,
    unlockSeconds: Int?,
    newKey: (seconds: Int) -> String?,
    onBack: (() -> Unit)?,
    onConnect: (Host) -> Unit,
) {
    val form = @Composable { SetupForm(host, hasKey, fingerprint, authorizeCommand, createKey, unlockSeconds, newKey, onConnect) }
    if (onBack != null) {
        Page("mac", onBack, form)
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.bg0)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Flower(sway = true, Modifier.size(128.dp))
            Text("wallflower", color = Palette.fg, fontFamily = words, fontSize = wordSize(24.sp, 30.sp), fontWeight = FontWeight.Bold)
            Text("Your Mac's terminals, made for your phone.", color = Palette.grey, style = Type.body, textAlign = TextAlign.Center)
        }
        form()
    }
}

/** The Mac (where, who, which multiplexer), this phone's key, and connect, saying plainly what's missing. */
@Composable
private fun SetupForm(
    host: Host?,
    hasKey: Boolean,
    fingerprint: String?,
    authorizeCommand: () -> String,
    createKey: () -> String?,
    unlockSeconds: Int?,
    newKey: (seconds: Int) -> String?,
    onConnect: (Host) -> Unit,
) {
    val initial = host ?: Host()
    var address by remember { mutableStateOf(initial.address) }
    var user by remember { mutableStateOf(initial.user) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var mux by remember { mutableStateOf(initial.mux) }
    var dev by remember { mutableStateOf(initial.dev) }
    var keyError by remember { mutableStateOf<String?>(null) }
    var tried by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Section("your Mac")
    Field("address", address, KeyboardType.Uri, hint = "100.x.y.z, or its tailnet name") { address = it }
    Text("This phone's key only works over Tailscale, so it's the Mac's Tailscale address.", color = Palette.dim, style = Type.caption)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field("username", user, hint = "yours on the Mac", modifier = Modifier.weight(1f)) { user = it }
        Field("port", port, KeyboardType.Number, modifier = Modifier.width(88.dp)) { port = it.filter(Char::isDigit).take(5) }
    }
    Text("sessions in", color = Palette.dim, style = Type.caption)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (m in MUXES) Chip(m, mux == m) { mux = m }
    }
    if (BuildConfig.DEBUG) {
        Toggle("development transport (adb reverse, no ssh)", dev) { dev = it }
    }

    Section("this phone's key")
    if (hasKey && fingerprint != null) {
        Body("Made and kept in this phone's secure hardware; your fingerprint unlocks it.")
        Text(fingerprint, color = Palette.aqua, fontFamily = Mono, fontSize = 12.sp)
        Text("Let it in: run this on the Mac. It can start wallflower there, and nothing else.", color = Palette.fg, style = Type.label)
        val command = authorizeCommand()
        Code(command)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("copy") { copy(context, command) }
            Button("share") { share(context, command) }
        }
        if (host != null) {
            Text("Unlocked for", color = Palette.fg, style = Type.label)
            Body("How long after your fingerprint or screen lock (unlocking the phone counts) the key works, so the app can reconnect on its own while you're away.")
            var picked by remember { mutableStateOf<Int?>(null) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in UNLOCKS) Chip(unlockLabel(s), (picked ?: unlockSeconds) == s) { picked = s.takeIf { it != unlockSeconds } }
            }
            picked?.let { s ->
                Body("The time is part of the key, so this makes a new one. Authorize it on the Mac before this connection ends (a pane here works): until then, only the Mac can let the phone back in.")
                Button("make a new key, unlocked for ${unlockLabel(s)}", primary = true) {
                    keyError = newKey(s)
                    picked = null
                }
            }
        }
    } else {
        Body("wallflower signs in to the Mac with a key made and kept in this phone's secure hardware, unlocked by your fingerprint. It never leaves the phone.")
        Button("make the key", primary = true) { keyError = createKey() }
    }
    keyError?.let { Text(it, color = Palette.red, style = Type.label) }

    Spacer(Modifier.height(4.dp))
    val missing = missing(address, user, port, dev, hasKey)
    Button("connect", primary = true) {
        tried = true
        if (missing == null) {
            onConnect(
                Host(
                    address = address.trim(), user = user.trim(), port = port.toInt(), command = initial.command, mux = mux,
                    session = initial.session.takeIf { mux == initial.mux }, dev = dev, machine = initial.machine.takeIf { address.trim() == initial.address },
                ),
            )
        }
    }
    if (tried) missing?.let { Text(it, color = Palette.red, style = Type.label) }
}

/** What connecting needs that's not there yet, in a line; null if nothing. */
private fun missing(address: String, user: String, port: String, dev: Boolean, hasKey: Boolean): String? = when {
    port.toIntOrNull() !in 1..65535 -> "The port is a number up to 65535; ssh's is 22."
    dev -> null
    address.isBlank() -> "Fill in the Mac's address."
    user.isBlank() -> "Fill in your username on the Mac."
    !hasKey -> "Make this phone's key first."
    else -> null
}

private val UNLOCKS = listOf(5 * 60, 30 * 60, 2 * 3600, 8 * 3600)

/** The multiplexers the gateway can serve a session of. */
private val MUXES = listOf("tmux", "rex")

internal fun unlockLabel(seconds: Int) = if (seconds < 3600) "${seconds / 60} min" else "${seconds / 3600} h"
