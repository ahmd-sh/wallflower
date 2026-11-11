package sh.ahmd.wallflower.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import sh.ahmd.wallflower.protocol.Mux

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
 * A profile's shortcuts, which held ctrl, or the shortcuts key, offers on
 * the bar: the user's own, to add, change, move and remove, then those the
 * program ships, each to hide or show again.
 */
@Composable
fun ShortcutsScreen(store: KeyBarStore, ref: ProfileRef, program: String?, programId: String?, mux: Mux?, onBack: () -> Unit, onOpen: (ProfileRef) -> Unit) {
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
    val shipped = shippedFor(bar, ref, profile, program, programId)
    var selected by remember(ref) { mutableStateOf<Int?>(null) }
    var editing by remember(ref) { mutableStateOf<Int?>(null) } // the one of theirs the fields below change, if not a new one
    var label by remember(ref) { mutableStateOf("") }
    var sends by remember(ref) { mutableStateOf("") }
    var bound by remember(ref) { mutableStateOf(false) }
    fun done() {
        editing = null
        label = ""
        sends = ""
        bound = false
    }

    Page(if (ref == ProfileRef.Default) "default shortcuts" else "${profile.name} shortcuts", onBack) {
        Body("Hold ctrl on the bar, or press the shortcuts key, then slide onto one and let go to send it; let go first, and they stay up to tap one.")
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

        Section("yours")
        if (profile.shortcuts.isEmpty()) Body("None yet. They come after the program's.")
        profile.shortcuts.forEachIndexed { i, k -> ShortcutRow(k, selected = selected == i) { selected = if (selected == i) null else i } }
        selected?.takeIf { it < profile.shortcuts.size }?.let { i ->
            fun move(to: Int) {
                val keys = profile.shortcuts.toMutableList()
                keys.add(to, keys.removeAt(i))
                save(profile.copy(shortcuts = keys))
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
                    sends = k.keys.joinToString(" ")
                    bound = k.bindings
                }
                Button("remove") {
                    save(profile.copy(shortcuts = profile.shortcuts.filterIndexed { j, _ -> j != i }))
                    selected = null
                    if (editing == i) done() else editing = editing?.let { if (it > i) it - 1 else it }
                }
            }
        }

        val changing = editing?.takeIf { it < profile.shortcuts.size }
        Section(if (changing != null) "change" else "add")
        Body("$KEY_NAMES Text with Enter after it goes in place of what's in the program's line, as what you send does; without, it starts what you send.")
        val keys = KeyBar.keysOf(sends)
        Field("sends", sends, hint = "/compact Enter") { sends = it }
        KeyNames(sends) { sends = it }
        Field(if (label.isBlank() && keys.isNotEmpty()) "label (blank: ${KeyBar.labelOf(keys)})" else "label", label) { label = it }
        if (bindings) Toggle("through ${mux?.name ?: "the multiplexer"}'s key bindings (C-j to change pane; prefix is your prefix)", bound) { bound = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(if (changing != null) "save" else "add shortcut", primary = true) {
                if (keys.isEmpty()) return@Button
                val key = BarKey(label.trim().ifBlank { KeyBar.labelOf(keys) }, keys, bindings = bound && bindings)
                save(profile.copy(shortcuts = if (changing != null) profile.shortcuts.toMutableList().also { it[changing] = key } else profile.shortcuts + key))
                done()
            }
            if (changing != null) Button("cancel") { done() }
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
