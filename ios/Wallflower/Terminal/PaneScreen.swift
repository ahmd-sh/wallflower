import Observation
import ScreenReading
import SwiftUI
import TerminalCore

/**
 * What the shown pane's screen says: its program's input line, what it says
 * it's doing, a prompt to answer while one can be, questions it asked
 * waiting to be brought up, what's shown aside of its line, and the mode
 * it's in. Read whenever its output changes, at most every [every], as the
 * program reads it; the prompt only while [asks] (it can be answered: in
 * the mobile view, the program not working). What it says it's doing, and
 * its mode, hold while its line can't be read. Of an answer aside,
 * [answer] is what's been read of it so far, as it's scrolled.
 */
@MainActor
@Observable
final class PaneScreen {
    var line: InputLine?
    var status: String?
    var prompt: Prompt?
    var asked: Asked?
    var aside: Aside?
    var answer: [String] = []
    var mode: Mode?
    @ObservationIgnored private var question: String?
    @ObservationIgnored private var asideAt = Date.distantPast
    @ObservationIgnored private var terminal: Terminal?
    @ObservationIgnored private var pane: String?
    @ObservationIgnored private var program: any Program = Programs.of(nil)
    @ObservationIgnored private var asks = false
    @ObservationIgnored private var dirty = false
    @ObservationIgnored private var reading: Task<Void, Never>?
    @ObservationIgnored private let owner = NSObject()

    func watch(pane: String?, terminal: Terminal?, program: any Program, asks: Bool) {
        if pane != self.pane {
            self.pane = pane
            line = nil
            status = nil
            prompt = nil
            asked = nil
            aside = nil
            answer = []
            mode = nil
            asked = nil
        }
        self.program = program
        if asks != self.asks {
            self.asks = asks
            reread()
        }
        guard terminal !== self.terminal else { return }
        self.terminal?.removeListener(owner)
        self.terminal = terminal
        terminal?.addListener(owner) { [weak self] in
            DispatchQueue.main.async { self?.reread() }
        }
        reread()
    }

    /** Reads it again soon. */
    func reread() {
        dirty = true
        guard reading == nil else { return }
        reading = Task { [weak self] in
            while let self, self.dirty, !Task.isCancelled {
                self.dirty = false
                let p = self.program
                let a = self.asks
                let bytes = self.terminal?.screenBytes()
                let read: Read = await Task.detached {
                    guard let bytes, let s = Screen.parse(bytes) else { return Read() }
                    return Read(line: p.line(s), status: p.status(s), prompt: a ? p.prompt(s) : nil, asked: p.asked(s), aside: p.aside(s), mode: p.mode(s))
                }.value
                if self.line != read.line { self.line = read.line }
                // A screen without the program's line is between drawings, or covered by what it asks: it doesn't say it stopped.
                if read.line != nil || read.status != nil, self.status != read.status { self.status = read.status }
                if self.prompt != read.prompt { self.prompt = read.prompt }
                if self.asked != read.asked { self.asked = read.asked }
                if let mode = read.mode, self.mode != mode { self.mode = mode }
                // What's read of an answer is kept through a screen between drawings, and laid together as it scrolls, till another question.
                if case .answer(let answer) = read.aside {
                    let next = answer.question == self.question ? answer.over(self.answer) : answer.rows ?? []
                    if self.answer != next { self.answer = next }
                    self.question = answer.question
                }
                // A screen between drawings has none of it: what was aside holds a moment, till it's read again.
                if read.aside != nil { self.asideAt = Date() }
                if read.aside == nil && read.line == nil && read.prompt == nil && self.aside != nil && Date().timeIntervalSince(self.asideAt) < asideHold {
                    Task { [weak self] in
                        try? await Task.sleep(for: .seconds(asideHold))
                        self?.reread()
                    }
                } else if self.aside != read.aside {
                    self.aside = read.aside
                }
                try? await Task.sleep(for: .milliseconds(80))
            }
            self?.reading = nil
        }
    }

    /** Gone at once; the next read says what's there now. */
    func forgetLine() { line = nil }
}

private struct Read: Sendable {
    var line: InputLine?
    var status: String?
    var prompt: Prompt?
    var asked: Asked?
    var aside: Aside?
    var mode: Mode?
}

private let asideHold: TimeInterval = 0.4

/** The modes each program has been seen to go round, while the app runs: they're how it's set up on the computer, the same in each of its panes. */
@MainActor
enum Cycles {
    private static var all: [ObjectIdentifier: Cycle] = [:]

    static func of(_ program: any Program) -> Cycle {
        let id = ObjectIdentifier(program)
        if let c = all[id] { return c }
        let c = Cycle(known: program.modes)
        all[id] = c
        return c
    }
}
