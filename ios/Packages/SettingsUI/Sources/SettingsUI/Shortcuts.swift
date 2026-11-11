import ScreenReading
import SwiftUI
import WallflowerDesign
import Wire

/**
 * The shortcuts the programs [ref]'s profile is for ship: those it lists,
 * and the focused pane's ([programId]) if the profile is its, which a
 * profile listing commands (bash) doesn't name.
 */
func shippedFor(_ bar: KeyBar, _ ref: ProfileRef, _ profile: KeyProfile, program: String?, programId: String?) -> [BarKey] {
    let focused = programId.flatMap { bar.refFor(command: program, program: $0) == ref ? $0 : nil }
    var ids: [String] = []
    if case .at = ref { ids = profile.programs }
    if let focused { ids.append(focused) }
    var out: [BarKey] = []
    for k in ids.flatMap({ Programs.of($0).shortcuts }).map(KeyBar.shortcut) where !out.contains(k) { out.append(k) }
    return out
}

/** A profile's shortcuts in a line: how many there are, of them the user's, and how many of the program's are hidden. */
func shortcutsSummary(_ profile: KeyProfile, shipped: [BarKey]) -> String {
    let hidden = shipped.filter { profile.hidden.contains($0.label) }.count
    let shown = shipped.count - hidden + profile.shortcuts.count
    if shown == 0 && hidden == 0 { return "none yet" }
    return [
        "\(shown)",
        profile.shortcuts.isEmpty ? nil : "\(profile.shortcuts.count) yours",
        hidden == 0 ? nil : "\(hidden) hidden",
    ].compactMap { $0 }.joined(separator: " · ")
}

/**
 * A profile's shortcuts, which the shortcuts key offers on the bar: one to
 * add (or one of theirs to change), the user's own, to move and remove, then
 * those the program ships, each to hide or show again.
 */
public struct ShortcutsScreen: View {
    @Environment(\.palette) private var palette
    let store: KeyBarStore
    let ref: ProfileRef
    let program: String?
    let programId: String?
    let onBack: () -> Void
    let onOpen: (ProfileRef) -> Void
    /** Gone back already: the profile went from under the page. */
    @State private var left = false

    public init(store: KeyBarStore, ref: ProfileRef, program: String?, programId: String?, onBack: @escaping () -> Void, onOpen: @escaping (ProfileRef) -> Void) {
        self.store = store
        self.ref = ref
        self.program = program
        self.programId = programId
        self.onBack = onBack
        self.onOpen = onOpen
    }

    public var body: some View {
        Group {
            if let profile = store.bar.profile(at: ref) {
                ShortcutsEditor(store: store, ref: ref, profile: profile, program: program, programId: programId, onBack: onBack, onOpen: onOpen)
                    .id(ref)
            } else {
                palette.bg0.ignoresSafeArea().onAppear {
                    guard !left else { return }
                    left = true
                    onBack()
                }
            }
        }
        .onChange(of: ref) { left = false }
    }
}

private struct ShortcutsEditor: View {
    let store: KeyBarStore
    let ref: ProfileRef
    let profile: KeyProfile
    let program: String?
    let programId: String?
    let onBack: () -> Void
    let onOpen: (ProfileRef) -> Void
    @State private var selected: Int?
    /** The one of theirs being changed, if not a new one. */
    @State private var editing: Int?
    @State private var label = ""
    @State private var keys: [String] = []

    var body: some View {
        let shipped = shippedFor(store.bar, ref, profile, program: program, programId: programId)
        let changing = editing.flatMap { profile.shortcuts.indices.contains($0) ? $0 : nil }
        Page(ref == .default ? "default shortcuts" : "\(profile.name) shortcuts", onBack: onBack) {
            Paragraph("Press the shortcuts key on the bar, then slide onto one and let go to send it; let go first, and they stay up to tap one.")
            if ref == .default {
                Paragraph("For programs without a profile of their own.")
                if let program = program.nonEmpty, store.bar.refFor(command: program, program: programId) == .default {
                    WordButton("make a profile for \(program)", primary: true) {
                        let index = store.bar.profiles.count
                        store.update { b in
                            var b = b
                            b.profiles.append(b.madeFor(command: program, program: programId))
                            return b
                        }
                        onOpen(.at(index))
                    }
                }
            }

            Section(changing != nil ? "change" : "add")
            KeysBuilder(keys: $keys)
            Field(label.trimmingCharacters(in: .whitespaces).isEmpty && !keys.isEmpty ? "name (blank: \(KeyBar.label(of: keys)))" : "name", value: $label)
            HStack(spacing: 8) {
                WordButton(changing != nil ? "save" : "add shortcut", primary: true) {
                    guard !keys.isEmpty else { return }
                    let named = label.trimmingCharacters(in: .whitespaces)
                    // As if typed on a keyboard: a multiplexer's own keys (the user's prefix, then a key) work too.
                    let key = BarKey(label: named.isEmpty ? KeyBar.label(of: keys) : named, keys: keys, bindings: true)
                    save {
                        if let c = changing, $0.shortcuts.indices.contains(c) { $0.shortcuts[c] = key } else { $0.shortcuts.append(key) }
                    }
                    done()
                }
                if changing != nil { WordButton("cancel") { done() } }
            }

            Section("yours")
            if profile.shortcuts.isEmpty { Paragraph("None yet. They come after the program's.") }
            ForEach(Array(profile.shortcuts.enumerated()), id: \.offset) { i, k in
                ShortcutRow(key: k, selected: selected == i) { selected = selected == i ? nil : i }
            }
            if let i = selected, i < profile.shortcuts.count {
                HStack(spacing: 8) {
                    WordButton("earlier") { if i > 0 { move(i, i - 1) } }
                    WordButton("later") { if i < profile.shortcuts.count - 1 { move(i, i + 1) } }
                    WordButton("change") {
                        let k = profile.shortcuts[i]
                        editing = i
                        label = k.hasOwnLabel ? "" : k.label
                        keys = k.keys
                    }
                    WordButton("remove") {
                        save { if $0.shortcuts.indices.contains(i) { $0.shortcuts.remove(at: i) } }
                        selected = nil
                        if editing == i { done() } else if let e = editing, e > i { editing = e - 1 }
                    }
                }
            }

            if !shipped.isEmpty {
                Section("the program's")
                Paragraph("Tap one to hide it from the bar, or to show it again.")
                ForEach(shipped, id: \.self) { k in
                    let hidden = profile.hidden.contains(k.label)
                    ShortcutRow(key: k, hidden: hidden) {
                        save { p in
                            if hidden { p.hidden.removeAll { $0 == k.label } } else { p.hidden.append(k.label) }
                        }
                    }
                }
            }
        }
    }

    private func done() {
        editing = nil
        label = ""
        keys = []
    }

    /** Moves shortcut [from] to [to], the selection (and what's being changed) with it. */
    private func move(_ from: Int, _ to: Int) {
        save {
            let k = $0.shortcuts.remove(at: from)
            $0.shortcuts.insert(k, at: to)
        }
        selected = to
        if editing == from { editing = to } else if editing == to { editing = from }
    }

    private func save(_ change: (inout KeyProfile) -> Void) {
        store.update { b in
            var b = b
            switch ref {
            case .default: change(&b.default)
            case .at(let i):
                guard b.profiles.indices.contains(i) else { return b }
                change(&b.profiles[i])
            }
            return b
        }
    }
}

/**
 * What a shortcut sends, [keys] by wallflower's names, made by tapping keys
 * and typing: a modifier (ctrl, alt, shift) waits for the next key, tapped
 * or typed, and goes with it; what's typed otherwise is text, typed as it is.
 * The field edits the text at the end; a key tapped after it ends it.
 */
struct KeysBuilder: View {
    @Environment(\.palette) private var palette
    @Binding var keys: [String]
    @State private var held: [String] = []

    private var text: String { keys.last.flatMap { KeyBar.isKey($0) ? nil : $0 } ?? "" }

    var body: some View {
        let empty = keys.isEmpty && held.isEmpty
        HStack(spacing: 0) {
            Group {
                if empty {
                    Text("sends nothing yet").font(Fonts.mono(14)).foregroundColor(palette.dim)
                } else {
                    faceText(faceOf(keys + held), size: 14, color: palette.fg)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 6)
            if !empty {
                Button {
                    if held.isEmpty { keys.removeLast() } else { held.removeLast() }
                } label: {
                    GlyphIcon(.close, palette.grey, size: 14).padding(10).contentShape(Circle())
                }
                .buttonStyle(Pressable())
            }
        }
        .padding(.leading, 14)
        .padding(.trailing, 4)
        .padding(.vertical, 6)
        .background(RoundedRectangle(cornerRadius: 12).fill(palette.bg1))
        HStack(spacing: 6) {
            ForEach(KeyBar.modifiers, id: \.self) { m in
                Chip(KeyBar.label(of: [m]), selected: held.contains(m)) { if held.contains(m) { held.removeAll { $0 == m } } else { held.append(m) } }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        KeyChoices(choices: KeyBar.named.map { k in KeyChoice(keys: [k], selected: false) { add(k) } })
        Field("type", value: Binding(get: { text }, set: type), hint: "/compact")
        Paragraph("Text with ⏎ after it goes in place of what's in the program's line and runs; text alone starts what you send. After ctrl, alt or shift, a letter typed goes with it.")
    }

    private func withHeld(_ key: String) -> String { held.reversed().reduce(key) { k, m in KeyBar.held(m, k) } }

    private func add(_ key: String) {
        keys.append(withHeld(key))
        held = []
    }

    /** What's typed: the text at the end, or after a modifier, the letter that goes with it. */
    private func type(_ typed: String) {
        let before = text.isEmpty ? keys : Array(keys.dropLast())
        if !held.isEmpty, typed.count == text.count + 1, typed.hasPrefix(text), let c = typed.last {
            keys = before + (text.isEmpty ? [] : [text]) + [withHeld(String(c))]
            held = []
        } else {
            keys = before + (typed.isEmpty ? [] : [typed])
        }
    }
}

/** A shortcut as a row: how it shows on the bar, then what it sends, in words; dimmed if [hidden]. */
private struct ShortcutRow: View {
    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics
    let key: BarKey
    var selected = false
    var hidden = false
    let action: () -> Void

    var body: some View {
        Button {
            haptics.tick()
            action()
        } label: {
            HStack(spacing: 12) {
                faceText(faceOf(key), size: 14, color: hidden ? palette.dim : palette.fg).lineLimit(1)
                Text(KeyBar.name(of: key))
                    .words(.caption)
                    .foregroundColor(hidden ? palette.dim : palette.grey)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if hidden { Text("hidden").words(.caption).foregroundColor(palette.dim) }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: 12).fill(palette.bg1))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(selected ? palette.orange : .clear, lineWidth: 1))
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(Pressable())
    }
}
