import XCTest

/**
 * Selecting in the terminal: held on a word, it's selected and the edit menu
 * offers Copy and Select All; held and dragged, the selection takes in more;
 * its end handle drags; a tap away lets it go. The rig's pane (WF_SESSION,
 * tab 0) ends with "last words on screen: alpha beta gamma" above the
 * cursor; WF_ROW is that row's middle and WF_CELL a cell's width, in points
 * from the screen's top left, with the grid starting WF_LEFT in.
 */
final class SelectTests: XCTestCase {
    private var shots: String?
    private var step = 0

    private func shot(_ name: String) {
        step += 1
        guard let dir = shots else { return }
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent(String(format: "select-%02d-%@.png", step, name)))
    }

    func testSelect() throws {
        let env = ProcessInfo.processInfo.environment
        func value(_ k: String) -> String? { env[k] ?? env["TEST_RUNNER_" + k] }
        shots = value("WF_SHOTS")
        let port = value("WF_LISTEN") ?? "41781"
        let session = value("WF_SESSION") ?? "$0"
        let row = Double(value("WF_ROW") ?? "741")!
        let cellWidth = Double(value("WF_CELL") ?? "7.63")!
        let left = Double(value("WF_LEFT") ?? "4")!

        let app = XCUIApplication()
        app.launchArguments = ["-wfDev", port, "-wfSession", session, "-wfStill", "YES"]
        app.launch()
        let allow = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Allow"]
        if allow.waitForExistence(timeout: 2) { allow.tap() }
        sleep(6)
        let origin = app.coordinate(withNormalizedOffset: .zero)
        func at(col: Double, dy: Double = 0) -> XCUICoordinate { origin.withOffset(CGVector(dx: left + (col + 0.5) * cellWidth, dy: row + dy)) }
        shot("start")

        // Held on "beta" (columns 28-31): it's selected, and the menu offers to copy it.
        at(col: 29).press(forDuration: 1.0)
        let copy = app.menuItems["Copy"].exists ? app.menuItems["Copy"] : app.buttons["Copy"]
        XCTAssert(copy.waitForExistence(timeout: 3), "the menu offers Copy: \(app.debugDescription)")
        XCTAssert(app.menuItems["Select All"].exists || app.buttons["Select All"].exists)
        shot("word")

        // Its end handle (below the row, at the word's right edge) dragged to the end of "gamma".
        at(col: 31.5, dy: 15).press(forDuration: 0.05, thenDragTo: at(col: 37, dy: 15))
        sleep(1)
        shot("handle")
        XCTAssert(app.menuItems["Copy"].waitForExistence(timeout: 3) || app.buttons["Copy"].exists, "the menu's back once the handle's let go")

        // A tap away lets it go.
        origin.withOffset(CGVector(dx: 200, dy: 400)).tap()
        sleep(1)
        shot("let-go")
        XCTAssertFalse(app.menuItems["Copy"].exists || app.buttons["Copy"].exists)

        // Held on "alpha" and dragged on to "beta": both.
        at(col: 23).press(forDuration: 1.0, thenDragTo: at(col: 30))
        XCTAssert(app.menuItems["Copy"].waitForExistence(timeout: 3) || app.buttons["Copy"].exists)
        shot("dragged")
        (app.menuItems["Copy"].exists ? app.menuItems["Copy"] : app.buttons["Copy"]).tap()
        sleep(1)
        shot("copied")
        XCTAssertFalse(app.menuItems["Copy"].exists || app.buttons["Copy"].exists, "copied, the selection's let go")

        // Held past the end of a row near the top, where there's no word: the menu, with nothing to copy. The
        // clipboard has text now, so the keyboard comes up to paste with (the first time, over its own intro).
        let blank = origin.withOffset(CGVector(dx: left + 44.5 * cellWidth, dy: 400))
        blank.press(forDuration: 1.0)
        sleep(2)
        if app.buttons["Continue"].exists {
            app.buttons["Continue"].tap()
            sleep(1)
        }
        func item(_ name: String) -> XCUIElement { app.menuItems[name].exists ? app.menuItems[name] : app.buttons[name] }
        if !item("Select All").exists { blank.press(forDuration: 1.0) } // the keyboard coming up took the menu away
        XCTAssert(item("Select All").waitForExistence(timeout: 3))
        XCTAssert(item("Paste").exists, "with the clipboard's text to paste")
        XCTAssertFalse(item("Copy").exists)
        shot("blank")
        item("Select All").tap()
        sleep(1)
        XCTAssert(item("Copy").waitForExistence(timeout: 3), "all of it selected, to copy")
        shot("all")
        item("Copy").tap()
        sleep(1)
        XCTAssertFalse(item("Copy").exists)
    }
}
