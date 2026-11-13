import XCTest
@testable import TerminalCore

final class TerminalTests: XCTestCase {
    func testWritesAndPacksAFrame() {
        let t = Terminal(cols: 20, rows: 4)
        t.write(Data("hello \u{1b}[1mworld\u{1b}[0m\r\n✻ é".utf8))
        let f = FrameBuffer()
        XCTAssertTrue(t.frame(into: f, force: true))
        XCTAssertTrue(f.full)
        XCTAssertEqual(f.cols, 20)
        XCTAssertEqual(f.rows, 4)
        XCTAssertEqual(f.rowCount, 4)
        let row0 = (0..<11).map { f.text(0, $0) }.joined()
        XCTAssertEqual(row0, "hello world")
        XCTAssertNotEqual(f.flags(0, 6) & FrameBuffer.FLAG_BOLD, 0)
        XCTAssertEqual(f.text(1, 0), "✻")
        XCTAssertEqual(f.cursorY, 1)
        XCTAssertFalse(t.frame(into: f), "nothing changed since")
        t.write(Data("!".utf8))
        XCTAssertTrue(t.frame(into: f))
        XCTAssertFalse(f.full)
        XCTAssertEqual(f.rowCount, 1)
        XCTAssertEqual(f.rowY(0), 1)
    }

    func testScreenSnapshot() {
        let t = Terminal(cols: 10, rows: 2)
        t.write(Data("ab".utf8))
        let s = t.screenBytes()!
        XCTAssertEqual(s[0], 10)
        XCTAssertEqual(s[2], 2)
        XCTAssertEqual(s[4], 2) // cursor x
    }

    func testWheelOnThePrimaryScreenIsLocal() {
        let t = Terminal(cols: 10, rows: 2)
        XCTAssertEqual(t.wheel(up: true, col: 0, row: 0), .local)
        t.write(Data("\u{1b}[?1049h".utf8))
        XCTAssertEqual(t.wheel(up: true, col: 0, row: 0), .arrows)
    }

    func testSelectsTheWordUnderACell() {
        let t = Terminal(cols: 40, rows: 4)
        t.write(Data("see src/main.go:12 now\r\n(https://x.dev/a?b=1)".utf8))
        XCTAssertTrue(t.selectWord(col: 6, row: 0))
        XCTAssertEqual(t.selectionText(), "src/main.go:12", "a path keeps its slashes and colons")
        XCTAssertEqual(t.selection(), Terminal.Selection(startCol: 4, startRow: 0, endCol: 17, endRow: 0))
        XCTAssertTrue(t.selectWord(col: 10, row: 1))
        XCTAssertEqual(t.selectionText(), "https://x.dev/a?b=1", "brackets end it")
        XCTAssertFalse(t.selectWord(col: 30, row: 0), "nothing to select in blank cells")
    }

    func testDragExtendsFromTheWord() {
        let t = Terminal(cols: 40, rows: 4)
        t.write(Data("one two three\r\nfour five".utf8))
        XCTAssertTrue(t.selectWord(col: 5, row: 0)) // two
        t.selectExtend(col: 6, row: 1) // into five
        XCTAssertEqual(t.selectionText(), "two three\nfour fi")
        t.selectExtend(col: 1, row: 0) // back before it: the word stays in
        XCTAssertEqual(t.selectionText(), "ne two")
        t.selectExtend(col: 5, row: 0)
        XCTAssertEqual(t.selectionText(), "two")
    }

    func testHandlesMoveOneEnd() {
        let t = Terminal(cols: 40, rows: 4)
        t.write(Data("alpha beta gamma".utf8))
        XCTAssertTrue(t.selectWord(col: 7, row: 0)) // beta
        t.selectHold(last: true)
        t.selectTo(col: 15, row: 0)
        XCTAssertEqual(t.selectionText(), "beta gamma")
        t.selectHold(last: false)
        t.selectTo(col: 0, row: 0)
        XCTAssertEqual(t.selectionText(), "alpha beta gamma")
        t.selectTo(col: 15, row: 0) // past the other end: it turns around
        XCTAssertEqual(t.selectionText(), "a")
        t.unselect()
        XCTAssertNil(t.selection())
        XCTAssertNil(t.selectionText())
    }

    func testCopyJoinsWrappedRowsAndTrims() {
        let t = Terminal(cols: 10, rows: 4)
        t.write(Data("abcdefghijklmn   \r\nxy   ".utf8))
        t.selectView()
        XCTAssertEqual(t.selectionText()?.trimmingCharacters(in: .newlines), "abcdefghijklmn\nxy")
    }

    func testSelectionStaysOnItsTextAsItScrolls() {
        let t = Terminal(cols: 20, rows: 3)
        t.write(Data("first\r\nsecond".utf8))
        XCTAssertTrue(t.selectWord(col: 1, row: 1))
        t.write(Data("\r\nthird\r\nfourth".utf8)) // second scrolls up a row
        XCTAssertEqual(t.selection(), Terminal.Selection(startCol: 0, startRow: 0, endCol: 5, endRow: 0))
        t.write(Data("\r\nfifth".utf8))
        XCTAssertEqual(t.selection()?.startRow, -1, "above the view, still kept")
        XCTAssertEqual(t.selectionText(), "second")
        t.scrollBy(-1)
        XCTAssertEqual(t.selection()?.startRow, 0)
    }
}
