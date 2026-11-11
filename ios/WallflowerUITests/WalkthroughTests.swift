import XCTest

/**
 * The app walked through by touch, against `wallflower listen` on a
 * throwaway server (WF_LISTEN, default 41778), each step's screen saved to
 * WF_SHOTS if it's set: what the screens look like, and that the gestures
 * (pane swipes, the arrow cluster's slide, menus) do what they should.
 */
final class WalkthroughTests: XCTestCase {
    private var app: XCUIApplication!
    private var shots: String?
    private var step = 0

    override func setUp() {
        continueAfterFailure = true
        let env = ProcessInfo.processInfo.environment
        shots = env["WF_SHOTS"] ?? env["TEST_RUNNER_WF_SHOTS"]
        app = XCUIApplication()
        let port = env["WF_LISTEN"] ?? env["TEST_RUNNER_WF_LISTEN"] ?? "41778"
        app.launchArguments = ["-wfDev", port, "-wfSession", "$0", "-wfScript", "tab:1", "-wfStill", "YES"]
        addUIInterruptionMonitor(withDescription: "notifications") { alert in
            let allow = alert.buttons["Allow"]
            if allow.exists {
                allow.tap()
                return true
            }
            return false
        }
    }

    private func shot(_ name: String) {
        step += 1
        guard let dir = shots else { return }
        let png = XCUIScreen.main.screenshot().pngRepresentation
        try? png.write(to: URL(fileURLWithPath: dir).appendingPathComponent(String(format: "%02d-%@.png", step, name)))
    }

    private func at(_ x: CGFloat, _ y: CGFloat) -> XCUICoordinate {
        app.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y))
    }

    private func allowNotifications() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = springboard.buttons["Allow"]
        if allow.waitForExistence(timeout: 2) { allow.tap() }
    }

    /**
     * The shortcuts key: pressed, its shortcuts come up on a panel of their
     * own, three rows at most, the rest a swipe away; "add shortcut" opens
     * their page. (On a shell pane, tab 0; past three rows if the bar has
     * enough of the user's own.)
     */
    func testShortcuts() {
        app.launchArguments = app.launchArguments.map { $0 == "tab:1" ? "tab:0" : $0 }
        app.launch()
        allowNotifications()
        sleep(6)
        shot("bar")
        at(0.085, 0.92).tap()
        sleep(1)
        shot("shortcuts")
        at(0.85, 0.80).press(forDuration: 0.05, thenDragTo: at(0.15, 0.80), withVelocity: .fast, thenHoldForDuration: 0)
        sleep(1)
        shot("shortcuts-swiped")
        let add = app.buttons.matching(NSPredicate(format: "label CONTAINS 'add shortcut'")).firstMatch
        if add.waitForExistence(timeout: 2) { add.tap() }
        sleep(2)
        shot("shortcuts-page")

        // Back, then pressed and slid onto the first (a shell's clear), it goes and they shut.
        at(0.065, 0.098).tap()
        sleep(2)
        at(0.085, 0.92).press(forDuration: 0.3, thenDragTo: at(0.156, 0.727))
        sleep(2)
        shot("shortcut-slid")
    }

    func testWalkthrough() {
        app.launch()
        allowNotifications()
        sleep(6)
        shot("terminal")

        // Tabs: held, rename and close.
        at(0.175, 0.098).press(forDuration: 1.0)
        sleep(1)
        shot("tab-menu")
        at(0.5, 0.6).tap() // away from the menu
        sleep(1)

        // The arrow cluster: pressed, it bubbles up; slid onto ↑ and let go, it sends and shuts.
        let cluster = at(0.215, 0.92)
        cluster.press(forDuration: 0.05)
        sleep(1)
        shot("cluster-open")
        at(0.5, 0.97).tap()
        sleep(1)
        cluster.press(forDuration: 0.3, thenDragTo: at(0.215, 0.79))
        sleep(1)
        shot("cluster-slid")

        // A second pane, then a swipe between them.
        at(0.5, 0.4).tap()
        sleep(1)
        shot("keyboard")
        app.swipeDown()
        sleep(1)
        at(0.5, 0.5).press(forDuration: 0.05, thenDragTo: at(0.05, 0.5), withVelocity: .fast, thenHoldForDuration: 0)
        sleep(2)
        shot("swiped")

        // The overview of panes, from the dots.
        at(0.93, 0.145).tap()
        sleep(2)
        shot("overview")
        at(0.25, 0.25).tap()
        sleep(2)

        // Claude's window: its mobile view, a fold opened.
        at(0.58, 0.098).tap()
        sleep(3)
        shot("mobile")
        let steps = app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'steps'")).firstMatch
        if steps.waitForExistence(timeout: 2) {
            steps.tap()
            sleep(2)
            shot("steps-open")
        }
        app.swipeDown()
        sleep(1)
        shot("mobile-scrolled")

        // The + menu.
        at(0.085, 0.92).tap()
        sleep(2)
        shot("plus-menu")
        at(0.5, 0.2).tap()
        sleep(1)

        // Away: the Live Activity in the Dynamic Island, and on the Home Screen.
        XCUIDevice.shared.press(.home)
        sleep(4)
        shot("island")
        app.activate()
        sleep(2)
        shot("back")
    }
}

extension WalkthroughTests {
    /** Claude at work, and the app left: the Live Activity in the Dynamic Island, and on the Lock Screen. */
    func testLiveActivity() {
        app.launchArguments = ["-wfDev", ProcessInfo.processInfo.environment["WF_LISTEN"] ?? "41778", "-wfSession", "$0", "-wfScript", "tab:2;mobile:on", "-wfStill", "YES"]
        app.launch()
        allowNotifications()
        sleep(6)
        shot("working")
        XCUIDevice.shared.press(.home)
        sleep(3)
        shot("island")
        XCUIDevice.shared.perform(NSSelectorFromString("pressLockButton"))
        sleep(2)
        XCUIDevice.shared.press(.home) // wakes it to the Lock Screen
        sleep(2)
        shot("lock-screen")
        let allow = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Allow"]
        if allow.waitForExistence(timeout: 2) {
            allow.tap()
            sleep(2)
            shot("lock-screen-allowed")
        }
    }
}

extension WalkthroughTests {
    /** Away a while, past when iOS would otherwise freeze the app: what a program notifies still arrives, as a notification. */
    func testNotifiedWhileAway() {
        app.launchArguments = ["-wfDev", ProcessInfo.processInfo.environment["WF_LISTEN"] ?? "41778", "-wfSession", "$0", "-wfScript", "tab:0", "-wfStill", "YES"]
        app.launch()
        allowNotifications()
        sleep(4)
        XCUIDevice.shared.press(.home)
        sleep(80)
        shot("notified")
        sleep(6)
        shot("notified-later")
    }
}

extension WalkthroughTests {
    /** Lets the app notify, as a first run asks, so later runs aren't covered by the question. */
    func testAllowNotifications() {
        app.launch()
        allowNotifications()
        sleep(2)
    }
}

extension WalkthroughTests {
    /** The composer's +: photos, files and the program's commands, its cross turned. */
    func testPlusMenu() {
        app.launchArguments = ["-wfDev", ProcessInfo.processInfo.environment["WF_LISTEN"] ?? "41778", "-wfSession", "$0", "-wfScript", "tab:2;mobile:on;draft:a draft for claude", "-wfStill", "YES"]
        app.launch()
        sleep(9)
        shot("draft")
        at(0.085, 0.92).tap()
        sleep(2)
        shot("plus-menu")
    }
}

extension WalkthroughTests {
    /** Opens a fold in the mobile view and scrolls, then leaves the app be: for watching what it does after. */
    func testFoldThenScroll() {
        app.launchArguments = ["-wfDev", ProcessInfo.processInfo.environment["WF_LISTEN"] ?? "41778", "-wfSession", "$0", "-wfScript", "tab:2;mobile:on", "-wfStill", "YES"]
        app.launch()
        for t in 1...4 {
            sleep(3)
            shot("t\(t * 3)")
        }
        let steps = app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'steps'")).firstMatch
        if steps.waitForExistence(timeout: 5) { steps.tap() }
        sleep(2)
        shot("fold-open")
        app.swipeDown()
        sleep(45)
        shot("after")
    }
}

extension WalkthroughTests {
    /** Swipes between a window's two panes, without the keyboard and with it. */
    func testSwipes() {
        app.launch()
        sleep(7)
        shot("start")
        let mid = at(0.5, 0.35)
        mid.press(forDuration: 0.05, thenDragTo: at(0.02, 0.35), withVelocity: .fast, thenHoldForDuration: 0)
        sleep(2)
        shot("swiped-once")
        mid.press(forDuration: 0.05, thenDragTo: at(0.02, 0.35), withVelocity: .fast, thenHoldForDuration: 0)
        sleep(2)
        shot("swiped-twice")
        mid.tap()
        sleep(2)
        mid.press(forDuration: 0.05, thenDragTo: at(0.98, 0.35), withVelocity: .fast, thenHoldForDuration: 0)
        sleep(2)
        shot("swiped-typing")
    }
}

extension WalkthroughTests {
    /** Held, a terminal offers to paste what's on the clipboard into its pane. */
    func testPaste() {
        UIPasteboard.general.string = "echo pasted from the phone"
        app.launch()
        sleep(6)
        at(0.31, 0.098).tap() // the shell's tab
        sleep(2)
        at(0.5, 0.4).press(forDuration: 0.8)
        sleep(1)
        shot("paste-menu")
        let paste = app.menuItems["Paste"].firstMatch
        if paste.waitForExistence(timeout: 3) {
            paste.tap()
            sleep(2)
        }
        shot("pasted")
        at(0.5, 0.4).press(forDuration: 0.8)
        sleep(1)
        shot("copy-menu") // reading the clipboard back here would ask the test runner's own paste permission
        XCTAssertTrue(app.menuItems["Copy screen"].firstMatch.waitForExistence(timeout: 3))
    }
}
