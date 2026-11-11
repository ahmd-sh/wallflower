import XCTest
import ScreenReading

final class LineTests: XCTestCase {
    /// A screen from rows of text. `marks` gives each cell's attributes by
    /// row, one character per cell: p prompt, i input, g input in
    /// autosuggestion grey, f faint; anything else is plain output.
    private func screen(_ rows: [String], _ marks: [String] = [], cursor: (Int, Int), wrapped: Set<Int> = []) -> Screen {
        let cols = rows.map(\.utf16.count).max()!
        var text = [String](repeating: "", count: cols * rows.count)
        var attrs = [Int](repeating: 0, count: cols * rows.count)
        for (y, row) in rows.enumerated() {
            for (x, c) in row.utf16.enumerated() { text[y * cols + x] = c == 0x20 ? "" : String(decoding: [c], as: UTF16.self) }
            guard y < marks.count else { continue }
            for (x, m) in marks[y].enumerated() {
                attrs[y * cols + x] = switch m {
                case "p": Screen.prompt
                case "i": Screen.input
                case "g": Screen.input | Screen.grey
                case "f": Screen.faint
                default: 0
                }
            }
        }
        return Screen(cols: cols, rows: rows.count, cursorX: cursor.0, cursorY: cursor.1, text: text, attrs: attrs, wrapped: rows.indices.map { wrapped.contains($0) })
    }

    private func repeated(_ s: String, _ n: Int) -> String { String(repeating: s, count: n) }

    func testShellInputAfterThePromptWithoutTheRightPromptOrSuggestion() {
        let s = screen(
            [
                "dotfiles ➜ git status --short      main ",
                "                                        ",
            ],
            [
                repeated("p", 11) + repeated("i", 10) + repeated("g", 8) + repeated("i", 6) + "pppp ",
            ],
            cursor: (21, 0)
        )
        XCTAssertEqual(InputLine(text: "git status", rows: 1), Shell().line(s))
    }

    func testABlankTheShellLeftUndrawnStaysInTheLine() {
        let s = screen(["dotfiles ➜ ls -G     main "], [repeated("p", 11) + "ii.ii" + "     pppp"], cursor: (16, 0))
        XCTAssertEqual(InputLine(text: "ls -G", rows: 1), Shell().line(s))
    }

    func testAnEmptyPromptIsAnEmptyLine() {
        let s = screen(["dotfiles ➜           main "], [repeated("p", 11) + repeated("i", 10) + "pppp"], cursor: (11, 0))
        XCTAssertEqual(InputLine(text: "", rows: 1), Shell().line(s))
    }

    func testARunningCommandHasNoLine() {
        let s = screen(
            ["dotfiles ➜ sleep 10 ", "                    "],
            [repeated("p", 11) + repeated("i", 9), ""],
            cursor: (0, 1)
        )
        XCTAssertNil(Shell().line(s))
    }

    func testWrappedShellInputJoinsWithoutANewline() {
        let s = screen(
            ["$ echo a long li", "ne of text      "],
            ["ppiiiiiiiiiiiiii", "iiiiiiiiiii     "],
            cursor: (10, 1),
            wrapped: [0]
        )
        XCTAssertEqual(InputLine(text: "echo a long line of text", rows: 2), Shell().line(s))
    }

    func testOutputWithoutPromptMarksHasNoLine() {
        XCTAssertNil(Shell().line(screen(["$ ls", "a b c"], cursor: (0, 1))))
    }

    private let rule = String(repeating: "─", count: 24)

    func testClaudeBoxSuggestsWhatItsPlaceholderQuotes() {
        let s = screen(
            ["● Done.", rule, "❯ Try \"fix the tests\"", rule, "  -- INSERT --"],
            ["", "", "  fff ffff fff ffffff"],
            cursor: (2, 2)
        )
        XCTAssertEqual(InputLine(text: "", rows: 1, suggestion: "fix the tests"), Claude.shared.line(s))
    }

    func testClaudeBoxSuggestsWhatsNextAcrossRows() {
        let s = screen(
            [rule, "❯ ask the fruit and size", "  questions again", rule],
            ["", "  ffffffffffffffffffffff", "  ffffffffffffffff"],
            cursor: (2, 1)
        )
        XCTAssertEqual(InputLine(text: "", rows: 2, suggestion: "ask the fruit and size questions again"), Claude.shared.line(s))
    }

    func testClaudeBoxSuggestsNothingOnceTyped() {
        let s = screen(
            [rule, "❯ fix the tests", rule],
            ["", "  iiiiiiiifffff"],
            cursor: (11, 1)
        )
        XCTAssertEqual(InputLine(text: "fix the", rows: 1), Claude.shared.line(s))
    }

    func testClaudeBoxJoinsItsOwnWrapsAndKeepsNewlines() {
        // 24 columns: 20 for text, between the two-column margin and two more at the right.
        let s = screen(
            [
                rule,
                "❯ make a profile for",
                "  hntui with j",
                "  second line",
                rule,
            ],
            cursor: (13, 3)
        )
        XCTAssertEqual(InputLine(text: "make a profile for hntui with j\nsecond line", rows: 3), Claude.shared.line(s))
    }

    // Claude Code 2.1.318 at 30 columns, as captured: a suggestion too wide
    // for the box is cut short, and Tab takes it into the box whole, wrapped
    // where its next word wouldn't fit beside the cursor.
    private let rule30 = String(repeating: "─", count: 30)

    func testClaudeBoxSaysASuggestionIsCutShort() {
        let s = screen(
            [rule30, "❯ Show me a minimal tmux.conf…", rule30, "  -- INSERT --"],
            ["", "  " + repeated("f", 28)],
            cursor: (2, 1)
        )
        XCTAssertEqual(InputLine(text: "", rows: 1, suggestion: "Show me a minimal tmux.conf…"), Claude.shared.line(s))
    }

    func testClaudeBoxHoldsASuggestionWholeOnceTaken() {
        let s = screen(
            [rule30, "❯ Show me a minimal", "  tmux.conf to start with", rule30, "  -- INSERT --"],
            cursor: (25, 2)
        )
        XCTAssertEqual(InputLine(text: "Show me a minimal tmux.conf to start with", rows: 2), Claude.shared.line(s))
        XCTAssertEqual(["Tab"], Claude.shared.acceptSuggestion)
    }

    func testClaudeBoxWrapsAWordThatLeavesNoRoomForTheCursor() {
        let s = screen(
            [rule30, "❯ aa bb cc dd ee ff gg hh ii", "  jj kk ll mm nn oo pp qq", "  rr", rule30],
            cursor: (4, 3)
        )
        XCTAssertEqual(InputLine(text: "aa bb cc dd ee ff gg hh ii jj kk ll mm nn oo pp qq rr", rows: 3), Claude.shared.line(s))
    }

    func testClaudeBoxBreaksAWordTooLongForARowWhereTheRowEnds() {
        let s = screen(
            [rule30, "❯ see /Users/ahmedshaikh/dev", "  /wallflower/internal/gatew", "  ay/sessions.go now", rule30],
            cursor: (20, 3)
        )
        XCTAssertEqual(InputLine(text: "see /Users/ahmedshaikh/dev/wallflower/internal/gateway/sessions.go now", rows: 3), Claude.shared.line(s))
    }

    func testClaudeShellModeKeepsItsBang() {
        let s = screen([rule, "! seq 1 300", rule], cursor: (11, 1))
        XCTAssertEqual(InputLine(text: "!seq 1 300", rows: 1), Claude.shared.line(s))
    }
}

final class ProgramTests: XCTestCase {
    private let line = InputLine(text: "two\nrows", rows: 2)

    private func repeated<T>(_ n: Int, _ item: [T]) -> [T] { Array(repeating: item, count: n).flatMap { $0 } }

    func testTheCommandPicksTheProgram() {
        XCTAssertIdentical(Claude.shared, Programs.of("claude"))
        XCTAssertEqual(["C-x", "C-k"], Programs.of("ZSH").clearKeys)
        XCTAssertEqual([], Programs.of("bash").clearKeys)
        XCTAssertEqual([], Programs.of("hntui").clearKeys)
    }

    func testALineIsClearedARowAtATimeAndOnceMore() {
        XCTAssertEqual(repeated(5, ["C-x", "C-k"]), Programs.of("zsh").clear(line, unseen: true))
        XCTAssertEqual(repeated(5, ["Escape", "S"]), Programs.of("zsh").clear(line, keys: ["Escape", "S"]))
    }

    func testClaudeIsInInsertModeBeforeItsLineIsClearedUnseen() {
        XCTAssertEqual(repeated(5, ["C-u"]), Claude.shared.clear(line))
        XCTAssertEqual(["A"] + repeated(5, ["C-u"]), Claude.shared.clear(line, unseen: true))
        XCTAssertEqual(repeated(5, ["C-k"]), Claude.shared.clear(line, keys: ["C-k"], unseen: true)) // the user's own keys, as they are
    }

    func testRestoringFromRewindTakesTheConversationBackToTheQuotedMessage() throws {
        func option(_ label: String) -> Prompt.Option { Prompt.Option(number: 1, label: label, detail: "", checked: nil, current: true, muted: false) }
        let prompt = Prompt(lines: [], options: [option("Restore conversation"), option("Restore code")], tabs: [], quote: "say just: two, then … done")
        guard case let .rewound(said) = Claude.shared.picked(prompt, option: prompt.options[0]) else { return XCTFail("not rewound") }
        XCTAssertTrue(said("say just: two, then think, then say done"))
        XCTAssertFalse(said("say just: three"))
        XCTAssertNil(Claude.shared.picked(prompt, option: prompt.options[1]))
        var unquoted = prompt
        unquoted.quote = ""
        XCTAssertNil(Claude.shared.picked(unquoted, option: prompt.options[0]))
    }
}
