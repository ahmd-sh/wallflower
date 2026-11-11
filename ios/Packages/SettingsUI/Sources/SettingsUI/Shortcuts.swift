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
 * A profile's shortcuts, which held ctrl, or the shortcuts key, offers on
 * the bar: the user's own, to add, change, move and remove, then those the
 * program ships, each to hide or show again.
 */
public struct ShortcutsScreen: View {
    @Environment(\.palette) private var palette
    let store: KeyBarStore
    let ref: ProfileRef
    let program: String?
    let programId: String?
    let mux: Mux?
    let onBack: () -> Void
    let onOpen: (ProfileRef) -> Void
    /** Gone back already: the profile went from under the page. */
    @State private var left = false

    public init(store: KeyBarStore, ref: ProfileRef, program: String?, programId: String?, mux: Mux?, onBack: @escaping () -> Void, onOpen: @escaping (ProfileRef) -> Void) {
        self.store = store
        self.ref = ref
        self.program = program
        self.programId = programId
        self.mux = mux
        self.onBack = onBack
        self.onOpen = onOpen
    }

    public var body: some View {
        Group {
            if let profile = store.bar.profile(at: ref) {
                ShortcutsEditor(store: store, ref: ref, profile: profile, program: program, programId: programId, mux: mux, onBack: onBack, onOpen: onOpen)
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
    let mux: Mux?
    let onBack: () -> Void
    let onOpen: (ProfileRef) -> Void
    @State private var selected: Int?
    /** The one of theirs the fields below change, if not a new one. */
    @State private var editing: Int?
    @State private var label = ""
    @State private var sends = ""
    @State private var bound = false

    /** What a multiplexer that can't take keys through its bindings doesn't offer; unknown until connected. */
    private var bindings: Bool { mux.map { $0.can.contains(Mux.BINDINGS) } ?? true }

    var body: some View {
        let shipped = shippedFor(store.bar, ref, profile, program: program, programId: programId)
        let changing = editing.flatMap { profile.shortcuts.indices.contains($0) ? $0 : nil }
        let keys = KeyBar.keys(of: sends)
        Page(ref == .default ? "default shortcuts" : "\(profile.name) shortcuts", onBack: onBack) {
            Paragraph("Hold ctrl on the bar, or press the shortcuts key, then slide onto one and let go to send it; let go first, and they stay up to tap one.")
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
                        sends = k.keys.joined(separator: " ")
                        bound = k.bindings
                    }
                    WordButton("remove") {
                        save { if $0.shortcuts.indices.contains(i) { $0.shortcuts.remove(at: i) } }
                        selected = nil
                        if editing == i { done() } else if let e = editing, e > i { editing = e - 1 }
                    }
                }
            }

            Section(changing != nil ? "change" : "add")
            Paragraph(keyNamesHelp + " Text with Enter after it goes in place of what's in the program's line, as what you send does; without, it starts what you send.")
            Field("sends", value: $sends, hint: "/compact Enter")
            KeyNames(sends: $sends)
            Field(label.trimmingCharacters(in: .whitespaces).isEmpty && !keys.isEmpty ? "label (blank: \(KeyBar.label(of: keys)))" : "label", value: $label)
            if bindings {
                Toggle("through \(mux?.name ?? "the multiplexer")'s key bindings (C-j to change pane; prefix is your prefix)", on: bound) { bound = $0 }
            }
            HStack(spacing: 8) {
                WordButton(changing != nil ? "save" : "add shortcut", primary: true) {
                    guard !keys.isEmpty else { return }
                    let named = label.trimmingCharacters(in: .whitespaces)
                    let key = BarKey(label: named.isEmpty ? KeyBar.label(of: keys) : named, keys: keys, bindings: bound && bindings)
                    save {
                        if let c = changing, $0.shortcuts.indices.contains(c) { $0.shortcuts[c] = key } else { $0.shortcuts.append(key) }
                    }
                    done()
                }
                if changing != nil { WordButton("cancel") { done() } }
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
        sends = ""
        bound = false
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
