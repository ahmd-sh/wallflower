import XCTest

/**
 * Codex's agents in its mobile view, against `wallflower listen` on a
 * throwaway server (WF_LISTEN, default 41778) with one session, of one
 * window, Codex, up on its agent command center, with tasks named "build
 * fix" and "docs pass" among them; each step's screen saved to WF_SHOTS if
 * it's set.
 */
final class AgentsTests: XCTestCase {
    private var app: XCUIApplication!
    private var shots: String?
    private var step = 0

    override func setUp() {
        continueAfterFailure = true
        let env = ProcessInfo.processInfo.environment
        shots = env["WF_SHOTS"] ?? env["TEST_RUNNER_WF_SHOTS"]
        app = XCUIApplication()
        let port = env["WF_LISTEN"] ?? env["TEST_RUNNER_WF_LISTEN"] ?? "41778"
        app.launchArguments = ["-wfDev", port, "-wfStill", "YES"]
    }

    private func shot(_ name: String) {
        step += 1
        guard let dir = shots else { return }
        let png = XCUIScreen.main.screenshot().pngRepresentation
        try? png.write(to: URL(fileURLWithPath: dir).appendingPathComponent(String(format: "agents-%02d-%@.png", step, name)))
    }

    private func at(_ x: CGFloat, _ y: CGFloat) -> XCUICoordinate {
        app.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y))
    }

    private func button(_ label: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", label)).firstMatch
    }

    /**
     * The list takes the conversation's place; a filter tapped filters it,
     * a task tapped opens it (its conversation in the list's place), and the
     * shortcuts' "agents" brings the list back, which "back" leaves.
     */
    func testAgents() {
        app.launch()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = springboard.buttons["Allow"]
        if allow.waitForExistence(timeout: 2) { allow.tap() }
        let session = button("1 tab")
        if session.waitForExistence(timeout: 8) { session.tap() }
        sleep(6)
        if !button("new task").exists { at(0.955, 0.148).tap() } // the mobile view, if it isn't on
        sleep(2)
        shot("list")
        XCTAssertTrue(button("new task").exists)

        let ready = button("ready")
        if ready.waitForExistence(timeout: 2) { ready.tap() }
        sleep(2)
        shot("ready")
        let all = button("all")
        if all.waitForExistence(timeout: 2) { all.tap() }
        sleep(2)

        let docs = button("docs pass")
        XCTAssertTrue(docs.waitForExistence(timeout: 3))
        docs.tap()
        sleep(4)
        shot("opened")
        XCTAssertFalse(button("new task").exists)

        at(0.2, 0.914).tap() // the shortcuts key, after the +
        sleep(1)
        shot("shortcuts")
        let agents = button("agents")
        if agents.waitForExistence(timeout: 2) { agents.tap() }
        sleep(3)
        shot("list-again")
        XCTAssertTrue(button("new task").waitForExistence(timeout: 3))

        button("back").tap()
        sleep(3)
        shot("back")
    }
}
