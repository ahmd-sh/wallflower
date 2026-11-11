import ScreenReading
import SwiftUI
import WallflowerDesign
import Wire

/**
 * A profile of the key bar to change: its name and programs (the default's
 * for everything else), its keys to move, remove and add (custom ones too),
 * its shortcuts (a page of their own), and what stop, the send button, the
 * return key and clear press.
 */
public struct ProfileScreen: View {
    @Environment(\.palette) private var palette
    let store: KeyBarStore
    let ref: ProfileRef
    let program: String?
    let programId: String?
    let mux: Mux?
    let onBack: () -> Void
    let onShortcuts: () -> Void
    let onOpen: (ProfileRef) -> Void
    /** Gone back already: the profile went from under the page. */
    @State private var left = false
    /** The profile as it was when deleted, shown as the page goes. */
    @State private var deleted: KeyProfile?

    public init(
        store: KeyBarStore, ref: ProfileRef, program: String?, programId: String?, mux: Mux?,
        onBack: @escaping () -> Void, onShortcuts: @escaping () -> Void, onOpen: @escaping (ProfileRef) -> Void
    ) {
        self.store = store
        self.ref = ref
        self.program = program
        self.programId = programId
        self.mux = mux
        self.onBack = onBack
        self.onShortcuts = onShortcuts
        self.onOpen = onOpen
    }

    public var body: some View {
        Group {
            if let profile = deleted ?? store.bar.profile(at: ref) {
                ProfileEditor(store: store, ref: ref, profile: profile, program: program, programId: programId, mux: mux, onBack: onBack, onShortcuts: onShortcuts, onOpen: onOpen) {
                    deleted = profile
                    onBack()
                }
                .disabled(deleted != nil)
                .id(ref)
            } else {
                palette.bg0.ignoresSafeArea().onAppear {
                    guard !left else { return }
                    left = true
                    onBack()
                }
            }
        }
        .onChange(of: ref) {
            left = false
            deleted = nil
        }
    }
}

private struct ProfileEditor: View {
    @Environment(\.isEnabled) private var isEnabled
    let store: KeyBarStore
    let ref: ProfileRef
    let profile: KeyProfile
    let program: String?
    let programId: String?
    let mux: Mux?
    let onBack: () -> Void
    let onShortcuts: () -> Void
    let onOpen: (ProfileRef) -> Void
    let onDelete: () -> Void
    @State private var selected: Int?
    @State private var dragging = false
    @State private var name: String
    @State private var programs: String
    @State private var clear: String
    @State private var label = ""
    @State private var sends = ""
    @State private var bound = false

    init(
        store: KeyBarStore, ref: ProfileRef, profile: KeyProfile, program: String?, programId: String?, mux: Mux?,
        onBack: @escaping () -> Void, onShortcuts: @escaping () -> Void, onOpen: @escaping (ProfileRef) -> Void, onDelete: @escaping () -> Void
    ) {
        self.store = store
        self.ref = ref
        self.profile = profile
        self.program = program
        self.programId = programId
        self.mux = mux
        self.onBack = onBack
        self.onShortcuts = onShortcuts
        self.onDelete = onDelete
        self.onOpen = onOpen
        _name = State(initialValue: profile.name)
        _programs = State(initialValue: profile.programs.joined(separator: " "))
        _clear = State(initialValue: (profile.clear ?? Programs.of(profile.programs.first).clearKeys).joined(separator: " "))
    }

    /** What a multiplexer that can't take keys through its bindings doesn't offer; unknown until connected. */
    private var bindings: Bool { mux.map { $0.can.contains(Mux.BINDINGS) } ?? true }

    var body: some View {
        Page(ref == .default ? "default keys" : profile.name, onBack: onBack, scrolls: !dragging) {
            switch ref {
            case .at:
                Field("name", value: Binding(get: { name }, set: { v in
                    name = v
                    save { $0.name = v }
                }))
                Field("programs", value: Binding(get: { programs }, set: { v in
                    programs = v
                    save { $0.programs = v.split { $0 == " " || $0 == "," }.map(String.init).filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty } }
                }))
                Paragraph("Space-separated, as panes report them, or as wallflower names their program (shell)." + (program.nonEmpty.map { " The focused pane runs \($0)." } ?? ""))
            case .default:
                Paragraph("For programs without a profile of their own.")
                if let program = program.nonEmpty, store.bar.refFor(command: program, program: programId) == .default {
                    WordButton("make a profile for \(program)", primary: true) {
                        let index = store.bar.profiles.count
                        store.update { b in
                            var b = b
                            b.profiles.append(KeyProfile(name: program, programs: [program], keys: profile.keys, stop: profile.stop))
                            return b
                        }
                        onOpen(.at(index))
                    }
                }
            }

            Section("keys")
            Paragraph("Tap one to move or remove it; hold one to see what it sends.")
            KeyChoices(choices: keyChoices, move: move, dragging: $dragging)
            if let i = selected, i < profile.keys.count {
                HStack(spacing: 8) {
                    WordButton("‹ earlier") { if i > 0 { move(i, i - 1) } }
                    WordButton("later ›") { if i < profile.keys.count - 1 { move(i, i + 1) } }
                    WordButton("remove") {
                        save { if $0.keys.indices.contains(i) { $0.keys.remove(at: i) } }
                        selected = nil
                    }
                }
            }

            Section("shortcuts")
            PageRow("shortcuts", shortcutsSummary(profile, shipped: shippedFor(store.bar, ref, profile, program: program, programId: programId)), action: onShortcuts)

            Section("stop")
            Paragraph("What the send button sends when the mobile view's box is empty.")
            KeyChoices(choices: stops.map { keys in KeyChoice(keys: keys, selected: profile.stop == keys) { save { $0.stop = keys } } })

            Section("send button")
            Paragraph("What it presses in the terminal, where the keyboard types straight into the pane.")
            KeyChoices(choices: presses.map { keys in KeyChoice(keys: keys, selected: profile.sendKeys() == keys) { save { $0.send = keys } } })

            Section("return key")
            Paragraph("What the keyboard's return key presses there. ctrl j and alt enter start a new line in apps that take several, as Claude Code does; a shell runs the line.")
            let runs = Programs.of(profile.programs.first)
            KeyChoices(choices: presses.map { keys in KeyChoice(keys: keys, selected: profile.returnKeys(runs) == keys) { save { $0.returnKey = keys } } })

            Section("clear")
            Paragraph("What empties the app's line, so what's in the box replaces it (sent once per row of the line). Empty: the box adds to the line.")
            Field("keys", value: Binding(get: { clear }, set: { v in
                clear = v
                save { $0.clear = v.split(separator: " ").map(String.init) }
            }))

            Section("add")
            KeyChoices(choices: KeyBar.common.filter { bindings || !$0.bindings }.map { k in KeyChoice(k, id: k, selected: false) { save { $0.keys.append(k) } } })

            Section("custom")
            Paragraph(keyNamesHelp + " Tap one to add it.")
            let keys = KeyBar.keys(of: sends)
            Field("sends", value: $sends)
            KeyNames(sends: $sends)
            Field(label.trimmingCharacters(in: .whitespaces).isEmpty && !keys.isEmpty ? "label (blank: \(KeyBar.label(of: keys)))" : "label", value: $label)
            if bindings {
                Toggle("through \(mux?.name ?? "the multiplexer")'s key bindings (C-j to change pane; prefix is your prefix)", on: bound) { bound = $0 }
            }
            WordButton("add key") {
                guard !keys.isEmpty else { return }
                let named = label.trimmingCharacters(in: .whitespaces).isEmpty ? KeyBar.label(of: keys) : label
                save { $0.keys.append(BarKey(label: named, keys: keys, bindings: bound && bindings)) }
                label = ""
                sends = ""
            }

            if case .at(let i) = ref {
                Spacer().frame(height: 8)
                WordButton("delete profile") {
                    store.update { b in
                        var b = b
                        if b.profiles.indices.contains(i) { b.profiles.remove(at: i) }
                        return b
                    }
                    onDelete()
                }
            }
        }
    }

    /** The profile's keys, each by itself and which of its kind it is, so moving one moves it. */
    private var keyChoices: [KeyChoice] {
        var seen: [BarKey: Int] = [:]
        return profile.keys.enumerated().map { i, k in
            let nth = seen[k, default: 0]
            seen[k] = nth + 1
            return KeyChoice(k, id: KeyID(key: k, nth: nth), selected: selected == i) { selected = selected == i ? nil : i }
        }
    }

    /** Moves key [from] to [to], the selection with it. */
    private func move(_ from: Int, _ to: Int) {
        save {
            let k = $0.keys.remove(at: from)
            $0.keys.insert(k, at: to)
        }
        if let s = selected {
            if s == from {
                selected = to
            } else if from < s && s <= to {
                selected = s - 1
            } else if to <= s && s < from {
                selected = s + 1
            }
        }
    }

    private func save(_ change: (inout KeyProfile) -> Void) {
        guard isEnabled else { return } // deleted: what shifted into its place isn't this one
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

private struct KeyID: Hashable {
    let key: BarKey
    let nth: Int
}

private let stops = ["C-c", "Escape", "C-d", "q"].map { [$0] }
private let presses = ["Enter", "C-j", "M-Enter"].map { [$0] }

/** How a custom key's keys are written. */
let keyNamesHelp = "Key names, space-separated; the words between them are typed as they are (Escape :w Enter). C- is ctrl, M- alt and S- shift, "
    + "joined to the key they're held with (C-a, M-Enter, S-Up). BTab is shift tab, BSpace backspace, DC delete, IC insert; F1 to F12 work too."

/** wallflower's names (tmux's) for what a custom key sends, beyond text, to tap onto the end of [sends]: joined to a modifier written last (C-), else a word of its own. */
struct KeyNames: View {
    @Binding var sends: String

    var body: some View {
        Flow(spacing: 6) {
            ForEach(names, id: \.self) { n in
                Chip(n, selected: false) {
                    let joins = sends.isEmpty || sends.hasSuffix(" ") || isHeld(sends.components(separatedBy: " ").last ?? "")
                    sends = joins ? sends + n : "\(sends) \(n)"
                }
            }
        }
    }
}

private let names = [
    "C-", "M-", "S-", "Enter", "Escape", "Tab", "BTab", "BSpace", "Space",
    "Up", "Down", "Left", "Right", "Home", "End", "PageUp", "PageDown", "DC", "IC",
]

/** Whether [s] is only modifiers held (C-, C-M-), for a key to join. */
private func isHeld(_ s: String) -> Bool {
    let c = Array(s)
    guard !c.isEmpty, c.count % 2 == 0 else { return false }
    return stride(from: 0, to: c.count, by: 2).allSatisfy { "CMS".contains(c[$0]) && c[$0 + 1] == "-" }
}
