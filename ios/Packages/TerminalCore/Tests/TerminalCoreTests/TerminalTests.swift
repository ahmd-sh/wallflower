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
}
