import Foundation
import ReadModel
import ScreenReading
import SessionCore
import Wire

/**
 * What the user can do to a pane, in its program's terms: [clearKeys] empty
 * a row of its input line (the user's, or the program's), [stopKeys] stop it,
 * and where the keyboard types straight in, [sendKeys] are the send button's
 * and [returnKeys] the keyboard's return key.
 */
@MainActor
struct PaneActions {
    let session: Session
    let pane: Pane
    let program: any Program
    let clearKeys: [String]
    let stopKeys: [String]
    let sendKeys: [String]
    let returnKeys: [String]

    func keys(_ keys: [String], bindings: Bool = false) { session.keys(pane.id, keys, bindings: bindings) }

    /** A scroll wheel's notch: its bytes for an app that tracks the mouse, else an arrow. */
    func wheel(_ up: Bool, _ bytes: Data?) {
        if let bytes { session.bytes(pane.id, bytes) } else { keys([up ? "Up" : "Down"]) }
    }

    func enter() { keys(["Enter"]) }

    /** The send button, where the keyboard types straight in. */
    func press() { keys(sendKeys) }

    /** Text typed straight into the pane: as it is, but pasted if it has lines (from the clipboard), so they don't run one by one. */
    func type(_ text: String) {
        if text.contains("\n") && text.count > 1 {
            session.submit(pane.id, text, enter: false)
        } else {
            session.bytes(pane.id, Data(text.replacingOccurrences(of: "\n", with: "\r").utf8))
        }
    }

    func stop() {
        session.stopped(pane.id)
        keys(stopKeys)
    }

    /**
     * Brings the user's message back into the program's input line, to edit,
     * if the program can: how, if it's on its way.
     */
    func recall(_ id: String, _ text: String, reading: Reading, line: InputLine?) -> Recall? {
        let latest = reading.entries.last { if case .said(let s) = $0, s.by == .user { return true } else { return false } }?.id == id
        guard let r = program.recall(latest: latest, working: activityOf(pane) == .working) else { return nil }
        switch r {
        case .keys(let k):
            session.stopped(pane.id) // as a stop does, the program may take the message back
            keys(k)
            return r
        case .command(let c):
            return send(c, enter: true, line: line, unseen: true) ? r : nil
        }
    }

    /**
     * Whether the pane's at work, so a stop means something: its program
     * says so, if it says; else a command other than its shell runs there,
     * but not one that took the whole screen (an editor, a pager).
     */
    var busy: Bool {
        if let a = activityOf(pane) { return a != .idle }
        let name = pane.command.trimmingCharacters(in: CharacterSet(charactersIn: "-")).split(separator: "/").last.map(String.init) ?? ""
        return !Self.shells.contains(name) && !pane.alt
    }

    /** Whether anything empties its input line. */
    var clears: Bool { !clearKeys.isEmpty }

    /** Empties [line] in the pane; [unseen] while the mobile view covers the program. */
    func clear(_ line: InputLine, unseen: Bool) { keys(program.clear(line, keys: clearKeys, unseen: unseen)) }

    /** Sends composed text in place of whatever [line] held; false if it couldn't go. */
    @discardableResult
    func send(_ text: String, enter: Bool, line: InputLine?, unseen: Bool) -> Bool {
        session.submit(pane.id, text, enter: enter, clear: line.map { program.clear($0, keys: clearKeys, unseen: unseen) } ?? [])
    }

    /**
     * A shortcut, picked from the bar. Text it starts with that Enter sends
     * goes in place of what [line] holds, as the box's does, so a command
     * runs as itself whatever's in the line (and in vim mode's NORMAL, where
     * it can't be seen). Its other keys and text go where the cursor is: as
     * if typed on a keyboard attached to the multiplexer if it says, so its
     * key bindings apply (the user's prefix, then a key), where it can.
     */
    func shortcut(_ key: BarKey, line: InputLine?) {
        let bound = key.bindings && session.mux?.can.contains(Mux.BINDINGS) == true
        for (i, step) in KeyBar.steps(key.keys).enumerated() {
            switch step {
            case .keys(let k): keys(k, bindings: bound)
            case .text(let text, enter: false): if bound { keys([text], bindings: true) } else { type(text) }
            case .text(let text, enter: true) where i == 0: send(text, enter: true, line: line, unseen: true)
            case .text(let text, enter: true): _ = session.submit(pane.id, text)
            }
        }
    }

    /** Compacts its conversation, if its program can; it's under the mobile view, so unseen. */
    var compact: ((InputLine?) -> Void)? {
        guard let command = program.compact else { return nil }
        return { line in send(command, enter: true, line: line, unseen: true) }
    }

    /** Has the program take the suggestion in its input line into the line, whole, if it can. */
    var acceptSuggestion: (() -> Void)? {
        guard let k = program.acceptSuggestion else { return nil }
        return { keys(k) }
    }

    func picture(_ id: String) async -> Data? { await session.picture(pane: pane.id, id: id) }

    /** An option picked from a prompt the pane offered. */
    func picked(_ prompt: Prompt, _ option: Prompt.Option, held: String? = nil) { affect(program.picked(prompt, option: option), held) }

    /** A prompt the pane offered has gone, leaving its input [line] as it is. */
    func settled(_ prompt: Prompt, _ line: InputLine?, held: String? = nil) { affect(program.settled(prompt, line: line), held) }

    private func affect(_ effect: Effect?, _ held: String?) {
        guard let effect else { return }
        switch effect {
        case .rewound(let said): session.rewound(pane.id, said: said, held: held)
        }
    }

    /** Commands that are a shell waiting at its prompt. */
    static let shells: Set<String> = ["zsh", "bash", "fish", "sh", "dash", "ksh", "mksh", "tcsh", "csh", "nu", "xonsh", "elvish", "pwsh", "login"]
}
