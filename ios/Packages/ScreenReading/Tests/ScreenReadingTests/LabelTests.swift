import XCTest
import ScreenReading

final class LabelTests: XCTestCase {
    func testClaudesTabIsItsConversationsNameWithoutTheSpinner() {
        XCTAssertEqual("wallflower-night", Claude.shared.label("✳ wallflower-night"))
        XCTAssertEqual("Fix the tests", Claude.shared.label("⠂ Fix the tests"))
        XCTAssertEqual(nil, Claude.shared.label("✳"))
        XCTAssertEqual(nil, Programs.of("zsh").label("~/dev"))
    }
}
