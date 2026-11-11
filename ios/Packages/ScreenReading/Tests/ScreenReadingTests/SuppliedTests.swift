import XCTest
import ScreenReading

final class SuppliedTests: XCTestCase {
    override func tearDown() {
        Programs.supply([:])
        super.tearDown()
    }

    func testAPluginsProgramIsWhatItsDataSaysAndCantTakeABuiltinsPlace() {
        let aider = Supplied(keyBar: KeyDefaults(name: "aider", keys: ["Escape"]), commands: [Command(text: "/undo", about: "undo")], newlineKeys: ["M-Enter"], clearKeys: ["C-u"])
        Programs.supply(["Aider": aider, "claude": aider])
        XCTAssertIdentical(aider, Programs.of("aider"))
        XCTAssertIdentical(aider, Programs.supplied("aider"))
        XCTAssertIdentical(Claude.shared, Programs.of("claude"))
        XCTAssertNil(Programs.supplied("claude"))
        XCTAssertEqual(["M-Enter"], Programs.of("aider").newlineKeys)
        XCTAssertEqual(["/undo"], Programs.of("aider").commands.map(\.text))
        Programs.supply([:])
        XCTAssertNil(Programs.supplied("aider"))
    }
}
