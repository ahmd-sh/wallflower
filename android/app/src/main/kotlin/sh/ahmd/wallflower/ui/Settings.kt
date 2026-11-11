package sh.ahmd.wallflower.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import sh.ahmd.wallflower.BuildConfig
import sh.ahmd.wallflower.KeyBar
import sh.ahmd.wallflower.KeyBarStore
import sh.ahmd.wallflower.KeyProfile
import sh.ahmd.wallflower.prefs.Look
import sh.ahmd.wallflower.prefs.Preferences
import sh.ahmd.wallflower.prefs.Scheme
import sh.ahmd.wallflower.program.Programs
import sh.ahmd.wallflower.protocol.VERSION as PROTOCOL_VERSION
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

/**
 * Settings: the computer, how wallflower looks, and the key bars each a page of
 * their own, said in a line here; what's on or off is here as it is, and
 * what the phone knows of the gateway (its plugins, its versions) last.
 */
@Composable
fun SettingsScreen(
    host: Host?,
    store: KeyBarStore,
    settings: Preferences,
    program: String?,
    programId: String?,
    gateway: String?,
    onBack: () -> Unit,
    onEditHost: () -> Unit,
    onLook: () -> Unit,
    onKeyBars: () -> Unit,
) {
    val bar by store.bar.collectAsStateWithLifecycle()
    val look by settings.look.flow.collectAsStateWithLifecycle()
    val scheme by settings.scheme.flow.collectAsStateWithLifecycle()
    Page("settings", onBack) {
        if (host != null) PageRow(host.machine ?: "your computer", if (host.dev) "development transport" else "${host.user}@${host.address}:${host.port}", onEditHost)
        PageRow("look", "${scheme.label} · ${if (look == Look.Terminal) "terminal" else "bricolage"} font", onLook)
        val focused = bar.refFor(program, programId)
        val now = (focused as? ProfileRef.At)?.let { bar.profiles[it.index].name } ?: bar.default.name
        PageRow("key bars", "${bar.profiles.size + 1} · this pane's: $now", onKeyBars)

        Section("as you go")
        val mobile by settings.mobileView.flow.collectAsStateWithLifecycle()
        Toggle("mobile views, where a program has one", mobile) { settings.mobileView.set(it) }
        Body("Claude Code shows as its conversation, the terminal running underneath; the button by a pane's dots switches.")
        val feel by settings.haptics.flow.collectAsStateWithLifecycle()
        Toggle("haptics for swipes, switches and sends", feel) { settings.haptics.set(it) }
        val askPanes by settings.askClosingPane.flow.collectAsStateWithLifecycle()
        val askWindows by settings.askClosingWindow.flow.collectAsStateWithLifecycle()
        Toggle("ask before closing a pane", askPanes) { settings.askClosingPane.set(it) }
        Toggle("ask before closing a tab (or its last pane)", askWindows) { settings.askClosingWindow.set(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val pill by settings.statusBar.flow.collectAsStateWithLifecycle()
            Toggle("in the status bar while a program works or needs you (a Live Update)", pill) { settings.statusBar.set(it) }
        }

        Section("plugins")
        val yours = Programs.suppliedIds()
        Body("Built in: ${Programs.builtin.keys.joinToString(", ")}. " + if (yours.isEmpty()) "None of yours yet." else "Yours: ${yours.sorted().joinToString(", ")}.")
        Body("Yours go on the computer, a JSON file each in ~/.config/wallflower/plugins (docs/plugins.md in wallflower's source).")

        Section("about")
        Body(listOfNotNull("wallflower ${BuildConfig.VERSION_NAME} · protocol ${PROTOCOL_VERSION}", gateway).joinToString("\n"))
    }
}

/** The theme and the font. */
@Composable
fun LookScreen(settings: Preferences, onBack: () -> Unit) = Page("look", onBack) {
    Section("theme")
    val scheme by settings.scheme.flow.collectAsStateWithLifecycle()
    ThemePicker(scheme) { settings.scheme.set(it) }
    Body("The terminal takes the theme's colors too. Material You's come from the wallpaper, light or dark with the phone.")
    Section("font")
    val look by settings.look.flow.collectAsStateWithLifecycle()
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("terminal", look == Look.Terminal) { settings.look.set(Look.Terminal) }
        Chip("bricolage", look == Look.Grotesque, Grotesque) { settings.look.set(Look.Grotesque) }
    }
    Body("For wallflower's own words. The terminal, what you type for it, keys and values stay in the terminal font.")
}

/** The key bar's profiles, by the programs they're for, to open and change. */
@Composable
fun KeyBarsScreen(store: KeyBarStore, program: String?, programId: String?, onBack: () -> Unit, onOpen: (ProfileRef) -> Unit) {
    val bar by store.bar.collectAsStateWithLifecycle()
    val focused = bar.refFor(program, programId)
    Page("key bars", onBack) {
        Body(
            "Each pane gets the first profile listing its program." +
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

/** A row that opens a page: what it's about, what's set there now, and ›. */
@Composable
internal fun PageRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.bg1).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Palette.fg, style = Type.body)
            Text(summary, color = Palette.grey, style = Type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("›", color = Palette.grey, style = Type.title)
    }
}

/** The themes, two to a row, each in its own colors. */
@Composable
private fun ThemePicker(picked: Scheme, onPick: (Scheme) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in Scheme.entries.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in row) ThemeChoice(s.label, colorsOf(s), s == picked, Modifier.weight(1f)) { onPick(s) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A theme as a swatch: its page, its words and its accents. */
@Composable
private fun ThemeChoice(name: String, colors: Colors, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val haptics = haptics()
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .clip(shape)
            .background(colors.bg0)
            .border(if (selected) 2.dp else 1.dp, if (selected) Palette.orange else Palette.border, shape)
            .clickable {
                if (!selected) haptics.tick()
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(name, color = colors.fg, fontFamily = words, fontSize = wordSize(12.sp, 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (c in listOf(colors.red, colors.orange, colors.yellow, colors.green, colors.blue, colors.purple)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(c))
            }
        }
    }
}

private val Scheme.label
    get() = when (this) {
        Scheme.EverforestDarkHard -> "everforest"
        Scheme.GruvboxDark -> "gruvbox"
        Scheme.CatppuccinMocha -> "catppuccin mocha"
        Scheme.CatppuccinLatte -> "catppuccin latte"
        Scheme.TokyoNight -> "tokyo night"
        Scheme.Nord -> "nord"
        Scheme.MaterialYou -> "material you"
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
        Text(
            buildAnnotatedString {
                profile.keys.forEachIndexed { i, k ->
                    if (i > 0) append("  ")
                    append(faceOf(k))
                }
            },
            color = Palette.dim,
            fontFamily = Mono,
            fontSize = 12.sp,
            maxLines = 1,
            inlineContent = glyphs(Palette.dim),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    store: KeyBarStore,
    ref: ProfileRef,
    program: String?,
    programId: String?,
    onBack: () -> Unit,
    onOpen: (ProfileRef) -> Unit,
    onShortcuts: () -> Unit,
) {
    val bar by store.bar.collectAsStateWithLifecycle()
    val profile = when (ref) {
        ProfileRef.Default -> bar.default
        is ProfileRef.At -> bar.profiles.getOrNull(ref.index)
    }
    if (profile == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
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
                        store.update { it.copy(profiles = it.profiles + it.madeFor(program, programId)) }
                        onOpen(ProfileRef.At(index))
                    }
                }
            }
        }

        Section("keys")
        Body("Tap one to move or remove it; hold one to see what it sends.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            profile.keys.forEachIndexed { i, k -> KeyChoice(faceOf(k), KeyBar.nameOf(k), selected == i) { selected = if (selected == i) null else i } }
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

        Section("shortcuts")
        PageRow("shortcuts", shortcutsSummary(profile, shippedFor(bar, ref, profile, program, programId)), onShortcuts)

        Section("stop")
        Body("What the send button sends when the mobile view's box is empty.")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (keys in stops) KeyChoice(faceOf(keys), KeyBar.nameOf(keys), profile.stop == keys) { save(profile.copy(stop = keys)) }
        }

        Section("send button")
        Body("What it presses in the terminal, where the keyboard types straight into the pane.")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (keys in presses) KeyChoice(faceOf(keys), KeyBar.nameOf(keys), profile.sendKeys() == keys) { save(profile.copy(send = keys)) }
        }

        Section("return key")
        Body("What the keyboard's return key presses there. ctrl j and alt enter start a new line in apps that take several, as Claude Code does; a shell runs the line.")
        val program = Programs.of(profile.programs.firstOrNull().orEmpty())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (keys in presses) KeyChoice(faceOf(keys), KeyBar.nameOf(keys), profile.returnKeys(program) == keys) { save(profile.copy(returnKey = keys)) }
        }

        Section("clear")
        Body("What empties the app's line, so what's in the box replaces it (once per row of the line). none: the box adds to the line.")
        val usual = Programs.of(profile.programs.firstOrNull().orEmpty()).clearKeys
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("the program's", profile.clear == null || profile.clear == usual, font = words) { save(profile.copy(clear = null)) }
            for (keys in clears.filter { it != usual }) {
                if (keys.isEmpty()) {
                    Chip("none", profile.clear == keys, font = words) { save(profile.copy(clear = keys)) }
                } else {
                    KeyChoice(faceOf(keys), KeyBar.nameOf(keys), profile.clear == keys) { save(profile.copy(clear = keys)) }
                }
            }
        }

        Section("add")
        Body("Your own keys, like ctrl with a letter or several in a row, are shortcuts: add them from the shortcuts key.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (k in KeyBar.common) KeyChoice(faceOf(k), KeyBar.nameOf(k), false) { save(profile.copy(keys = profile.keys + k)) }
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

/** What can empty a row of a program's line: ctrl u (most), ctrl x ctrl k (zsh's kill-buffer), end then ctrl u (readline's from the end), or nothing. */
private val clears = listOf(listOf("C-u"), listOf("C-x", "C-k"), listOf("End", "C-u"), emptyList())

/** A full-screen page over the terminal, with a back arrow. */
@Composable
internal fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
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
