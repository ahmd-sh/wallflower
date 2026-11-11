import Foundation
import XCTest
import ScreenReading

final class ScreenTests: XCTestCase {
    private func u16(_ v: Int) -> [UInt8] { [UInt8(v & 0xFF), UInt8(v >> 8)] }

    private func cell(_ attr: Int, _ codepoints: UInt32...) -> [UInt8] {
        [UInt8(attr), UInt8(codepoints.count)] + codepoints.flatMap { cp in (0..<4).map { UInt8((cp >> (8 * $0)) & 0xFF) } }
    }

    func testReadsTheNativeSnapshotLayout() throws {
        let bytes: [UInt8] = u16(3) + u16(2) + u16(1) + u16(1)
            + [1] + cell(Screen.prompt, 0x24) + cell(Screen.input, 0x1F600) + cell(Screen.input | Screen.spacer)
            + [0] + cell(Screen.faint | Screen.grey, 0x65, 0x301) + cell(Screen.muted | Screen.highlight) + cell(0, 0x2500)
        let s = try XCTUnwrap(Screen.parse(Data(bytes)))
        XCTAssertEqual([3, 2, 1, 1], [s.cols, s.rows, s.cursorX, s.cursorY])
        XCTAssertEqual(["$", "😀", "", "e\u{301}", "", "─"], (0..<2).flatMap { y in (0..<3).map { s.text($0, y) } })
        XCTAssertEqual([Screen.prompt, Screen.input, Screen.input], (0..<3).map { s.role($0, 0) })
        XCTAssertTrue(s.isSpacer(2, 0))
        XCTAssertTrue(s.isWrapped(0))
        XCTAssertFalse(s.isWrapped(1))
        XCTAssertTrue(s.isFaint(0, 1) && s.isGrey(0, 1))
        XCTAssertTrue(s.isMuted(1, 1) && s.isHighlighted(1, 1))
        XCTAssertFalse(s.isFaint(2, 1) || s.isGrey(2, 1) || s.isMuted(2, 1) || s.isHighlighted(2, 1) || s.isSpacer(2, 1))
        XCTAssertNil(Screen.parse(Array(bytes.dropLast())))
    }
}
