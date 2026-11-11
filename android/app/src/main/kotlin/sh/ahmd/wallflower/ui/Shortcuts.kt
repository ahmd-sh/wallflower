package sh.ahmd.wallflower.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.ahmd.wallflower.BarKey
import sh.ahmd.wallflower.KeyBar
import sh.ahmd.wallflower.KeyBarStore
import sh.ahmd.wallflower.KeyProfile
import sh.ahmd.wallflower.program.Programs

/**
 * The shortcuts the programs [ref]'s profile is for ship: those it lists,
 * and the focused pane's ([programId]) if the profile is its, which a
 * profile listing commands (bash) doesn't name.
 */
internal fun shippedFor(bar: KeyBar, ref: ProfileRef, profile: KeyProfile, program: String?, programId: String?): List<BarKey> {
    val focused = programId?.takeIf { bar.refFor(program, it) == ref }
    val ids = (if (ref is ProfileRef.At) profile.programs else emptyList()) + listOfNotNull(focused)
    return ids.flatMap { Programs.of(it).shortcuts }.map(KeyBar::shortcut).distinct()
}

/** A profile's shortcuts in a line: how many there are, of them the user's, and how many of the program's are hidden. */
internal fun shortcutsSummary(profile: KeyProfile, shipped: List<BarKey>): String {
    val hidden = shipped.count { it.label in profile.hidden }
    val shown = shipped.size - hidden + profile.shortcuts.size
    if (shown == 0 && hidden == 0) return "none yet"
    return listOfNotNull(
        "$shown",
        profile.shortcuts.size.takeIf { it > 0 }?.let { "$it yours" },
        hidden.takeIf { it > 0 }?.let { "$it hidden" },
    ).joinToString(" · ")
}

/**
 * A profile's shortcuts, which the shortcuts key offers on the bar: one to
 * add (or one of theirs to change), the user's own, to move and remove, then
 * those the program ships, each to hide or show again.
 */
@Composable
fun ShortcutsScreen(store: KeyBarStore, ref: ProfileRef, program: String?, programId: String?, onBack: () -> Unit, onOpen: (ProfileRef) -> Unit) {
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
    val shipped = shippedFor(bar, ref, profile, program, programId)
    var selected by remember(ref) { mutableStateOf<Int?>(null) }
    var editing by remember(ref) { mutableStateOf<Int?>(null) } // the one of theirs being changed, if not a new one
    var label by remember(ref) { mutableStateOf("") }
    var keys by remember(ref) { mutableStateOf(emptyList<String>()) }
    fun done() {
        editing = null
        label = ""
        keys = emptyList()
    }

    Page(if (ref == ProfileRef.Default) "default shortcuts" else "${profile.name} shortcuts", onBack) {
        Body("Press the shortcuts key on the bar, then slide onto one and let go to send it; let go first, and they stay up to tap one.")
        if (ref == ProfileRef.Default) {
            Body("For programs without a profile of their own.")
            if (!program.isNullOrEmpty() && bar.refFor(program, programId) == ProfileRef.Default) {
                Button("make a profile for $program", primary = true) {
                    val index = bar.profiles.size
                    store.update { it.copy(profiles = it.profiles + it.madeFor(program, programId)) }
                    onOpen(ProfileRef.At(index))
                }
            }
        }

        val changing = editing?.takeIf { it < profile.shortcuts.size }
        Section(if (changing != null) "change" else "add")
        KeysBuilder(keys) { keys = it }
        Field(if (label.isBlank() && keys.isNotEmpty()) "name (blank: ${KeyBar.labelOf(keys)})" else "name", label) { label = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(if (changing != null) "save" else "add shortcut", primary = true) {
                if (keys.isEmpty()) return@Button
                // As if typed on a keyboard: a multiplexer's own keys (the user's prefix, then a key) work too.
                val key = BarKey(label.trim().ifBlank { KeyBar.labelOf(keys) }, keys, bindings = true)
                save(profile.copy(shortcuts = if (changing != null) profile.shortcuts.toMutableList().also { it[changing] = key } else profile.shortcuts + key))
                done()
            }
            if (changing != null) Button("cancel") { done() }
        }

        Section("yours")
        if (profile.shortcuts.isEmpty()) Body("None yet. They come after the program's.")
        profile.shortcuts.forEachIndexed { i, k -> ShortcutRow(k, selected = selected == i) { selected = if (selected == i) null else i } }
        selected?.takeIf { it < profile.shortcuts.size }?.let { i ->
            fun move(to: Int) {
                val all = profile.shortcuts.toMutableList()
                all.add(to, all.removeAt(i))
                save(profile.copy(shortcuts = all))
                selected = to
                if (editing == i) editing = to else if (editing == to) editing = i
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("earlier") { if (i > 0) move(i - 1) }
                Button("later") { if (i < profile.shortcuts.lastIndex) move(i + 1) }
                Button("change") {
                    val k = profile.shortcuts[i]
                    editing = i
                    label = if (k.ownLabel) "" else k.label
                    keys = k.keys
                }
                Button("remove") {
                    save(profile.copy(shortcuts = profile.shortcuts.filterIndexed { j, _ -> j != i }))
                    selected = null
                    if (editing == i) done() else editing = editing?.let { if (it > i) it - 1 else it }
                }
            }
        }

        if (shipped.isNotEmpty()) {
            Section("the program's")
            Body("Tap one to hide it from the bar, or to show it again.")
            for (k in shipped) {
                val hidden = k.label in profile.hidden
                ShortcutRow(k, hidden = hidden) { save(profile.copy(hidden = if (hidden) profile.hidden - k.label else profile.hidden + k.label)) }
            }
        }
    }
}

/**
 * What a shortcut sends, [keys] by wallflower's names, made by tapping keys
 * and typing: a modifier (ctrl, alt, shift) waits for the next key, tapped
 * or typed, and goes with it; what's typed otherwise is text, typed as it is.
 * The field edits the text at the end; a key tapped after it ends it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeysBuilder(keys: List<String>, onChange: (List<String>) -> Unit) {
    var held by remember { mutableStateOf(emptyList<String>()) }
    val text = keys.lastOrNull()?.takeIf { !KeyBar.isKey(it) }.orEmpty()
    fun withHeld(key: String) = held.foldRight(key) { m, k -> KeyBar.held(m, k) }
    fun add(key: String) {
        onChange(keys + withHeld(key))
        held = emptyList()
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.bg1).padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val face = faceOf(keys + held)
        Text(
            if (keys.isEmpty() && held.isEmpty()) AnnotatedString("sends nothing yet") else face,
            color = if (keys.isEmpty() && held.isEmpty()) Palette.dim else Palette.fg,
            fontFamily = Mono,
            fontSize = 14.sp,
            inlineContent = glyphs(Palette.fg),
            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
        )
        if (keys.isNotEmpty() || held.isNotEmpty()) {
            Box(Modifier.clip(CircleShape).clickable { if (held.isNotEmpty()) held = held.dropLast(1) else onChange(keys.dropLast(1)) }.padding(10.dp)) {
                GlyphIcon(Glyph.Close, Palette.grey, size = 14.dp)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (m in KeyBar.modifiers) Chip(KeyBar.labelOf(listOf(m)), m in held) { held = if (m in held) held - m else held + m }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (k in KeyBar.named) KeyChoice(faceOf(listOf(k)), KeyBar.nameOf(listOf(k)), false) { add(k) }
    }
    Field("type", text, hint = "/compact") { typed ->
        val before = if (text.isEmpty()) keys else keys.dropLast(1)
        if (held.isNotEmpty() && typed.length == text.length + 1 && typed.startsWith(text)) {
            onChange(before + listOfNotNull(text.ifEmpty { null }) + withHeld(typed.takeLast(1)))
            held = emptyList()
        } else {
            onChange(before + listOfNotNull(typed.ifEmpty { null }))
        }
    }
    Body("Text with ⏎ after it goes in place of what's in the program's line and runs; text alone starts what you send. After ctrl, alt or shift, a letter typed goes with it.")
}

/** A shortcut as a row: how it shows on the bar, then what it sends, in words; dimmed if [hidden]. */
@Composable
private fun ShortcutRow(key: BarKey, selected: Boolean = false, hidden: Boolean = false, onClick: () -> Unit) {
    val haptics = haptics()
    val shape = RoundedCornerShape(12.dp)
    val ink = if (hidden) Palette.dim else Palette.fg
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.bg1)
            .border(1.dp, if (selected) Palette.orange else Color.Transparent, shape)
            .clickable {
                haptics.tick()
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(faceOf(key), color = ink, fontFamily = Mono, fontSize = 14.sp, maxLines = 1, inlineContent = glyphs(ink))
        Text(
            KeyBar.nameOf(key),
            color = if (hidden) Palette.dim else Palette.grey,
            style = Type.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        if (hidden) Text("hidden", color = Palette.dim, style = Type.caption)
    }
}
