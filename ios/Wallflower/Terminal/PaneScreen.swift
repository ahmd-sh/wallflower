import Observation
import ScreenReading
import SwiftUI
import TerminalCore

/**
 * What the shown pane's screen says: its program's input line, what it says
 * it's doing, questions it asked waiting to be brought up, and a prompt to
 * answer while one can be. Read whenever its
 * output changes, at most every [every], as the program reads it; the
 * prompt only while [asks] (it can be answered: in the mobile view, the
 * program not working). What it says it's doing holds while its line can't
 * be read.
 */
@MainActor
@Observable
final class PaneScreen {
    var line: InputLine?
    var status: String?
    var prompt: Prompt?
    var asked: Asked?
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
                let read: (InputLine?, String?, Prompt?, Asked?) = await Task.detached {
                    guard let bytes, let s = Screen.parse(bytes) else { return (nil, nil, nil, nil) }
                    return (p.line(s), p.status(s), a ? p.prompt(s) : nil, p.asked(s))
                }.value
                if self.line != read.0 { self.line = read.0 }
                // A screen without the program's line is between drawings, or covered by what it asks: it doesn't say it stopped.
                if read.0 != nil || read.1 != nil, self.status != read.1 { self.status = read.1 }
                if self.prompt != read.2 { self.prompt = read.2 }
                if self.asked != read.3 { self.asked = read.3 }
                try? await Task.sleep(for: .milliseconds(80))
            }
            self?.reading = nil
        }
    }

    /** Gone at once; the next read says what's there now. */
    func forgetLine() { line = nil }
}
