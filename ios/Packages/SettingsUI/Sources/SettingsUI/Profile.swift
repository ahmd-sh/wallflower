import ScreenReading
import SwiftUI
import WallflowerDesign
import Wire

/**
 * A profile of the key bar to change: its name and programs (the default's
 * for everything else), its keys to move, remove and add, its shortcuts (a
 * page of their own), and what stop, the send button, the return key and
 * clear press.
 */
public struct ProfileScreen: View {
    @Environment(\.palette) private var palette
    let store: KeyBarStore
    let ref: ProfileRef
    let program: String?
    let programId: String?
    let onBack: () -> Void
    let onShortcuts: () -> Void
    let onOpen: (ProfileRef) -> Void
    /** Gone back already: the profile went from under the page. */
    @State private var left = false
    /** The profile as it was when deleted, shown as the page goes. */
    @State private var deleted: KeyProfile?

    public init(
        store: KeyBarStore, ref: ProfileRef, program: String?, programId: String?,
        onBack: @escaping () -> Void, onShortcuts: @escaping () -> Void, onOpen: @escaping (ProfileRef) -> Void
    ) {
        self.store = store
        self.ref = ref
        self.program = program
        self.programId = programId
        self.onBack = onBack
        self.onShortcuts = onShortcuts
        self.onOpen = onOpen
    }

    public var body: some View {
        Group {
            if let profile = deleted ?? store.bar.profile(at: ref) {
                ProfileEditor(store: store, ref: ref, profile: profile, program: program, programId: programId, onBack: onBack, onShortcuts: onShortcuts, onOpen: onOpen) {
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
    @Environment(\.pressing) private var pressing
    let store: KeyBarStore
    let ref: ProfileRef
    let profile: KeyProfile
    let program: String?
    let programId: String?
    let onBack: () -> Void
    let onShortcuts: () -> Void
    let onOpen: (ProfileRef) -> Void
    let onDelete: () -> Void
    @State private var selected: Int?
    @State private var dragging = false
    @State private var name: String
    @State private var programs: String

    init(
        store: KeyBarStore, ref: ProfileRef, profile: KeyProfile, program: String?, programId: String?,
        onBack: @escaping () -> Void, onShortcuts: @escaping () -> Void, onOpen: @escaping (ProfileRef) -> Void, onDelete: @escaping () -> Void
    ) {
        self.store = store
        self.ref = ref
        self.profile = profile
        self.program = program
        self.programId = programId
        self.onBack = onBack
        self.onShortcuts = onShortcuts
        self.onDelete = onDelete
        self.onOpen = onOpen
        _name = State(initialValue: profile.name)
        _programs = State(initialValue: profile.programs.joined(separator: " "))
    }

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
                            b.profiles.append(b.madeFor(command: program, program: programId))
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
            Paragraph("What empties the app's line, so what's in the box replaces it (once per row of the line). none: the box adds to the line.")
            let usual = Programs.of(profile.programs.first).clearKeys
            KeyChoices(choices: [KeyChoice(id: "program", face: [.text("the program's")], name: usual.isEmpty ? "nothing" : KeyBar.name(of: usual), selected: profile.clear == nil || profile.clear == usual) { save { $0.clear = nil } }]
                + clears.filter { $0 != usual }.map { keys in
                    keys.isEmpty
                        ? KeyChoice(id: "none", face: [.text("none")], name: "nothing", selected: profile.clear == keys) { save { $0.clear = keys } }
                        : KeyChoice(keys: keys, selected: profile.clear == keys) { save { $0.clear = keys } }
                })

            Section("add")
            Paragraph("Your own keys, like ctrl with a letter or several in a row, are shortcuts: add them from the shortcuts key.")
            KeyChoices(choices: pressing.only(KeyBar.common).map { k in KeyChoice(k, id: k, selected: false) { save { $0.keys.append(k) } } })

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

/** What can empty a row of a program's line: ctrl u (most), ctrl x ctrl k (zsh's kill-buffer), end then ctrl u (readline's from the end), or nothing. */
private let clears = [["C-u"], ["C-x", "C-k"], ["End", "C-u"], []]
