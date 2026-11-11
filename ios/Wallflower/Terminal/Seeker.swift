import Foundation
import Observation
import ReadModel
import ScreenReading

/**
 * A sent message asked back to edit, and the way to it: a program that
 * brings one back with a command (Claude Code's /rewind) lists the user's
 * messages, and the one asked for is found in that list, up to where the
 * user confirms. What a prompt did shows in the line it leaves, a moment
 * after it goes, whoever answered it.
 */
@MainActor
@Observable
final class Seeker {
    /**
     * A message asked back, looked for in the list its command opens: [id]
     * and what it [said], with [newer] of the user's messages after it. The
     * [list] as it opened; the places in it picked to see what they ask, the
     * latest last ([tried]); the prompt the last keys went [from]; and whether
     * they went [back] to the list, from what picking another asked.
     */
    struct Seeking: Equatable {
        var id: String
        var said: String
        var newer: Int
        var list: Walk.Listed?
        var tried: [Int] = []
        var from: Prompt?
        var back = false
    }

    /** The message asked back to edit, its text, each time it's asked. */
    struct Recalled: Equatable {
        let text: String
        let at: Date
    }

    private(set) var seeking: Seeking? {
        didSet { if seeking != oldValue { expire() } }
    }
    /** The message walked to, to edit. */
    private(set) var editing: String?
    private(set) var recalled: Recalled?
    @ObservationIgnored private var asked: Prompt?
    @ObservationIgnored private var gone: Date?
    @ObservationIgnored private var expiring: Task<Void, Never>?

    /** Another pane: nothing of the last one's carries over. */
    func reset() {
        seeking = nil
        editing = nil
        recalled = nil
        asked = nil
        gone = nil
    }

    /** The user's own hand on the prompt: the walk stops. */
    func handled() { seeking = nil }

    /** The user asked for message [id] ([text]) back, from [reading], to edit. */
    func edit(_ id: String, _ text: String, actions: PaneActions, reading: Reading, line: InputLine?) {
        guard let r = actions.recall(id, text, reading: reading, line: line) else { return }
        recalled = Recalled(text: text, at: Date())
        if case .command = r {
            let after = reading.shown.drop { $0.id != id }.dropFirst()
            let newer = after.filter { if case .said(let s) = $0, s.by == .user { return true } else { return false } }.count
            seeking = Seeking(id: id, said: text, newer: newer)
        }
    }

    /** Looked for a while since the last keys, it's given up: the user picks. */
    private func expire() {
        expiring?.cancel()
        guard let s = seeking else { return }
        expiring = Task { [weak self] in
            // The list can't be read while Claude says the wheel sends arrows, for some seconds, after asking about another.
            try? await Task.sleep(for: .seconds(s.back ? 20 : 10))
            guard !Task.isCancelled else { return }
            self?.seeking = nil
        }
    }

    /** The pane's screen offers [prompt] now: a step on the way, if one's being found. */
    func prompt(_ p: Prompt?, actions: PaneActions?) {
        guard let s = seeking, let p, p != s.from, let actions else { return } // what the last keys went from: they haven't shown yet
        guard let walk = actions.program.walk(p, said: s.said) else { return } // something between
        switch walk {
        case .listed(let list):
            // Straight to where the conversation has it, and picked, which only asks; once back from asking about another, close by.
            if s.list != nil && !s.back { return } // on the way to what a pick asks
            guard let at = Self.nearby(s.newer, list.last).first(where: { !s.tried.contains($0) }) else {
                seeking = nil // the list hasn't it: the user picks
                return
            }
            actions.keys(Self.moves(list, by: at - list.back) + list.pick)
            var next = s
            next.list = s.list ?? list
            next.tried.append(at)
            next.from = p
            next.back = false
            seeking = next
        case .picked(let picked):
            guard let list = s.list, !s.back else { return } // not one this asked, or on the way back
            if picked.on {
                editing = s.id
                seeking = nil
                return
            }
            // Not it (the list may count a message differently): back to the list, keys alone.
            actions.keys(picked.back)
            if Self.nearby(s.newer, list.last).contains(where: { !s.tried.contains($0) }) {
                var next = s
                next.from = p
                next.back = true
                seeking = next
            } else {
                seeking = nil
            }
        }
    }

    /** What a prompt did shows in the line it leaves, a moment after it goes, whoever answered it: a rewind restored at the computer, say. */
    func settle(prompt p: Prompt?, held: String?, line: InputLine?, actions: PaneActions?) {
        let was = asked
        if p != nil {
            asked = p
            gone = nil
        } else if was != nil && gone == nil {
            gone = Date()
        }
        if p == nil, let was, held != nil, let g = gone, Date().timeIntervalSince(g) < 3 {
            actions?.settled(was, line, held: editing)
            asked = nil
            editing = nil
        }
    }

    /** Keys that move the list's cursor [by] messages back (forward, if negative). */
    private static func moves(_ list: Walk.Listed, by: Int) -> [String] {
        Array(repeating: by > 0 ? list.up : list.down, count: abs(by)).flatMap { $0 }
    }

    /** Where to look for a message [at] in a list going [last] back: there, then a step either side, then two, and so on, up to three. */
    static func nearby(_ at: Int, _ last: Int) -> [Int] {
        (0...3).flatMap { d in d == 0 ? [at] : [at - d, at + d] }.filter { (0...last).contains($0) }
    }
}
