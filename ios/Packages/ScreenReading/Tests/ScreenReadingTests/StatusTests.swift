import XCTest
import ScreenReading

final class StatusTests: XCTestCase {
    private struct Unexpected: Error {
        let walk: Walk?
    }

    private let claude = Claude.shared

    private func listed(_ walk: Walk?) throws -> Walk.Listed {
        guard case let .listed(l) = walk else { throw Unexpected(walk: walk) }
        return l
    }

    private func picked(_ walk: Walk?) throws -> Walk.Picked {
        guard case let .picked(p) = walk else { throw Unexpected(walk: walk) }
        return p
    }

    func testClaudeSaysWhatItsDoingAboveItsBox() {
        XCTAssertEqual("Hyperspacing", claude.status(captured("claude-working.ansi", 90)))
        XCTAssertEqual("Compacting conversation", claude.status(captured("claude-compacting.ansi", 90)))
    }

    func testWhatItQueuedComesBetween() {
        XCTAssertEqual("Hyperspacing", claude.status(captured("claude-working-queued.ansi", 90)))
    }

    func testDoneItSaysNothing() {
        XCTAssertNil(claude.status(captured("claude-rewind-confirm.ansi", 54)))
    }

    func testTheQueuedPlaceholderIsNoSuggestion() throws {
        let line = try XCTUnwrap(claude.line(captured("claude-working-queued.ansi", 90)))
        XCTAssertEqual("", line.text)
        XCTAssertEqual("", line.suggestion)
    }

    func testARewindRestoredAtTheMacLeavesItsMessageInTheBox() throws {
        let confirm = try XCTUnwrap(Prompt.read(captured("claude-rewind-confirm.ansi", 54)))
        let message = "Use the Write tool to create /private/tmp/claude-502/-Users-ahmedshaikh-dev-dotfiles/9fb53435-c5d1-4202-adc7-69292faff226/scratchpad/rw.txt containing hi, then say just: done"
        guard case let .rewound(said) = claude.settled(confirm, line: InputLine(text: message, rows: 3)) else { return XCTFail("not rewound") }
        XCTAssertTrue(said(message))
        XCTAssertNil(claude.settled(confirm, line: InputLine(text: "", rows: 1))) // never mind
        XCTAssertNil(claude.settled(confirm, line: nil))
        var summaries = confirm
        summaries.options = confirm.options.filter { $0.label.hasPrefix("Summarize") }
        XCTAssertNil(claude.settled(summaries, line: InputLine(text: message, rows: 3)))
    }

    func testEditingAMessageWalksRewindsListToIt() throws {
        // ↑ 1 more, "say just: two", the cursor on the newest, ↓ 1 more: (current).
        let list = try XCTUnwrap(Prompt.read(captured("claude-rewind-list.ansi", 54)))
        XCTAssertEqual(.listed(Walk.Listed(back: 0, last: 2, up: ["Up"], down: ["Down"], pick: ["Enter"])), claude.walk(list, said: "say just: two"))
        // ↑ 7 more, a message, the cursor on (current).
        let multiline = try listed(claude.walk(XCTUnwrap(Prompt.read(captured("claude-rewind-list-multiline.ansi", 54))), said: "x"))
        XCTAssertEqual([-1, 7], [multiline.back, multiline.last])
        // At the top: the oldest, the cursor on the next, ↓ 11 more.
        let top = try listed(claude.walk(XCTUnwrap(Prompt.read(captured("claude-rewind-list-top.ansi", 54))), said: "x"))
        XCTAssertEqual([10, 11], [top.back, top.last])
    }

    func testWhatPickingOneAsksSaysWhetherItsTheMessage() throws {
        // Its foot (Never mind) below a short pane.
        let confirm = try XCTUnwrap(Prompt.read(captured("claude-rewind-confirm.ansi", 54)))
        let message = "Use the Write tool to create /private/tmp/claude-502/-Users-ahmedshaikh-dev-dotfiles/9fb53435-c5d1-4202-adc7-69292faff226/scratchpad/rw.txt containing hi, then say just: done"
        XCTAssertEqual(.picked(Walk.Picked(on: true, back: ["Escape"])), claude.walk(confirm, said: message))
        XCTAssertEqual(false, try picked(claude.walk(confirm, said: "say just: two")).on)
        XCTAssertEqual(false, try picked(claude.walk(confirm, said: "create /private/tmp/claude-502")).on) // from the start, not somewhere in it
    }

    func testAQuoteIsTheMessageFromItsStart() {
        func restore(_ quote: String) -> (String) -> Bool {
            let option = Prompt.Option(number: 1, label: "Restore conversation", detail: "", checked: nil, current: true, muted: false)
            guard case let .rewound(said) = claude.picked(Prompt(lines: [], options: [option], tabs: [], quote: quote), option: option) else {
                XCTFail("not rewound")
                return { _ in false }
            }
            return said
        }
        XCTAssertTrue(restore("yes")("yes"))
        XCTAssertFalse(restore("yes")("yes, and commit"))
        XCTAssertFalse(restore("commit")("let's commit as wip"))
        XCTAssertTrue(restore("let's commit…")("let's commit as wip"))
        XCTAssertTrue(restore("say just: two, then … done")("say just: two, then think, then say done"))
    }

    func testAMessageStartingOneIsNoMenuWhileTheBoxIsThere() {
        XCTAssertNil(claude.prompt(captured("claude-numbered-message.ansi", 54)))
        // Every menu Claude asks with takes the box's place, so each still reads.
        for (name, cols) in [
            ("claude-bash-permission.ansi", 54), ("claude-plan-approval.ansi", 54), ("claude-question.ansi", 52), ("claude-question-review.ansi", 52),
            ("claude-rewind-list.ansi", 54), ("claude-rewind-confirm.ansi", 54), ("claude-rewind-confirm-nocode.ansi", 54), ("claude-question-write.ansi", 54),
        ] {
            XCTAssertNotNil(claude.prompt(captured(name, cols)), name)
        }
    }
}
