import Foundation
import ScreenReading
import WallflowerDesign

/**
 * The modes the shown pane's program goes round, as far as they've been
 * seen ([Cycles]), and a mode picked, stepped to a key at a time: each step
 * as far as the [screen] shows it went, and no further round than where it
 * started.
 */
@MainActor
struct ModeWalk {
    let screen: PaneScreen
    let haptics: Haptics

    /** The modes [program] goes round, from the one it's in. */
    func modes(_ program: (any Program)?) -> [Mode] {
        guard let mode = screen.mode, let program, program.nextMode != nil else { return [] }
        return Cycles.of(program).from(mode)
    }

    /** The screen shows [program] in [mode]: one it goes round. */
    func saw(_ mode: Mode?, _ program: (any Program)?) {
        guard let mode, let program, program.nextMode != nil else { return }
        Cycles.of(program).saw(mode)
    }

    /** Steps the pane to the mode named [to], by its program's key for the next. */
    func walk(to: String, actions: PaneActions?) async {
        guard let actions, let keys = actions.program.nextMode, let from = screen.mode else { return }
        let cycle = Cycles.of(actions.program)
        var at = from
        for _ in 0..<modeSteps {
            // Only at its line: a menu, or what's aside of it, would take the key as its own (shift+tab allows edits, in some).
            if at.name == to || screen.line == nil || screen.aside != nil { break }
            actions.keys(keys)
            // Not moved: something's over its line, which takes the key.
            guard let next = await moved(from: at) else { break }
            cycle.stepped(from: at, to: next)
            at = next
            if at == from {
                cycle.missed(to) // round, and it wasn't there
                break
            }
            if at.name != to { haptics.repeating() }
        }
        if Task.isCancelled { return }
        if at.name == to { haptics.confirm() } else { haptics.reject() }
    }

    /** The mode once it's moved on from [at]; nil if it hasn't in a while. */
    private func moved(from at: Mode) async -> Mode? {
        let until = Date().addingTimeInterval(modeStepSeconds)
        while Date() < until && !Task.isCancelled {
            if let m = screen.mode, m != at { return m }
            try? await Task.sleep(for: .milliseconds(40))
        }
        return nil
    }
}

/** How many steps a mode picked is gone round to at most, and how long each may take to show. */
private let modeSteps = 8
private let modeStepSeconds: TimeInterval = 2
