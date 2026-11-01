package sh.ahmd.wallflower.ui

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.ahmd.wallflower.BuildConfig
import sh.ahmd.wallflower.session.Blocked
import sh.ahmd.wallflower.session.HardwareKey
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
        Text("Your Mac's tmux, over Tailscale and ssh.", color = Palette.grey, style = Type.body)

        Section("mac")
        Field("host", address) { address = it }
        Field("user", user) { user = it }
        Field("port", port, KeyboardType.Number) { port = it.filter(Char::isDigit) }
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
            onConnect(Host(address = address.trim(), user = user.trim(), port = port.toIntOrNull() ?: 22, command = initial.command, dev = dev))
        }
    }
}

private val UNLOCKS = listOf(5 * 60, 30 * 60, 2 * 3600, 8 * 3600)

private fun unlockLabel(seconds: Int) = if (seconds < 3600) "${seconds / 60} min" else "${seconds / 3600} h"

/** What to do about a connection that's waiting on the user. */
@Composable
fun BlockedCard(
    blocked: Blocked,
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
                Title("key locked")
                Body("Your fingerprint or screen lock unlocks the key for ${unlockLabel(unlockSeconds ?: HardwareKey.AUTH_SECONDS)}.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button("unlock", primary = true, onClick = onUnlock)
                    Button("settings", onClick = onSetup)
                }
            }
            is Blocked.UnknownHost -> {
                Title("first connection to ${blocked.host}")
                Body("Check this matches the Mac's host key (ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub):")
                Text(blocked.fingerprint, color = Palette.aqua, fontFamily = Mono, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button("trust", primary = true) { onTrust(blocked) }
                    Button("settings", onClick = onSetup)
                }
            }
            is Blocked.HostKeyChanged -> {
                Title("host key changed", Palette.red)
                Body("${blocked.host} now presents ${blocked.fingerprint}. If you didn't reinstall the Mac or change its keys, someone may be intercepting the connection.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button("forget old key") { onForget(blocked) }
                    Button("settings", onClick = onSetup)
                }
            }
            is Blocked.Refused -> {
                Title("the Mac turned the connection down")
                Body(blocked.reason)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button("retry", primary = true, onClick = onRetry)
                    Button("settings", onClick = onSetup)
                }
            }
            is Blocked.NotAuthorized -> {
                Title("not authorized yet")
                Body("Run this on the Mac, then retry:")
                val command = authorizeCommand()
                Code(command)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button("copy") { copy(context, command) }
                    Button("retry", primary = true, onClick = onRetry)
                }
            }
        }
    }
}

@Composable
private fun Title(text: String, color: Color = Palette.fg) =
    Text(text, color = color, fontFamily = words, fontSize = wordSize(14.sp, 17.sp), fontWeight = if (grotesque) FontWeight.SemiBold else FontWeight.Bold)

@Composable
private fun Code(text: String) = Text(
    text,
    color = Palette.fg,
    fontFamily = Mono,
    fontSize = 11.sp,
    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.bg1).padding(10.dp),
)

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Authorize wallflower"))
}
