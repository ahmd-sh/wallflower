import XCTest
import ScreenReading

/// Codex CLI 0.167's screens, at 64 columns.
final class CodexTests: XCTestCase {
    private let codex = Codex.shared

    private func screen(_ name: String) -> Screen { captured("codex-\(name).ansi", 64) }

    func testAnEmptyComposerHoldsOnlyItsPlaceholder() {
        XCTAssertEqual("", codex.line(screen("idle"))?.text)
        XCTAssertNil(codex.prompt(screen("idle")))
        XCTAssertNil(codex.status(screen("idle")))
        XCTAssertNil(codex.asked(screen("idle")))
    }

    func testALongLineIsWrappedAtWordsAndALongWordWhereTheRowEnds() {
        let line = codex.line(screen("typed"))
        XCTAssertEqual(
            "the quick brown fox jumps over the lazy dog and keeps running far beyond the edge of this narrow pane "
                + "averyveryverylongwordthatcannotfitonarowatallnomatterhowhardittries end\nand a second line",
            line?.text
        )
        XCTAssertEqual(5, line?.rows)
    }

    func testTheCommandsPopupIsAboveTheComposerNotInIt() {
        XCTAssertEqual("/", codex.line(screen("slash"))?.text)
        XCTAssertNil(codex.prompt(screen("slash")))
    }

    func testWorkingItSaysSoAboveTheComposer() {
        XCTAssertEqual("Working", codex.status(screen("working")))
        XCTAssertEqual("", codex.line(screen("working"))?.text)
        XCTAssertNil(codex.asked(screen("working")))
    }

    func testItsQuestionsWaitBelowItsWorkingToBeBroughtUp() {
        let s = screen("working-asks")
        XCTAssertEqual("Working", codex.status(s))
        XCTAssertEqual(Asked(what: "1 question", keys: ["S-Left"]), codex.asked(s))
        XCTAssertNil(codex.prompt(s))
        XCTAssertEqual("a message typed while it works", codex.line(screen("working-typed"))?.text)
    }

    func testBroughtUpAQuestionTakesTheComposersPlace() throws {
        let s = screen("question")
        XCTAssertNil(codex.line(s))
        XCTAssertNil(codex.asked(s))
        let p = try XCTUnwrap(codex.prompt(s))
        XCTAssertEqual(["Red", "Blue", "Green", "Other"], p.options.map(\.label))
        XCTAssertEqual(["Which colour do you pick?"], p.lines.map(\.text).filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }) // not the conversation's rows above
        XCTAssertEqual(["2"], p.keys(p.options[1])) // a number answers it at once
        XCTAssertEqual("Working", codex.status(s))
    }

    func testAnApprovalIsAMenu() throws {
        let s = screen("approval")
        XCTAssertNil(codex.line(s))
        let p = try XCTUnwrap(codex.prompt(s))
        XCTAssertEqual(3, p.options.count)
        XCTAssertEqual("Yes, proceed (y)", p.options[0].label)
        XCTAssertEqual("No, and tell Codex what to do differently (esc)", p.options[2].label)
        XCTAssertTrue(p.options[0].current)
        XCTAssertEqual("Would you like to run the following command?", p.lines.first?.text.trimmingCharacters(in: .whitespaces))
    }

    func testItsTitleIsTheConversationsNameThenTheProjects() {
        XCTAssertEqual("Approve touch command", codex.label("Approve touch command | wallflower"))
        XCTAssertEqual("Approve touch command", codex.label("⠴ Approve touch command | wallflower"))
        XCTAssertEqual("Approve touch command", codex.label("[ ! ] Action Required | Approve touch command | wallflower"))
        XCTAssertNil(codex.label("wallflower"))
        XCTAssertNil(codex.label("[ . ] Action Required | wallflower"))
    }

    func testItsModeIsPlanWhenItsFooterSaysSo() {
        XCTAssertEqual(Mode(name: "plan", mark: ""), codex.mode(captured("codex-plan.ansi", 80)))
        XCTAssertEqual(Mode(name: "default", mark: ""), codex.mode(screen("idle")))
        XCTAssertNil(codex.mode(screen("question")))
    }

    func testItHasItsOwnBarAndStop() {
        XCTAssertTrue(Programs.of("codex") === codex)
        XCTAssertEqual(["Escape"], codex.keyBar?.stop)
    }
}
