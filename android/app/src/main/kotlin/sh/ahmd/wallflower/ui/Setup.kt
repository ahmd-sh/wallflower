package sh.ahmd.wallflower.ui

import android.content.Context
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch
import sh.ahmd.wallflower.BuildConfig
import sh.ahmd.wallflower.session.Host
import sh.ahmd.wallflower.session.Pairing

/**
 * Setting wallflower up: what it is, in a line, then the code `wallflower
 * setup` shows on the computer to scan, or by hand, the computer to reach
 * and this phone's key to let in there. With a way back ([onBack]) in place
 * of the welcome, it edits the computer from settings, or pairs with one
 * again.
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
    pair: suspend (Pairing) -> String?,
    onBack: (() -> Unit)?,
    onConnect: (Host) -> Unit,
) {
    val form = @Composable { SetupForm(host, hasKey, fingerprint, authorizeCommand, createKey, unlockSeconds, newKey, onConnect) }
    if (onBack != null) {
        Page("computer", onBack) {
            Text("Pair with this computer again, or another one: run this there, then scan the code it shows.", color = Palette.fg, style = Type.label)
            Code("wallflower pair")
            ScanCode("scan a code", primary = false, createKey, pair, onConnect)
            form()
        }
        return
    }
    var byHand by remember { mutableStateOf(host != null) }
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
            Text("Your computer's terminals, made for your phone.", color = Palette.grey, style = Type.body, textAlign = TextAlign.Center)
        }
        Text("On your computer, run this. It gets the computer ready, then shows a code.", color = Palette.fg, style = Type.label)
        Code("wallflower setup")
        ScanCode("scan the code", primary = true, createKey, pair, onConnect)
        if (byHand) {
            form()
        } else {
            Text(
                "set up by hand",
                color = Palette.grey,
                style = Type.label,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { byHand = true }.padding(vertical = 6.dp),
            )
        }
    }
}

/** A button that scans a code and pairs with the computer it's from, then who it's pairing with, or why it couldn't. */
@Composable
private fun ScanCode(label: String, primary: Boolean, createKey: () -> String?, pair: suspend (Pairing) -> String?, onPaired: (Host) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pairing by remember { mutableStateOf<Pairing?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Button(label, primary) {
        if (pairing != null) return@Button
        error = createKey()
        if (error != null) return@Button
        scan(context, onError = { error = it }) { link ->
            val p = Pairing.parse(link)
            if (p == null) {
                error = "That's not a wallflower code: scan the one wallflower setup or wallflower pair shows."
                return@scan
            }
            error = null
            pairing = p
            scope.launch {
                val why = pair(p)
                pairing = null
                if (why == null) onPaired(p.host) else error = why
            }
        }
    }
    pairing?.let { Text("pairing with ${it.called}…", color = Palette.fg, style = Type.label) }
    error?.let { Text(it, color = Palette.red, style = Type.label) }
}

/** Opens Google's code scanner, for QR codes: [onCode] gets what it read, [onError] why it couldn't open. Backing out does nothing. */
private fun scan(context: Context, onError: (String) -> Unit, onCode: (String) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { code -> code.rawValue?.let(onCode) }
        .addOnFailureListener { e ->
            onError(
                if ((e as? MlKitException)?.errorCode == MlKitException.UNAVAILABLE) {
                    "The scanner is still downloading, the first time: try again in a moment."
                } else {
                    "The scanner didn't open: it comes with Google Play services. The phone's camera can open the code too."
                },
            )
        }
}

/**
 * Asks before pairing with the computer a code's [link] is from (opened from
 * the camera, say), saying so if it takes the place of [host]; then pairs,
 * saying how it went.
 */
@Composable
internal fun PairLink(link: String, host: Host?, pair: suspend (Pairing) -> String?, onPaired: (Host) -> Unit, onDismiss: () -> Unit) {
    val pairing = remember(link) { Pairing.parse(link) }
    if (pairing == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Palette.bg1,
            title = { DialogTitle("not a whole code") },
            text = {
                Text(
                    "The code didn't come through whole. Scan it again, or run wallflower pair on your computer for a new one.",
                    color = Palette.grey, style = Type.body, lineHeight = wordSize(TextUnit.Unspecified, 21.sp),
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("ok", color = Palette.fg, fontFamily = words) } },
        )
        return
    }
    val scope = rememberCoroutineScope()
    var busy by remember(link) { mutableStateOf(false) }
    var error by remember(link) { mutableStateOf<String?>(null) }
    val replacing = host?.takeIf { it.address != pairing.address }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Palette.bg1,
        title = { DialogTitle("pair with ${pairing.called}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "This phone will connect to it at ${pairing.address}, as ${pairing.user}." +
                        (replacing?.let { " It takes the place of ${it.machine ?: it.address}, the computer it connects to now." } ?: ""),
                    color = Palette.grey, style = Type.body, lineHeight = wordSize(TextUnit.Unspecified, 21.sp),
                )
                if (busy) Text("pairing with ${pairing.called}…", color = Palette.fg, style = Type.label)
                error?.let { Text(it, color = Palette.red, style = Type.label) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        val why = pair(pairing)
                        busy = false
                        if (why == null) onPaired(pairing.host) else error = why
                    }
                },
            ) { Text(if (error == null) "pair" else "try again", color = if (busy) Palette.dim else Palette.green, fontFamily = words) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("cancel", color = Palette.fg, fontFamily = words) } },
    )
}

/** The computer (where, who, which multiplexer), this phone's key, and connect, saying plainly what's missing. */
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
    var dev by remember { mutableStateOf(initial.dev) }
    var keyError by remember { mutableStateOf<String?>(null) }
    var tried by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Section("your computer")
    Field("address", address, KeyboardType.Uri, hint = "100.x.y.z, or its tailnet name") { address = it }
    Text("This phone's key only works over Tailscale, so it's the computer's Tailscale address.", color = Palette.dim, style = Type.caption)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field("username", user, hint = "yours on the computer", modifier = Modifier.weight(1f)) { user = it }
        Field("port", port, KeyboardType.Number, modifier = Modifier.width(88.dp)) { port = it.filter(Char::isDigit).take(5) }
    }
    if (BuildConfig.DEBUG) {
        Toggle("development transport (adb reverse, no ssh)", dev) { dev = it }
    }

    Section("this phone's key")
    if (hasKey && fingerprint != null) {
        Body("Made and kept in this phone's secure hardware; your fingerprint unlocks it.")
        Text(fingerprint, color = Palette.aqua, fontFamily = Mono, fontSize = 12.sp)
        Text("Let it in: run this on the computer. It can start wallflower there, and nothing else.", color = Palette.fg, style = Type.label)
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
                Body("The time is part of the key, so this makes a new one. Authorize it on the computer before this connection ends (a pane here works): until then, only the computer can let the phone back in.")
                Button("make a new key, unlocked for ${unlockLabel(s)}", primary = true) {
                    keyError = newKey(s)
                    picked = null
                }
            }
        }
    } else {
        Body("wallflower signs in to the computer with a key made and kept in this phone's secure hardware, unlocked by your fingerprint. It never leaves the phone.")
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
                    address = address.trim(), user = user.trim(), port = port.toInt(), command = initial.command, mux = initial.mux,
                    session = initial.session.takeIf { address.trim() == initial.address }, dev = dev, machine = initial.machine.takeIf { address.trim() == initial.address },
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
    address.isBlank() -> "Fill in the computer's address."
    user.isBlank() -> "Fill in your username on the computer."
    !hasKey -> "Make this phone's key first."
    else -> null
}

private val UNLOCKS = listOf(5 * 60, 30 * 60, 2 * 3600, 8 * 3600)

/** The multiplexers the gateway can serve a session of. */

internal fun unlockLabel(seconds: Int) = if (seconds < 3600) "${seconds / 60} min" else "${seconds / 3600} h"
