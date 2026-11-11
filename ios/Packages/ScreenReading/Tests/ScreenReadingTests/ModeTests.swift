import XCTest
import ScreenReading

/// Claude Code's permission modes, from captures at 54 columns.
final class ModeTests: XCTestCase {
    private let claude = Claude.shared

    func testItsFooterSaysWhichModeItsIn() {
        XCTAssertEqual(Mode(name: "manual", mark: "⏸"), claude.mode(captured("claude-mode-manual.ansi", 54)))
        XCTAssertEqual(Mode(name: "accept edits", mark: "⏵⏵"), claude.mode(captured("claude-mode-accept-edits.ansi", 54)))
        XCTAssertEqual(Mode(name: "plan", mark: "⏸"), claude.mode(captured("claude-mode-plan.ansi", 54)))
        XCTAssertEqual(Mode(name: "auto", mark: "⏵⏵"), claude.mode(captured("claude-mode-auto.ansi", 54)))
        XCTAssertEqual(Mode(name: "auto", mark: "⏵⏵"), claude.mode(captured("claude-working.ansi", 90)))
    }

    func testOutOfVimsInsertItsStillThere() {
        XCTAssertEqual(Mode(name: "accept edits", mark: "⏵⏵"), claude.mode(captured("claude-mode-normal.ansi", 54)))
    }

    func testWithoutItsBoxItCantBeSeen() {
        XCTAssertNil(claude.mode(captured("claude-bash-permission.ansi", 54)))
        XCTAssertNil(claude.mode(captured("claude-transcript.ansi", 54)))
        XCTAssertNil(claude.mode(captured("claude-btw.ansi", 54)))
    }

    func testTheCycleIsWhatsBeenSeenToFollow() {
        let manual = Mode(name: "manual", mark: "⏸")
        let edits = Mode(name: "accept edits", mark: "⏵⏵")
        let plan = Mode(name: "plan", mark: "⏸")
        let auto = Mode(name: "auto", mark: "⏵⏵")
        let cycle = Cycle(known: claude.modes)
        // Nothing seen: the current, then what it may have.
        XCTAssertEqual([auto, Mode(name: "manual", mark: ""), Mode(name: "accept edits", mark: ""), Mode(name: "plan", mark: "")], cycle.from(auto))
        cycle.stepped(from: auto, to: manual)
        cycle.stepped(from: manual, to: edits)
        XCTAssertEqual([auto, manual, edits, Mode(name: "plan", mark: "")], cycle.from(auto))
        XCTAssertEqual([edits, auto, manual, Mode(name: "plan", mark: "")], cycle.from(edits)) // nothing seen after it yet: the rest as they came
        cycle.stepped(from: edits, to: plan)
        cycle.stepped(from: plan, to: auto)
        XCTAssertEqual([plan, auto, manual, edits], cycle.from(plan))
        // One a round didn't come to goes, till it's seen.
        cycle.missed("plan")
        XCTAssertEqual([auto, manual, edits], cycle.from(auto))
        cycle.saw(plan)
        XCTAssertEqual([auto, manual, edits, plan], cycle.from(auto))
    }

    func testAModeNotKnownIsOneSeen() {
        let cycle = Cycle(known: claude.modes)
        cycle.saw(Mode(name: "bypass permissions", mark: "⏵⏵"))
        XCTAssertEqual(["auto", "bypass permissions", "manual", "accept edits", "plan"], cycle.from(Mode(name: "auto", mark: "⏵⏵")).map(\.name))
    }
}
