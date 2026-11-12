import XCTest

/**
 * The cmd key, where the multiplexer can press it: on a Rex pane (the rig's
 * WF_REX_SESSION, running `cat -v`) the bar editor offers it, the bar then
 * has it, and cmd then the pad's ← goes as ⌘← (^[[1;9D on the pane). On a
 * tmux pane with the same bar (tab 1, `cat -v`), there's no cmd.
 */
final class CmdTests: XCTestCase {
    private var shots: String?
    private var step = 0

    private func shot(_ name: String) {
        step += 1
        guard let dir = shots else { return }
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent(String(format: "cmd-%02d-%@.png", step, name)))
    }

    private func launch(_ args: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = args + ["-wfStill", "YES"]
        app.launch()
        let allow = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Allow"]
        if allow.waitForExistence(timeout: 2) { allow.tap() }
        sleep(6)
        return app
    }

    func testCmd() throws {
        let env = ProcessInfo.processInfo.environment
        shots = env["WF_SHOTS"] ?? env["TEST_RUNNER_WF_SHOTS"]
        let port = env["WF_LISTEN"] ?? env["TEST_RUNNER_WF_LISTEN"] ?? "41778"
        let rex = try XCTUnwrap(env["WF_REX_SESSION"] ?? env["TEST_RUNNER_WF_REX_SESSION"], "the rig's Rex session")

        var app = launch(["-wfDev", port, "-wfMux", "rex", "-wfSession", rex])
        shot("rex-bar")
        XCTAssertFalse(app.buttons["cmd"].exists, "not on the bar till it's added")
        app.buttons["escape"].swipeLeft() // the pencil, at the bar's end
        sleep(1)
        shot("bar-end")
        let edit = app.buttons["edit keys"]
        XCTAssert(edit.waitForExistence(timeout: 4), app.debugDescription)
        edit.tap()
        sleep(2)
        let offered = app.buttons["cmd + key"]
        XCTAssert(offered.waitForExistence(timeout: 3), "the editor offers cmd: Rex can press it")
        offered.tap() // scrolled to
        shot("added")
        sleep(1)
        app.buttons["back"].tap()
        sleep(2)
        let cmd = app.buttons["cmd"]
        XCTAssert(cmd.waitForExistence(timeout: 4), "the bar has cmd")
        if !cmd.isHittable { app.buttons["arrows, enter, backspace"].swipeLeft() }
        cmd.tap()
        shot("cmd-armed")
        let arrows = app.buttons["arrows, enter, backspace"]
        if !arrows.isHittable { cmd.swipeRight() }
        arrows.tap()
        sleep(1)
        app.buttons["left"].tap()
        sleep(1)
        app.buttons["put the arrows away"].tap()
        sleep(1)
        shot("sent")
        app.terminate()

        app = launch(["-wfDev", port, "-wfSession", "$0", "-wfScript", "tab:1"])
        shot("tmux-bar")
        XCTAssert(app.buttons["arrows, enter, backspace"].exists)
        XCTAssertFalse(app.buttons["cmd"].exists, "tmux can't press cmd")
    }
}
