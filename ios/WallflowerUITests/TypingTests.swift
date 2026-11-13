import XCTest

/**
 * The keyboard typing straight into a pane. The rig's session has a shell
 * on tab 0 and `cat > file` on tab 1; what arrived is checked from outside
 * (the pane, the file) against what the test wrote to WF_SHOTS.
 */
final class TypingTests: XCTestCase {
    private var shots: String?
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        let env = ProcessInfo.processInfo.environment
        shots = env["WF_SHOTS"] ?? env["TEST_RUNNER_WF_SHOTS"]
    }

    private func launch(tab: Int) {
        let env = ProcessInfo.processInfo.environment
        let port = env["WF_LISTEN"] ?? env["TEST_RUNNER_WF_LISTEN"] ?? "41778"
        app = XCUIApplication()
        app.launchArguments = ["-wfDev", port, "-wfSession", "$0", "-wfScript", "tab:\(tab)", "-wfStill", "YES"]
        app.launch()
        let allow = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Allow"]
        if allow.waitForExistence(timeout: 2) { allow.tap() }
        sleep(6)
        for _ in 0..<3 where !app.keyboards.firstMatch.exists {
            app.textViews.firstMatch.tap()
            _ = app.keyboards.firstMatch.waitForExistence(timeout: 3)
        }
        XCTAssert(app.keyboards.firstMatch.exists, "the keyboard's up")
        // Slide to type introduces itself the first time: offered, as in a text field.
        let intro = app.buttons["Continue"]
        if intro.waitForExistence(timeout: 2) { intro.tap() }
    }

    private func note(_ name: String, _ text: String) {
        guard let dir = shots else { return }
        try? text.write(to: URL(fileURLWithPath: dir).appendingPathComponent(name), atomically: true, encoding: .utf8)
    }

    private func shot(_ name: String) {
        guard let dir = shots else { return }
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("typing-\(name).png"))
    }

    /** Typed as fast as the keyboard goes, every character arrives, in order. */
    func testRapid() {
        launch(tab: 1)
        let chars = Array("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
        var g = SystemRandomNumberGenerator()
        let text = String((0..<200).map { _ in chars.randomElement(using: &g)! })
        note("rapid.txt", text)
        app.typeText(text)
        app.typeText("\n")
        sleep(3)
        shot("rapid")
    }

    /** At a shell's prompt, where what's typed shows ahead of its echo, the same. */
    func testRapidAtPrompt() {
        launch(tab: 0)
        let chars = Array("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
        var g = SystemRandomNumberGenerator()
        let text = String((0..<150).map { _ in chars.randomElement(using: &g)! })
        note("prompt.txt", text)
        app.typeText(text)
        sleep(3)
        shot("prompt")
    }

    /** ⌫ held deletes on and on, as a text field's does. */
    func testHoldDelete() {
        launch(tab: 0)
        app.typeText("abcdefghijklmnopqrstuvwxyz0123456789")
        sleep(2)
        shot("before-delete")
        let delete = app.keys["delete"]
        XCTAssert(delete.waitForExistence(timeout: 3), app.debugDescription)
        delete.press(forDuration: 3)
        sleep(2)
        shot("after-delete")
    }

    /** Held over a line the keyboard didn't type (the rig fills it first), ⌫ goes on too. */
    func testHoldDeleteOnTheLine() {
        launch(tab: 0)
        sleep(1)
        let delete = app.keys["delete"]
        XCTAssert(delete.waitForExistence(timeout: 3), app.debugDescription)
        delete.press(forDuration: 3)
        sleep(2)
        shot("after-delete-line")
    }
}
