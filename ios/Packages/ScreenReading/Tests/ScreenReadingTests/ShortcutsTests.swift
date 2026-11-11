import Foundation
import XCTest
import ScreenReading

final class ShortcutsTests: XCTestCase {
    private let bar = KeyBar.defaults

    func testClaudeShipsItsKeysThenItsCommands() {
        let claude = bar.profile(for: "claude", program: "claude").shortcuts(for: Programs.of("claude"))
        XCTAssertEqual(
            [
                "transcript", "mode", "todos", "background", "stash", "send now", "redraw",
                "/compact ⏎", "/clear ⏎", "/context ⏎", "/model ⏎", "/rewind ⏎", "/usage ⏎", "/diff ⏎", "/resume ⏎", "/tasks ⏎", "/recap ⏎", "/plan ⏎", "/btw",
            ],
            claude.map(\.label)
        )
        XCTAssertEqual(["C-o"], claude.first?.keys)
        XCTAssertEqual(["C-x", "C-s"], claude.first { $0.label == "send now" }?.keys)
        XCTAssertEqual(["/compact", "Enter"], claude.first { $0.label == "/compact ⏎" }?.keys)
        // A command's label is its keys', so it shows as them.
        XCTAssertEqual(true, claude.first { $0.label == "/compact ⏎" }?.hasOwnLabel)
        XCTAssertEqual(false, claude.first?.hasOwnLabel)
    }

    func testShellsShipCommandsThatWorkInAny() {
        let zsh = bar.profile(for: "zsh", program: "zsh").shortcuts(for: Programs.of("zsh"))
        XCTAssertEqual(["clear ⏎", "cd - ⏎", "!! ⏎", "suspend", "fg ⏎"], zsh.map(\.label))
        XCTAssertEqual(zsh, bar.profile(for: "bash", program: "shell").shortcuts(for: Programs.of("shell")))
        XCTAssertEqual([], bar.default.shortcuts(for: Programs.of("hntui")))
    }

    func testTheUsersComeAfterTheProgramsTheyDidntHide() {
        let mine = BarKey(label: "review", keys: ["/review", "Enter"])
        var claude = bar.profile(for: "claude", program: "claude")
        claude.shortcuts = [mine]
        claude.hidden = ["redraw", "/plan ⏎"]
        let offered = claude.shortcuts(for: Programs.of("claude"))
        XCTAssertEqual(mine, offered.last)
        XCTAssertFalse(offered.contains { $0.label == "redraw" || $0.label == "/plan ⏎" })
        XCTAssertEqual(19 - 2 + 1, offered.count)
    }

    func testAPluginsShortcutsComeWithIt() {
        let aider = Supplied(
            keyBar: KeyDefaults(name: "aider", keys: ["Escape", "shortcuts"]), commands: [], newlineKeys: nil, clearKeys: [],
            shortcuts: [Shortcut(["/undo", "Enter"]), Shortcut(["C-c", "C-c"], label: "quit")]
        )
        Programs.supply(["aider": aider])
        defer { Programs.supply([:]) }
        var withMine = bar
        withMine.default.shortcuts = [BarKey(label: "hi", keys: ["hi"])]
        let p = withMine.profile(for: "python3", program: "aider")
        XCTAssertTrue(p.keys[1].shortcuts) // the shortcuts key, by name
        // Until it's a profile of its own, what's the default's is its.
        XCTAssertEqual(["/undo ⏎", "quit", "hi"], p.shortcuts(for: Programs.of("aider")).map(\.label))
    }

    func testTheShortcutsKeyOffersThemAndSendsNothing() {
        let key = KeyBar.common.first { $0.shortcuts }
        XCTAssertEqual(KeyBar.shortcutsKey, key)
        XCTAssertEqual([], key?.keys)
        XCTAssertEqual("shortcuts", KeyBar.name(of: KeyBar.shortcutsKey))
        XCTAssertTrue(KeyBar.shortcutsKey.hasOwnLabel)
    }

    func testKeysAreTheirNamesAndTheRestIsText() {
        for k in ["Enter", "Escape", "BTab", "C-o", "M-Enter", "C-M-Left", "S-Up", "F5", "F12", "M-.", "C-_", "prefix", "DC"] { XCTAssertTrue(KeyBar.isKey(k), k) }
        for t in ["y", "1", "/compact", ":w", "fg", "cd -", "C-", "F13", "enter", "Ctrl-c"] { XCTAssertFalse(KeyBar.isKey(t), t) }
    }

    func testTheWordsBetweenKeysAreOneText() {
        XCTAssertEqual(["/model opus", "Enter"], KeyBar.keys(of: "/model opus Enter"))
        XCTAssertEqual(["Escape", ":w", "Enter"], KeyBar.keys(of: "Escape :w Enter"))
        XCTAssertEqual(["C-x", "C-s"], KeyBar.keys(of: "  C-x   C-s "))
        XCTAssertEqual(["prefix", "c"], KeyBar.keys(of: "prefix c"))
        XCTAssertEqual(["/btw"], KeyBar.keys(of: "/btw"))
        XCTAssertEqual([], KeyBar.keys(of: "   "))
        // What a shortcut sends comes back as written.
        let keys = ["Escape", "git commit -m wip", "Enter"]
        XCTAssertEqual(keys, KeyBar.keys(of: keys.joined(separator: " ")))
    }

    func testTextGoesWithItsEnterAMomentAfter() {
        XCTAssertEqual([.keys(["C-o"])], KeyBar.steps(["C-o"]))
        XCTAssertEqual([.text("/compact", enter: true)], KeyBar.steps(["/compact", "Enter"]))
        XCTAssertEqual([.text("/btw", enter: false)], KeyBar.steps(["/btw"]))
        XCTAssertEqual(
            [.keys(["Escape", "Escape"]), .text(":wq", enter: true), .keys(["Enter"]), .text("ls", enter: false)],
            KeyBar.steps(["Escape", "Escape", ":wq", "Enter", "Enter", "ls"])
        )
        XCTAssertEqual([.keys(["C-e", "C-u"]), .text("fg", enter: true)], KeyBar.steps(["C-e", "C-u", "fg", "Enter"]))
    }

    func testSurvivesStorage() throws {
        var mine = bar
        mine.default.shortcuts = [BarKey(label: "new window", keys: ["prefix", "c"], bindings: true)]
        mine.default.hidden = ["redraw"]
        XCTAssertEqual(mine, try JSONDecoder().decode(KeyBar.self, from: JSONEncoder().encode(mine)))
        // A bar saved before there were shortcuts loads with none of the user's, and none hidden.
        let old = try JSONDecoder().decode(KeyBar.self, from: Data(#"{"profiles":[{"name":"c","programs":["claude"],"keys":[{"label":"^o","keys":["C-o"]}]}],"default":{"name":"d","keys":[]}}"#.utf8))
        XCTAssertEqual([], old.profiles[0].shortcuts)
        XCTAssertEqual([], old.profiles[0].hidden)
        XCTAssertFalse(old.profiles[0].keys[0].shortcuts)
        XCTAssertEqual(19, old.profile(for: "claude", program: "claude").shortcuts(for: Programs.of("claude")).count)
        // And one Android saved reads the same: its fields are named alike.
        let android = try JSONDecoder().decode(KeyProfile.self, from: Data(#"{"name":"c","keys":[{"label":"shortcuts","keys":[],"shortcuts":true}],"shortcuts":[{"label":"hi","keys":["hi","Enter"]}],"hidden":["mode"]}"#.utf8))
        XCTAssertEqual(KeyBar.shortcutsKey, android.keys[0])
        XCTAssertEqual(["mode"], android.hidden)
    }
}
