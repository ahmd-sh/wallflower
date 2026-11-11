import XCTest

/**
 * Pairing from a link, as the Camera app opens one when it reads the code:
 * WF_PAIR is a code `wallflower pair` showed (skipped without one). The
 * app, set up fresh, asks first, pairs and connects; each step's screen
 * saved to WF_SHOTS if it's set.
 */
final class PairingTests: XCTestCase {
    private var shots: String?
    private var step = 0

    private func shot(_ name: String) {
        step += 1
        guard let dir = shots else { return }
        let png = XCUIScreen.main.screenshot().pngRepresentation
        try? png.write(to: URL(fileURLWithPath: dir).appendingPathComponent(String(format: "pair-%02d-%@.png", step, name)))
    }

    func testPairFromLink() throws {
        let env = ProcessInfo.processInfo.environment
        guard let code = env["WF_PAIR"] ?? env["TEST_RUNNER_WF_PAIR"], let link = URL(string: code) else {
            throw XCTSkip("no WF_PAIR code")
        }
        shots = env["WF_SHOTS"] ?? env["TEST_RUNNER_WF_SHOTS"]
        let app = XCUIApplication()
        app.launchArguments = ["-wfStill", "YES"]
        app.launch()
        sleep(2)
        shot("setup")

        XCUIDevice.shared.system.open(link)
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let open = springboard.buttons["Open"]
        if open.waitForExistence(timeout: 3) { open.tap() }
        let pair = app.alerts.buttons["pair"]
        XCTAssertTrue(pair.waitForExistence(timeout: 5), "no question before pairing")
        shot("asked")
        pair.tap()
        sleep(1)
        shot("pairing")
        let allow = springboard.buttons["Allow"]
        if allow.waitForExistence(timeout: 8) { allow.tap() }
        sleep(6)
        shot("connected")
    }
}
