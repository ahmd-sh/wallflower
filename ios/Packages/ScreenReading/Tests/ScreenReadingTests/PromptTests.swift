import XCTest
import ScreenReading

private typealias Line = Prompt.Line
private typealias Option = Prompt.Option
private typealias Tab = Prompt.Tab

/// Screens as Claude Code draws its prompts, from captures at 56 columns.
final class PromptTests: XCTestCase {
    /// Rows of text; in `marks`, m is grey and h highlighted, one character per cell.
    private func screen(_ rows: (String, String)...) -> Screen {
        let cols = 56
        var text = [String](repeating: "", count: cols * rows.count)
        var attrs = [Int](repeating: 0, count: cols * rows.count)
        for (y, (row, marks)) in rows.enumerated() {
            for (x, c) in row.utf16.enumerated() where x < cols { text[y * cols + x] = c == 0x20 ? "" : String(decoding: [c], as: UTF16.self) }
            for (x, m) in marks.enumerated() {
                attrs[y * cols + x] = switch m {
                case "m": Screen.muted
                case "h": Screen.highlight
                default: 0
                }
            }
        }
        return Screen(cols: cols, rows: rows.count, cursorX: 0, cursorY: 0, text: text, attrs: attrs, wrapped: [Bool](repeating: false, count: rows.count))
    }

    private func plain(_ row: String) -> (String, String) { (row, "") }

    private let rule = String(repeating: "─", count: 56)
    private let dashes = String(repeating: "╌", count: 56)

    private func option(_ number: Int?, _ label: String, _ detail: String, _ checked: Bool?, current: Bool, muted: Bool, scroll: Int = 0) -> Option {
        Option(number: number, label: label, detail: detail, checked: checked, current: current, muted: muted, scroll: scroll)
    }

    private func line(_ text: String, _ code: Bool) -> Line { Line(text: text, code: code) }

    private func tab(_ label: String, _ current: Bool) -> Tab { Tab(label: label, current: current) }

    func testAPermissionPrompt() throws {
        let menu = try XCTUnwrap(Prompt.read(
            screen(
                plain("⏺ Bash(touch /tmp/wf-probe-1)"),
                plain("  ⎿  Waiting…"),
                plain(""),
                plain(rule),
                plain(" Bash command"),
                plain(" Create empty file /tmp/wf-probe-1"),
                plain(dashes),
                plain(" touch /tmp/wf-probe-1"),
                plain(dashes),
                plain(" Do you want to proceed?"),
                plain(" ❯ 1. Yes"),
                plain("   2. Yes, and always allow access to /tmp from this"),
                plain("      project"),
                plain("   3. No"),
                plain(""),
                plain(" Esc to cancel · Tab to amend")
            )
        ))
        XCTAssertEqual(
            [line("Bash command", false), line("Create empty file /tmp/wf-probe-1", false), line("touch /tmp/wf-probe-1", true), line("Do you want to proceed?", false)],
            menu.lines
        )
        XCTAssertEqual(
            [
                option(1, "Yes", "", nil, current: true, muted: false),
                option(2, "Yes, and always allow access to /tmp from this project", "", nil, current: false, muted: false),
                option(3, "No", "", nil, current: false, muted: false),
            ],
            menu.options
        )
        XCTAssertEqual(["3"], menu.keys(menu.options[2]))
    }

    func testAQuestionWithDescriptionsAndAnOptionBelowARule() throws {
        let menu = try XCTUnwrap(Prompt.read(
            screen(
                plain(rule),
                plain(" ☐ Color"),
                plain("Which color do you prefer?"),
                ("❯ 1. Red", "  mm"),
                ("     A bold, warm color that stands out.", "     " + String(repeating: "m", count: 35)),
                ("  2. Green", "  mm"),
                ("     A calm, natural color.", "     " + String(repeating: "m", count: 22)),
                ("  3. Type something.", "  " + String(repeating: "m", count: 18)),
                plain(rule),
                ("  4. Chat about this", "  mm"),
                plain("Enter to select · ↑/↓ to navigate · Esc to cancel")
            )
        ))
        XCTAssertEqual([tab("☐ Color", false)], menu.tabs)
        XCTAssertEqual([line("Which color do you prefer?", false)], menu.lines)
        XCTAssertEqual(
            [
                option(1, "Red", "A bold, warm color that stands out.", nil, current: true, muted: false),
                option(2, "Green", "A calm, natural color.", nil, current: false, muted: false),
                option(3, "Type something.", "", nil, current: false, muted: true),
                option(4, "Chat about this", "", nil, current: false, muted: false),
            ],
            menu.options
        )
    }

    func testAMultipleChoiceAmongQuestions() throws {
        let menu = try XCTUnwrap(Prompt.read(
            screen(
                plain(rule),
                ("←  ☐ Fruit  ☐ Size  ✔ Submit  →", "  hhhhhhhhh"),
                plain("Which fruits do you like?"),
                ("❯ 1. [ ] Apple", "  mm"),
                ("         Crisp and sweet.", "         " + String(repeating: "m", count: 16)),
                ("  2. [✔] Banana", "  mm"),
                ("  3. [ ] Type something", "  " + String(repeating: "m", count: 21)),
                plain("     Next"),
                plain(rule),
                ("  4. Chat about this", "  mm"),
                plain("Enter to select · Tab/Arrow keys to navigate · Esc to"),
                plain("cancel")
            )
        ))
        XCTAssertEqual([tab("☐ Fruit", true), tab("☐ Size", false), tab("✔ Submit", false)], menu.tabs)
        XCTAssertEqual(
            [
                option(1, "Apple", "Crisp and sweet.", false, current: true, muted: false),
                option(2, "Banana", "", true, current: false, muted: false),
                option(3, "Type something", "", false, current: false, muted: true),
                option(nil, "Next", "", nil, current: false, muted: false),
                option(4, "Chat about this", "", nil, current: false, muted: false),
            ],
            menu.options
        )
        XCTAssertEqual(["2"], menu.keys(menu.options[1]))
        XCTAssertEqual(["Down", "Down", "Down", "Enter"], menu.keys(menu.options[3]))
        XCTAssertEqual(["Right", "Right"], menu.keys(menu.tabs[2]))
    }

    func testAPlanApprovalBelowThePlansOwnSteps() throws {
        let menu = try XCTUnwrap(Prompt.read(
            screen(
                plain(" Steps"),
                plain(" 1. Use Edit on /tmp/wf-probe-2.txt to replace the"),
                plain("    first line."),
                plain(" 2. Use Read on /tmp/wf-probe-2.txt."),
                plain(dashes),
                plain(rule),
                plain(" Claude has written up a plan and is ready to execute."),
                plain(" Would you like to proceed?"),
                plain(""),
                plain(" ❯ 1. Yes, and use auto mode"),
                plain("   2. Yes, manually approve edits"),
                ("   3. Tell Claude what to change", "   " + String(repeating: "m", count: 29)),
                ("      shift+tab to approve with this feedback", "      " + String(repeating: "m", count: 39)),
                plain(" ctrl+g to edit in Nvim · ~/.claude/plans/x.md")
            )
        ))
        XCTAssertEqual(
            [line("Claude has written up a plan and is ready to execute. Would you like to proceed?", false)],
            menu.lines
        )
        XCTAssertEqual(["Yes, and use auto mode", "Yes, manually approve edits", "Tell Claude what to change"], menu.options.map(\.label))
        XCTAssertEqual("shift+tab to approve with this feedback", menu.options[2].detail)
    }

    func testARealPlanApproval() throws {
        let menu = try XCTUnwrap(Prompt.read(captured("claude-plan-approval.ansi", 54)))
        XCTAssertEqual(
            [line("Claude has written up a plan and is ready to execute. Would you like to proceed?", false)],
            menu.lines
        )
        XCTAssertEqual(["Yes, and use auto mode", "Yes, manually approve edits", "Tell Claude what to change"], menu.options.map(\.label))
        XCTAssertEqual("shift+tab to approve with this feedback", menu.options[2].detail)
    }

    func testARealPermissionWithADescriptionBesideALabel() throws {
        let menu = try XCTUnwrap(Prompt.read(captured("claude-bash-permission.ansi", 54)))
        let code = menu.lines.filter(\.code)
        XCTAssertEqual([line("touch /tmp/wf-probe-1 && ls -l /tmp/wf-probe-1", true)], code)
        XCTAssertEqual(
            [
                option(1, "Yes", "", nil, current: true, muted: false),
                option(2, "Yes, and allow access to /tmp and touch /tmp/wf-probe-1 commands", "", nil, current: false, muted: false),
                option(3, "Yes, and switch to auto mode", "auto mode handles these prompts for you", nil, current: false, muted: false),
                option(4, "No", "", nil, current: false, muted: false),
            ],
            menu.options
        )
    }

    func testARealRewindListPickedWithTheCursor() throws {
        let menu = try XCTUnwrap(Prompt.read(captured("claude-rewind-list.ansi", 54)))
        XCTAssertEqual([line("Rewind", false), line("Restore the code and/or conversation to the point before…", false)], menu.lines)
        XCTAssertEqual(
            [
                option(nil, "↑ 1 more", "", nil, current: false, muted: true, scroll: -1),
                option(nil, "say just: two", "No code changes", nil, current: false, muted: false),
                option(nil, "Use the Write tool to create /private/tmp/c…", "rw.txt +1", nil, current: true, muted: false),
                option(nil, "↓ 1 more", "", nil, current: false, muted: true, scroll: 1),
            ],
            menu.options.map { var o = $0; o.muted = o.scroll != 0; return o }
        )
        XCTAssertEqual(["Up", "Enter"], menu.keys(menu.options[1]))
        XCTAssertEqual(["Up", "Up"], menu.keys(menu.options[0]))
        XCTAssertEqual(["Down"], menu.keys(menu.options[3]))
    }

    func testARealRewindConfirmationQuotesItsMessage() throws {
        let menu = try XCTUnwrap(Prompt.read(captured("claude-rewind-confirm.ansi", 54)))
        XCTAssertEqual(
            ["Restore code and conversation", "Restore conversation", "Restore code", "Summarize from here", "Summarize up to here"],
            menu.options.map(\.label)
        )
        XCTAssertEqual([true, false, false, false, false], menu.options.map(\.current))
        XCTAssertEqual(["5"], menu.keys(menu.options[4]))
        XCTAssertEqual(
            "UsetheWritetooltocreate/private/tmp/claude-502/-Users-ahmedshaikh-dev-dotfiles/9fb53435-c5d1-4202-adc7-69292faff226/scratchpad/rw.txtcontaininghi,thensayjust:done",
            menu.quote.replacingOccurrences(of: " ", with: "")
        )
    }

    func testARewindListInFullscreenModeWithItsFootPastTheBottom() throws {
        let menu = try XCTUnwrap(Prompt.read(captured("claude-rewind-list-fullscreen.ansi", 52)))
        XCTAssertEqual([line("Rewind", false), line("Restore the code and/or conversation to the point before…", false)], menu.lines)
        XCTAssertEqual(["↑ 1 more", "say just: two", "(current)"], menu.options.map(\.label))
        XCTAssertEqual([-1, 0, 0], menu.options.map(\.scroll))
        XCTAssertEqual("No code changes", menu.options[1].detail)
        XCTAssertEqual(["Up", "Enter"], menu.keys(menu.options[1]))
    }

    func testARewindConfirmationInFullscreenModeIsAboutWhatsBelowItsRule() throws {
        let menu = try XCTUnwrap(Prompt.read(captured("claude-rewind-confirm-fullscreen.ansi", 52)))
        XCTAssertEqual("Rewind", menu.lines.first?.text)
        XCTAssertEqual(["Restore conversation", "Summarize from here", "Summarize up to here"], menu.options.map(\.label))
        XCTAssertEqual("say just: two", menu.quote)
    }

    func testARealQuestionWithDescriptions() throws {
        let menu = try XCTUnwrap(Prompt.read(captured("claude-question.ansi", 52)))
        XCTAssertEqual([tab("☒ Fullscreen", false), tab("☐ zsh hook", true), tab("✔ Submit", false)], menu.tabs)
        XCTAssertEqual([line("Where should the zsh side (the OSC 133 marks plus the command record) live?", false)], menu.lines)
        XCTAssertEqual(
            [
                option(
                    1, "wallflower repo (Recommended)",
                    "~/dev/wallflower/shell/wallflower.zsh holds the marks and the record. .zshrc keeps a single source line, and its OSC 133 lines move out. wallflower's needs stay in wallflower.",
                    nil, current: true, muted: false
                ),
                option(2, "Inline in .zshrc", "Add the record's few lines next to the OSC 133 lines already in your .zshrc.", nil, current: false, muted: false),
                option(3, "Type something.", "", nil, current: false, muted: true),
                option(4, "Chat about this", "", nil, current: false, muted: false),
            ],
            menu.options
        )
        XCTAssertEqual(["2"], menu.keys(menu.options[1]))
    }

    func testARealQuestionPreviewingItsOptionsBesideThem() throws {
        let menu = try XCTUnwrap(Prompt.read(captured("claude-question-previews.ansi", 52)))
        XCTAssertEqual([tab("☐ Fullscreen", true), tab("☐ zsh hook", false), tab("✔ Submit", false)], menu.tabs)
        XCTAssertEqual(
            [line("When a command goes fullscreen (nvim, htop, lazygit, git log's pager), what should a shell pane's read view do?", false)],
            menu.lines
        )
        XCTAssertEqual(
            [
                option(1, "Hand over to terminal (Recommended)", "", nil, current: true, muted: false),
                option(2, "Live preview card", "", nil, current: false, muted: false),
                option(3, "Plain card", "", nil, current: false, muted: false),
            ],
            menu.options
        )
        // A number only moves the cursor there.
        XCTAssertEqual(["3", "Enter"], menu.keys(menu.options[2]))
    }

    func testARealQuestionPreviewInAShortPane() throws {
        let menu = try XCTUnwrap(Prompt.read(captured("claude-question-previews-short.ansi", 52)))
        XCTAssertEqual([line("lazygit, git log's pager), what should a shell pane's read view do?", false)], menu.lines)
        XCTAssertEqual(["Hand over to terminal (Recommended)", "Live preview card", "Plain card"], menu.options.map(\.label))
        XCTAssertEqual(["2", "Enter"], menu.keys(menu.options[1]))
    }

    func testARealReviewOfAnswersQuotesTheQuestionItsOn() throws {
        let menu = try XCTUnwrap(Prompt.read(captured("claude-question-review.ansi", 52)))
        XCTAssertEqual(
            [
                "Review your answers",
                "● When a command goes fullscreen (nvim, htop, lazygit, git log's pager), what should a shell pane's read view do?",
                "→ Live preview card",
                "● Where should the zsh side (the OSC 133 marks plus the command record) live?",
                "→ Inline in .zshrc",
                "Ready to submit your answers?",
            ],
            menu.lines.map(\.text)
        )
        XCTAssertEqual(["Submit answers", "Cancel"], menu.options.map(\.label))
        XCTAssertEqual(["2"], menu.keys(menu.options[1]))
    }

    func testAMultipleChoicesNextAndSubmitStayWithTheCursorOnThem() throws {
        let labels = [
            ("claude-question-multi-next.ansi", ["Red", "Green", "Blue", "Type something", "Next", "Chat about this"]),
            ("claude-question-multi-submit.ansi", ["Apple", "Pear", "Plum", "Type something", "Submit", "Chat about this"]),
        ]
        for (name, options) in labels {
            let menu = try XCTUnwrap(Prompt.read(captured(name, 70)), name)
            XCTAssertEqual(options, menu.options.map(\.label), name)
            let item = menu.options[4]
            XCTAssertEqual(nil, item.number, name)
            XCTAssertEqual(true, item.current, name)
            XCTAssertEqual(["Enter"], menu.keys(item), name)
        }
    }

    func testAPromptsSuggestionsAreNotAList() {
        let s = screen(
            plain(rule),
            plain("❯ /rew"),
            plain(rule),
            plain("  ❯ /rewind   Restore the code and/or"),
            plain("              conversation to a previous po…"),
            plain("    /review   Review a pull request")
        )
        XCTAssertNil(Prompt.read(s))
    }

    func testANumberedListIsNotAMenu() {
        let s = screen(
            plain("⏺ Two steps:"),
            plain("  1. Edit the file"),
            plain("  2. Read it back"),
            plain(rule),
            plain("❯ "),
            plain(rule)
        )
        XCTAssertNil(Prompt.read(s))
    }

    func testARewindConfirmationWithNoCodeChangesQuotesItsMessage() throws {
        let menu = try XCTUnwrap(Prompt.read(captured("claude-rewind-confirm-nocode.ansi", 54)))
        XCTAssertEqual(["Restore conversation", "Summarize from here", "Summarize up to here", "Never mind"], menu.options.map(\.label))
        XCTAssertEqual("reply with just the word guava", menu.quote)
    }

    func testAQuestionsWrittenAnswerUnderTheCursorIsStillTheLesserOption() throws {
        let menu = try XCTUnwrap(Prompt.read(captured("claude-question-write.ansi", 54)))
        let write = try XCTUnwrap(menu.options.first { $0.label == "Type something." })
        XCTAssertEqual(true, write.current)
        XCTAssertEqual(true, write.muted)
        XCTAssertEqual(false, menu.options.first { $0.label == "Summer" }?.muted)
    }

    func testARewindListOfAMessageStartingOneIsStillTheList() throws {
        let list = try XCTUnwrap(Prompt.read(captured("claude-rewind-list-multiline.ansi", 54)))
        XCTAssertEqual(["↑ 7 more", "1. reply with just the word ok…", "(current)"], list.options.map(\.label))
        XCTAssertEqual([false, false, true], list.options.map(\.current))
        let confirm = try XCTUnwrap(Prompt.read(captured("claude-rewind-confirm-multiline.ansi", 54)))
        XCTAssertEqual("1. reply with just the word ok 2. nothing else", confirm.quote)
    }
}
