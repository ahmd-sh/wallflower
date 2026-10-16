package sh.ahmd.wallflower.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.ahmd.wallflower.BuildConfig
import sh.ahmd.wallflower.session.Blocked
import sh.ahmd.wallflower.session.Host

@Composable
fun SetupScreen(
    host: Host?,
    hasKey: Boolean,
    fingerprint: String?,
    authorizeCommand: () -> String,
    createKey: () -> String?,
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
        Text("wallflower", color = Palette.fg, fontFamily = Mono, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Your Mac's tmux, over Tailscale and ssh.", color = Palette.grey, fontFamily = Mono, fontSize = 13.sp)

        Section("mac")
        Field("host", address) { address = it }
        Field("user", user) { user = it }
        Field("port", port, KeyboardType.Number) { port = it.filter(Char::isDigit) }
        if (BuildConfig.DEBUG) {
            Toggle("development transport (adb reverse, no ssh)", dev) { dev = it }
        }

        Section("this phone's key")
        if (hasKey && fingerprint != null) {
            Text("ecdsa p-256 · in secure hardware · unlocks with fingerprint", color = Palette.grey, fontFamily = Mono, fontSize = 12.sp)
            Text(fingerprint, color = Palette.aqua, fontFamily = Mono, fontSize = 12.sp)
            Text("Authorize it on the Mac (restricted to running wallflower, from Tailscale):", color = Palette.fg, fontFamily = Mono, fontSize = 12.sp)
            val command = authorizeCommand()
            Code(command)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("copy") { copy(context, command) }
                Button("share") { share(context, command) }
            }
        } else {
            Button("create key") { keyError = createKey() }
            keyError?.let { Text(it, color = Palette.red, fontFamily = Mono, fontSize = 12.sp) }
        }

        Spacer(Modifier.height(4.dp))
        Button("connect", primary = true) {
            onConnect(Host(address = address.trim(), user = user.trim(), port = port.toIntOrNull() ?: 22, command = initial.command, dev = dev))
        }
    }
}

/** What to do about a connection that's waiting on the user. */
@Composable
fun BlockedCard(
    blocked: Blocked,
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
                Body("Your fingerprint or screen lock unlocks the key for 5 minutes.")
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
internal fun Section(label: String) = Text(
    label,
    color = Palette.orange,
    fontFamily = words,
    fontSize = if (grotesque) 15.sp else 12.sp,
    fontWeight = if (grotesque) FontWeight.SemiBold else FontWeight.Bold,
    modifier = Modifier.padding(top = 8.dp),
)

@Composable
private fun Title(text: String, color: Color = Palette.fg) =
    Text(text, color = color, fontFamily = Mono, fontSize = 14.sp, fontWeight = FontWeight.Bold)

@Composable
internal fun Body(text: String) = Text(
    text,
    color = Palette.grey,
    fontFamily = words,
    fontSize = if (grotesque) 14.sp else 12.sp,
    lineHeight = if (grotesque) 20.sp else TextUnit.Unspecified,
)

@Composable
private fun Code(text: String) = Text(
    text,
    color = Palette.fg,
    fontFamily = Mono,
    fontSize = 11.sp,
    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.bg1).padding(10.dp),
)

@Composable
internal fun Field(label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Palette.dim, fontFamily = words, fontSize = if (grotesque) 13.sp else 11.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Palette.fg, fontFamily = Mono, fontSize = 15.sp),
            cursorBrush = SolidColor(Palette.orange),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.bg1).padding(12.dp),
        )
    }
}

@Composable
internal fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    if (grotesque) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onChange(!on) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(on, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Palette.orange, uncheckedColor = Palette.grey, checkmarkColor = Palette.bg0))
            Text(label, color = Palette.fg, fontFamily = Grotesque, fontSize = 15.sp, lineHeight = 20.sp, modifier = Modifier.padding(start = 14.dp).weight(1f))
        }
        return
    }
    Row(Modifier.clickable { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Text(if (on) "[x] " else "[ ] ", color = Palette.orange, fontFamily = Mono, fontSize = 13.sp)
        Text(label, color = Palette.fg, fontFamily = Mono, fontSize = 12.sp)
    }
}

@Composable
internal fun Button(label: String, primary: Boolean = false, onClick: () -> Unit) = Text(
    label,
    color = if (primary) Palette.bg0 else Palette.fg,
    fontFamily = words,
    fontSize = if (grotesque) 14.sp else 13.sp,
    fontWeight = if (!primary) FontWeight.Normal else if (grotesque) FontWeight.SemiBold else FontWeight.Bold,
    modifier = Modifier
        .clip(RoundedCornerShape(8.dp))
        .background(if (primary) Palette.green else Palette.bg3)
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp),
)

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("wallflower", text))
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Authorize wallflower"))
}
