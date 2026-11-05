package sh.ahmd.wallflower.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.ahmd.wallflower.BuildConfig
import sh.ahmd.wallflower.session.Host

@Composable
fun SetupScreen(
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
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.bg0)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("wallflower", color = Palette.fg, fontFamily = words, fontSize = wordSize(22.sp, 28.sp), fontWeight = FontWeight.Bold)
        Text("Your Mac's terminals, over Tailscale and ssh.", color = Palette.grey, style = Type.body)

        Section("mac")
        Field("host", address) { address = it }
        Field("user", user) { user = it }
        Field("port", port, KeyboardType.Number) { port = it.filter(Char::isDigit) }
        Text("sessions in", color = Palette.fg, style = Type.label)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in MUXES) Chip(m, mux == m) { mux = m }
        }
        if (BuildConfig.DEBUG) {
            Toggle("development transport (adb reverse, no ssh)", dev) { dev = it }
        }

        Section("this phone's key")
        if (hasKey && fingerprint != null) {
            Body("ecdsa p-256 · in secure hardware · unlocks with fingerprint")
            Text(fingerprint, color = Palette.aqua, fontFamily = Mono, fontSize = 12.sp)
            Text("Authorize it on the Mac (restricted to running wallflower, from Tailscale):", color = Palette.fg, style = Type.label)
            val command = authorizeCommand()
            Code(command)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("copy") { copy(context, command) }
                Button("share") { share(context, command) }
            }
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
            keyError?.let { Text(it, color = Palette.red, style = Type.label) }
        } else {
            Button("create key") { keyError = createKey() }
            keyError?.let { Text(it, color = Palette.red, style = Type.label) }
        }

        Spacer(Modifier.height(4.dp))
        Button("connect", primary = true) {
            onConnect(
                Host(
                    address = address.trim(), user = user.trim(), port = port.toIntOrNull() ?: 22, command = initial.command, mux = mux,
                    session = initial.session.takeIf { mux == initial.mux }, dev = dev, machine = initial.machine.takeIf { address.trim() == initial.address },
                ),
            )
        }
    }
}

private val UNLOCKS = listOf(5 * 60, 30 * 60, 2 * 3600, 8 * 3600)

/** The multiplexers the gateway can serve a session of. */
private val MUXES = listOf("tmux", "rex")

internal fun unlockLabel(seconds: Int) = if (seconds < 3600) "${seconds / 60} min" else "${seconds / 3600} h"
