package sh.ahmd.wallflower.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import sh.ahmd.wallflower.BarKey
import sh.ahmd.wallflower.KeyBar
import sh.ahmd.wallflower.KeyBarStore
import sh.ahmd.wallflower.KeyProfile
import sh.ahmd.wallflower.prefs.Look
import sh.ahmd.wallflower.prefs.Preferences
import sh.ahmd.wallflower.program.Programs
import sh.ahmd.wallflower.protocol.Mux
import sh.ahmd.wallflower.session.Host

/** A profile of the key bar, by where it sits. */
sealed interface ProfileRef {
    data object Default : ProfileRef
    data class At(val index: Int) : ProfileRef
}

/** The profile the bar of a pane running [command] (the gateway's [program]) comes from. */
fun KeyBar.refFor(command: String?, program: String? = null): ProfileRef {
    val i = profiles.indexOfFirst { it.matches(command, program) }
    return if (i >= 0) ProfileRef.At(i) else ProfileRef.Default
}

@Composable
fun SettingsScreen(
    host: Host?,
    store: KeyBarStore,
    settings: Preferences,
    program: String?,
    programId: String?,
    onBack: () -> Unit,
    onEditHost: () -> Unit,
    onOpen: (ProfileRef) -> Unit,
) {
    val bar by store.bar.collectAsStateWithLifecycle()
    val focused = bar.refFor(program, programId)
    Page("settings", onBack) {
        Section("look")
        val look by settings.look.flow.collectAsStateWithLifecycle()
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("terminal", look == Look.Terminal) { settings.look.set(Look.Terminal) }
            Chip("bricolage", look == Look.Grotesque, Grotesque) { settings.look.set(Look.Grotesque) }
        }
        Body("The terminal, what you type for it, keys and values stay in the terminal font.")
        val feel by settings.haptics.flow.collectAsStateWithLifecycle()
        Toggle("haptics for swipes, switches and sends", feel) { settings.haptics.set(it) }

        Section("mac")
        if (host != null) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.bg1).clickable(onClick = onEditHost).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${host.user}@${host.address}:${host.port}", color = Palette.fg, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("edit ›", color = Palette.grey, style = Type.label)
            }
        }

        Section("closing")
        val askPanes by settings.askClosingPane.flow.collectAsStateWithLifecycle()
        val askWindows by settings.askClosingWindow.flow.collectAsStateWithLifecycle()
        Toggle("ask before closing a pane", askPanes) { settings.askClosingPane.set(it) }
        Toggle("ask before closing a tab (or its last pane)", askWindows) { settings.askClosingWindow.set(it) }

        Section("mobile view")
        val mobile by settings.mobileView.flow.collectAsStateWithLifecycle()
        Toggle("show panes in mobile view where there is one", mobile) { settings.mobileView.set(it) }
        Body("Claude Code shows as its conversation: replies as markdown, steps folded. The button by a pane's dots switches every pane; the terminal keeps running underneath.")

        Section("key bar")
        Body(
            "Follows the focused pane's program; the first profile listing it wins." +
                (program?.takeIf { it.isNotEmpty() }?.let { " The focused pane runs $it." } ?: ""),
        )
        bar.profiles.forEachIndexed { i, p ->
            ProfileRow(p, p.programs.joinToString(" "), focused == ProfileRef.At(i)) { onOpen(ProfileRef.At(i)) }
        }
        ProfileRow(bar.default, "everything else", focused == ProfileRef.Default) { onOpen(ProfileRef.Default) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("new profile") {
                val forProgram = program?.takeIf { it.isNotEmpty() && focused == ProfileRef.Default }
                val index = bar.profiles.size
                store.update { b ->
                    b.copy(profiles = b.profiles + KeyProfile(forProgram ?: "new", listOfNotNull(forProgram), b.default.keys, b.default.stop))
                }
                onOpen(ProfileRef.At(index))
            }
            var confirm by remember { mutableStateOf(false) }
            LaunchedEffect(confirm) {
                if (confirm) {
                    delay(3_000)
                    confirm = false
                }
            }
            Button(if (confirm) "tap again to reset" else "reset") {
                if (confirm) store.reset()
                confirm = !confirm
            }
        }
    }
}

@Composable
private fun ProfileRow(profile: KeyProfile, programs: String, focused: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.bg1).clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                profile.name,
                color = Palette.fg,
                fontFamily = words,
                fontSize = wordSize(13.sp, 16.sp),
                fontWeight = if (grotesque) FontWeight.SemiBold else FontWeight.Bold,
            )
            if (focused) Box(Modifier.padding(start = 8.dp).size(6.dp).clip(CircleShape).background(Palette.green))
            Text(programs, color = Palette.grey, fontFamily = Mono, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 12.dp))
            Text("›", color = Palette.grey, fontFamily = Mono, fontSize = 14.sp)
        }
        Text(profile.keys.joinToString("  ") { it.label }, color = Palette.dim, fontFamily = Mono, fontSize = 12.sp, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(store: KeyBarStore, ref: ProfileRef, program: String?, programId: String?, mux: Mux?, onBack: () -> Unit, onOpen: (ProfileRef) -> Unit) {
    val bar by store.bar.collectAsStateWithLifecycle()
    val profile = when (ref) {
        ProfileRef.Default -> bar.default
        is ProfileRef.At -> bar.profiles.getOrNull(ref.index)
    }
    if (profile == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    // What a multiplexer that can't take keys through its bindings doesn't offer; unknown until connected.
    val bindings = mux?.let { Mux.BINDINGS in it.can } ?: true
    fun save(p: KeyProfile) = store.update { b ->
        when (ref) {
            ProfileRef.Default -> b.copy(default = p)
            is ProfileRef.At -> b.copy(profiles = b.profiles.toMutableList().also { it[ref.index] = p })
        }
    }
    var selected by remember(ref) { mutableStateOf<Int?>(null) }

    Page(if (ref == ProfileRef.Default) "default keys" else profile.name, onBack) {
        when (ref) {
            is ProfileRef.At -> {
                var name by remember(ref) { mutableStateOf(profile.name) }
                var programs by remember(ref) { mutableStateOf(profile.programs.joinToString(" ")) }
                Field("name", name) {
                    name = it
                    save(profile.copy(name = it))
                }
                Field("programs", programs) {
                    programs = it
                    save(profile.copy(programs = it.split(' ', ',').filter(String::isNotBlank)))
                }
                Body(
                    "Space-separated, as panes report them, or as wallflower names their program (shell)." +
                        (program?.takeIf { it.isNotEmpty() }?.let { " The focused pane runs $it." } ?: ""),
                )
            }
            ProfileRef.Default -> {
                Body("For programs without a profile of their own.")
                if (!program.isNullOrEmpty() && bar.refFor(program, programId) == ProfileRef.Default) {
                    Button("make a profile for $program", primary = true) {
                        val index = bar.profiles.size
                        store.update { it.copy(profiles = it.profiles + KeyProfile(program, listOf(program), profile.keys, profile.stop)) }
                        onOpen(ProfileRef.At(index))
                    }
                }
            }
        }

        Section("keys")
        Body("Tap one to move or remove it.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            profile.keys.forEachIndexed { i, k -> Chip(k.label, selected == i) { selected = if (selected == i) null else i } }
        }
        selected?.takeIf { it < profile.keys.size }?.let { i ->
            fun move(to: Int) {
                val keys = profile.keys.toMutableList()
                keys.add(to, keys.removeAt(i))
                save(profile.copy(keys = keys))
                selected = to
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("‹ earlier") { if (i > 0) move(i - 1) }
                Button("later ›") { if (i < profile.keys.lastIndex) move(i + 1) }
                Button("remove") {
                    save(profile.copy(keys = profile.keys.filterIndexed { j, _ -> j != i }))
                    selected = null
                }
            }
        }

        Section("stop")
        Body("What the send button sends when the mobile view's box is empty.")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (keys in stops) Chip(KeyBar.labelOf(keys), profile.stop == keys) { save(profile.copy(stop = keys)) }
        }

        Section("send button")
        Body("What it presses in the terminal, where the keyboard types straight into the pane.")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (keys in presses) Chip(KeyBar.labelOf(keys), profile.sendKeys() == keys) { save(profile.copy(send = keys)) }
        }

        Section("return key")
        Body("What the keyboard's return key presses there. ^j and ⌥⏎ start a new line in apps that take several, as Claude Code does; a shell runs the line.")
        val program = Programs.of(profile.programs.firstOrNull().orEmpty())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (keys in presses) Chip(KeyBar.labelOf(keys), profile.returnKeys(program) == keys) { save(profile.copy(returnKey = keys)) }
        }

        Section("clear")
        Body("What empties the app's line, so what's in the box replaces it (sent once per row of the line). Empty: the box adds to the line.")
        var clear by remember(ref) { mutableStateOf((profile.clear ?: Programs.of(profile.programs.firstOrNull().orEmpty()).clearKeys).joinToString(" ")) }
        Field("keys", clear) {
            clear = it
            save(profile.copy(clear = it.split(' ').filter(String::isNotEmpty)))
        }

        Section("add")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (k in KeyBar.common.filter { bindings || !it.bindings }) Chip(k.label, false) { save(profile.copy(keys = profile.keys + k)) }
        }

        Section("custom")
        Body(
            "Key names, space-separated; anything else is typed as is (Escape :w Enter). C- is ctrl, M- alt (⌥) and S- shift, " +
                "joined to the key they're held with (C-a, M-Enter, S-Up). BTab is ⇧tab, BSpace ⌫, DC delete, IC insert; F1 to F12 work too. Tap one to add it.",
        )
        var label by remember(ref) { mutableStateOf("") }
        var sends by remember(ref) { mutableStateOf("") }
        var bound by remember(ref) { mutableStateOf(false) }
        val keys = sends.split(' ').filter(String::isNotEmpty)
        Field("sends", sends) { sends = it }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (name in names) {
                Chip(name, false) {
                    val joins = sends.isEmpty() || sends.endsWith(' ') || sends.substringAfterLast(' ').matches(held)
                    sends = if (joins) sends + name else "$sends $name"
                }
            }
        }
        Field(if (label.isBlank() && keys.isNotEmpty()) "label (blank: ${KeyBar.labelOf(keys)})" else "label", label) { label = it }
        if (bindings) Toggle("through ${mux?.name ?: "the multiplexer"}'s key bindings (C-j to change pane; prefix is your prefix)", bound) { bound = it }
        Button("add key") {
            if (keys.isNotEmpty()) {
                save(profile.copy(keys = profile.keys + BarKey(label.ifBlank { KeyBar.labelOf(keys) }, keys, bindings = bound && bindings)))
                label = ""
                sends = ""
            }
        }

        if (ref is ProfileRef.At) {
            Spacer(Modifier.height(8.dp))
            Button("delete profile") {
                store.update { b -> b.copy(profiles = b.profiles.filterIndexed { j, _ -> j != ref.index }) }
                onBack()
            }
        }
    }
}

private val stops = listOf("C-c", "Escape", "C-d", "q").map { listOf(it) }
private val presses = listOf("Enter", "C-j", "M-Enter").map { listOf(it) }

/** wallflower's names (tmux's) for what a custom key sends, beyond text. */
private val names = listOf(
    "C-", "M-", "S-", "Enter", "Escape", "Tab", "BTab", "BSpace", "Space",
    "Up", "Down", "Left", "Right", "Home", "End", "PageUp", "PageDown", "DC", "IC",
)
private val held = Regex("([CMS]-)+")

/** A full-screen page over the terminal, with a back arrow. */
@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.bg0)
            .pointerInput(Unit) { detectTapGestures { } } // nothing reaches the terminal underneath
            .systemBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹",
                color = Palette.fg,
                fontFamily = Mono,
                fontSize = 22.sp,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(horizontal = 14.dp, vertical = 4.dp),
            )
            Text(title, color = Palette.fg, fontFamily = words, fontSize = wordSize(16.sp, 22.sp), fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}
