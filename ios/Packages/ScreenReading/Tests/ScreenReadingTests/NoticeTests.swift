import XCTest
import ScreenReading

final class NoticeTests: XCTestCase {
    private let input = Notice(title: "Claude Code", body: "Claude needs your input")

    private func plain(_ rows: String...) -> Screen {
        let cols = 40
        let units = rows.map { Array($0.utf16) }
        let text = (0..<(cols * rows.count)).map { i -> String in
            let row = units[i / cols], x = i % cols
            guard x < row.count, row[x] != 0x20 else { return "" }
            return String(decoding: [row[x]], as: UTF16.self)
        }
        return Screen(cols: cols, rows: rows.count, cursorX: 2, cursorY: 2, text: text, attrs: [Int](repeating: 0, count: cols * rows.count), wrapped: [Bool](repeating: false, count: rows.count))
    }

    private let rule = String(repeating: "─", count: 40)

    func testClaudeAskingAnythingIsOneNotice() {
        let asking = [
            ("claude-bash-permission.ansi", 54),
            ("claude-plan-approval.ansi", 54),
            ("claude-question.ansi", 52),
            ("claude-question-review.ansi", 52),
        ]
        for (capture, cols) in asking {
            XCTAssertEqual(input, Claude.shared.notice(title: "Claude Code", body: "Claude needs your permission", screen: captured(capture, cols)), capture)
            XCTAssertEqual(input, Claude.shared.notice(title: nil, body: nil, screen: captured(capture, cols)), "\(capture), by the bell")
        }
    }

    func testClaudeWithItsPromptBoxUpIsNone() {
        let idle = plain("● Done.", rule, "❯ ", rule, "  -- INSERT --")
        for body in ["Claude is waiting for your input", "Explore agent finished", "the build passed", nil] {
            XCTAssertNil(Claude.shared.notice(title: "Claude Code", body: body, screen: idle), String(describing: body))
        }
    }

    func testClaudeWhoseScreenCouldntBeReadIsOneNotice() {
        XCTAssertEqual(input, Claude.shared.notice(title: "Claude Code", body: "Claude is waiting for your input", screen: nil))
    }

    func testOnlyClaudeReadsItsScreenAndOthersShowAsTheyAreButForTheBell() {
        let zsh = Programs.of("zsh")
        XCTAssertTrue(Claude.shared.readsScreenToNotify)
        XCTAssertFalse(zsh.readsScreenToNotify)
        XCTAssertEqual(Notice(title: "Tests", body: "all 41 pass"), zsh.notice(title: "Tests", body: "all 41 pass", screen: nil))
        XCTAssertNil(zsh.notice(title: nil, body: nil, screen: nil))
    }
}
