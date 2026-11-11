import XCTest
import ScreenReading

/// Claude Code's views over its prompt box, from captures at 54 columns.
final class AsideTests: XCTestCase {
    private struct Unexpected: Error {
        let aside: Aside?
    }

    private let claude = Claude.shared

    private func answer(_ aside: Aside?) throws -> Aside.Answer {
        guard case let .answer(a) = aside else { throw Unexpected(aside: aside) }
        return a
    }

    func testItsTranscriptSaysSoBelowItsLastRule() {
        for name in ["claude-transcript.ansi", "claude-transcript-help.ansi", "claude-transcript-search.ansi"] {
            let s = captured(name, 54)
            XCTAssertEqual(.view(Aside.View(name: "transcript", leave: ["C-o"])), claude.aside(s), name)
            XCTAssertNil(claude.line(s), name)
            XCTAssertNil(claude.prompt(s), name) // its numbered rows are something said
        }
    }

    func testWithItsBoxThereNothingsAside() {
        for (name, cols) in [("claude-working.ansi", 90), ("claude-mode-manual.ansi", 54), ("claude-numbered-message.ansi", 54), ("claude-working-queued.ansi", 90)] {
            XCTAssertNil(claude.aside(captured(name, cols)), name)
        }
    }

    func testWhatItAsksIsntAside() {
        for (name, cols) in [
            ("claude-bash-permission.ansi", 54), ("claude-plan-approval.ansi", 54), ("claude-question.ansi", 52), ("claude-question-review.ansi", 52),
            ("claude-rewind-list.ansi", 54), ("claude-rewind-confirm.ansi", 54), ("claude-question-write.ansi", 54), ("claude-rewind-list-fullscreen.ansi", 52),
            ("claude-rewind-confirm-fullscreen.ansi", 52), ("claude-question-previews.ansi", 52),
        ] {
            XCTAssertNil(claude.aside(captured(name, cols)), name)
        }
    }

    func testAFullscreenMenusRuleIsNoAnswersAndSaysNothingOfWork() {
        XCTAssertNil(claude.status(captured("claude-rewind-list-fullscreen.ansi", 52)))
        XCTAssertNil(claude.status(captured("claude-rewind-confirm-fullscreen.ansi", 52)))
    }

    func testASideQuestionsAnswerTakesTheBoxsPlace() throws {
        let s = captured("claude-btw.ansi", 54)
        let a = try answer(claude.aside(s))
        XCTAssertEqual("what is 2+2, one word", a.question)
        XCTAssertEqual(["Four"], a.rows)
        XCTAssertEqual(["Escape"], a.leave)
        XCTAssertEqual(46, a.width)
        XCTAssertFalse(a.taller)
        XCTAssertNil(claude.line(s))
        XCTAssertNil(claude.prompt(s))
    }

    func testWhileItsAnsweredItSaysSoAndWhatClaudeIsDoingShowsAboveIt() throws {
        let s = captured("claude-btw-answering.ansi", 54)
        let a = try answer(claude.aside(s))
        XCTAssertEqual("what is 3+3, one word", a.question) // the bold one, below those asked before
        XCTAssertNil(a.rows)
        XCTAssertEqual("Orchestrating", claude.status(s))
    }

    func testTheAnsweredOneIsTheBoldOneOfThoseAsked() throws {
        let a = try answer(claude.aside(captured("claude-btw-history.ansi", 54)))
        XCTAssertEqual("what is 3+3, one word", a.question)
        XCTAssertEqual(["Six"], a.rows)
        XCTAssertNil(claude.status(captured("claude-btw-history.ansi", 54)))
    }

    func testAnAnswersWrappedRowsJoinAndItsOwnLinesStay() throws {
        let a = try answer(claude.aside(captured("claude-btw-markdown.ansi", 54)))
        XCTAssertEqual("in markdown: a heading, two short bullets,…", a.question)
        XCTAssertEqual(
            [
                "Markdown Basics",
                "",
                "- Headings use hash marks",
                "- Lists use dashes",
                "",
                "echo \"Hello, world\"",
                "",
                "Markdown lets you format plain text with simple symbols that convert into styled HTML. Headings use hash marks and "
                    + "lists use dashes. Because the syntax stays readable even unrendered, it suits notes, documentation, and quick messages shared across teams daily.",
            ].joined(separator: "\n"),
            a.text(a.rows!)
        )
    }

    func testALongAnswerShowsAsMuchAsItsRoom() throws {
        let a = try answer(claude.aside(captured("claude-btw-long.ansi", 54)))
        XCTAssertEqual((1...25).map { "\($0)" }, a.rows)
        XCTAssertTrue(a.taller)
        XCTAssertEqual("1\n2\n3", a.text(Array(a.rows!.prefix(3))))
        XCTAssertFalse(try answer(claude.aside(captured("claude-btw-markdown.ansi", 54))).taller) // all there
    }

    func testScrolledThroughItsAnswerAddsUp() throws {
        let whole = try answer(claude.aside(captured("claude-btw-markdown.ansi", 54))).rows!
        var kept: [String] = []
        for i in 0...3 {
            let shown = try answer(claude.aside(captured("claude-btw-scroll-\(i).ansi", 54)))
            XCTAssertTrue(shown.taller)
            kept = shown.over(kept)
        }
        XCTAssertEqual(whole, kept)
        // Scrolled back up, it's still all there.
        XCTAssertEqual(whole, try answer(claude.aside(captured("claude-btw-scroll-0.ansi", 54))).over(kept))
    }

    func testRowsThatDontMeetWhatWasReadStandAlone() {
        func answer(_ rows: String...) -> Aside.Answer {
            Aside.Answer(question: "q", rows: rows, width: 46, taller: false, leave: ["Escape"], up: ["Up"], down: ["Down"], step: 3)
        }
        XCTAssertEqual(["a", "b", "c"], answer("a", "b", "c").over(["a"])) // more of it written
        XCTAssertEqual(["x", "", "y"], answer("x", "", "y").over(["a", ""])) // a blank alike isn't meeting
        XCTAssertEqual(["z"], answer("z").over(["a", "b"]))
        var answering = answer("Answering")
        answering.rows = nil
        XCTAssertEqual(["a", "b"], answering.over(["a", "b"]))
        XCTAssertEqual(["o", "p", "a", "b", "c"], answer("o", "p", "a", "b").over(["a", "b", "c"])) // scrolled up past it
    }

    func testBtwIsACommand() {
        XCTAssertTrue(claude.commands.contains { $0.text == "/btw" })
    }
}
