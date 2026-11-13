import Foundation
import ScreenReading
import XCTest

final class KeyboardTests: XCTestCase {
    private func edit(_ old: String, _ new: String) -> KeyboardText.Edit { .init(from: old, to: new) }

    func testTypingOnTheEndTypesJustThat() {
        XCTAssertEqual(edit("ls", "ls "), .init(deletes: 0, typed: " "))
        XCTAssertEqual(edit("", "hello"), .init(deletes: 0, typed: "hello"))
        XCTAssert(edit("same", "same").isEmpty)
    }

    func testAWordPutRightDeletesBackToWhereItParts() {
        // Autocorrect, a swiped word's other pick, dictation hearing it better.
        XCTAssertEqual(edit("git cmomit", "git commit"), .init(deletes: 5, typed: "ommit"))
        XCTAssertEqual(edit("hello word", "hello world"), .init(deletes: 1, typed: "ld"))
        XCTAssertEqual(edit("abc", ""), .init(deletes: 3, typed: ""))
    }

    func testDeletesCountCharactersAsALineEditorDoes() {
        // A line editor takes a character (a code point) per ⌫, so a flag's two go as two.
        XCTAssertEqual(edit("hi 🇨🇦", "hi "), .init(deletes: 2, typed: ""))
        XCTAssertEqual(edit("cafe", "café"), .init(deletes: 1, typed: "é"))
    }

    func testTheKeyboardsTextFollowsItsEdits() {
        var k = KeyboardText()
        k.insert("helo")
        k.select(NSRange(location: 3, length: 0))
        k.insert("l")
        XCTAssertEqual(k.text, "hello")
        XCTAssertEqual(k.selection, NSRange(location: 4, length: 0))
        XCTAssert(k.deleteBackward())
        XCTAssertEqual(k.text, "helo")
        k = KeyboardText()
        XCTAssertFalse(k.deleteBackward(), "nothing to delete: the pane's own ⌫")
        k.insert("hi 🇨🇦")
        XCTAssert(k.deleteBackward())
        XCTAssertEqual(k.text, "hi ", "the flag goes whole")
    }

    func testComposingReplacesWhatItComposedTillItsDone() {
        var k = KeyboardText()
        k.insert("say ")
        k.setMarked("hel", selected: NSRange(location: 3, length: 0))
        k.setMarked("hello", selected: NSRange(location: 5, length: 0))
        XCTAssertEqual(k.text, "say hello")
        XCTAssertEqual(k.marked, NSRange(location: 4, length: 5))
        k.insert("hello ") // committed
        XCTAssertEqual(k.text, "say hello ")
        XCTAssertNil(k.marked)
        k.setMarked("x", selected: NSRange(location: 1, length: 0))
        k.setMarked("", selected: NSRange(location: 0, length: 0))
        XCTAssertEqual(k.text, "say hello ")
        XCTAssertNil(k.marked)
    }

    func testRangesOutsideTheTextAreKeptInside() {
        var k = KeyboardText()
        k.insert("ab")
        k.replace(NSRange(location: 1, length: 99), with: "c")
        XCTAssertEqual(k.text, "ac")
        XCTAssertEqual(k.text(in: NSRange(location: 5, length: 3)), "")
    }
}
