import Foundation
import XCTest
import ScreenReading

final class KeyBarTests: XCTestCase {
    private let bar = KeyBar.defaults

    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder().decode(type, from: Data(json.utf8))
    }

    func testTheProgramPicksTheProfile() {
        XCTAssertEqual("claude code", bar.profile(for: "claude", program: "claude").name)
        XCTAssertEqual("shell", bar.profile(for: "ZSH").name)
        XCTAssertEqual("shell", bar.profile(for: "bash", program: "shell").name)
        XCTAssertEqual("default", bar.profile(for: "hntui").name)
        XCTAssertEqual("default", bar.profile(for: "").name)
    }

    func testABarSavedBeforeAProgramShippedGivesItItsOwn() {
        var saved = bar
        saved.profiles.removeAll { $0.programs.contains("codex") }
        let codex = saved.profile(for: "codex", program: "codex")
        XCTAssertEqual("codex", codex.name)
        XCTAssertEqual(["Escape"], codex.stop) // ^C would quit it
        XCTAssertEqual(["shortcuts", "esc", "⇧←", "⇧tab", "arrows", "tab", "1", "2", "3"], codex.keys.map(\.label))
        // Made a profile of its own, it's that bar, not the default's with ^C.
        let made = saved.madeFor(command: "codex", program: "codex")
        XCTAssertEqual(["Escape"], made.stop)
        XCTAssertEqual(["codex"], made.programs)
        XCTAssertEqual(codex.keys, made.keys)
        XCTAssertEqual(["hntui"], saved.madeFor(command: "hntui", program: nil).programs)
    }

    func testTheProgramsShippedBringTheirBars() {
        let claude = bar.profile(for: "claude", program: "claude")
        XCTAssertEqual(["shortcuts", "esc", "⇧tab", "↑", "↓", "⏎", "1", "2", "3", "^o", "^c"], claude.keys.map(\.label))
        XCTAssertTrue(claude.keys[0].shortcuts) // by name, the key that offers them
        XCTAssertEqual(["claude"], claude.programs)
        XCTAssertEqual(["zsh", "shell"], bar.profile(for: "zsh").programs)
        // A profile saved by command still matches the pane by its command.
        var saved = bar
        saved.profiles = [KeyProfile(name: "mine", programs: ["bash"], keys: [])]
        XCTAssertEqual("mine", saved.profile(for: "bash", program: "shell").name)
    }

    func testTheArrowsGoInOneKey() throws {
        let shell = bar.profile(for: "zsh").keys
        XCTAssertEqual(["shortcuts", "ctrl", "tab", "arrows", "^r", "^c", "^d", "esc"], shell.map(\.label))
        XCTAssertTrue(shell[3].cluster)
        XCTAssertEqual(["Up", "Left", "Enter", "Right", "Down", "BSpace"], shell[3].keys)
        XCTAssertEqual("arrows", bar.default.keys.last?.label)
        // A bar saved before there were clusters loads as it was.
        let saved = try decode(BarKey.self, #"{"label":"↑","keys":["Up"]}"#)
        XCTAssertFalse(saved.cluster)
    }

    func testTheFirstMatchWins() {
        var both = bar
        both.profiles = [KeyProfile(name: "a", programs: ["zsh"], keys: []), KeyProfile(name: "b", programs: ["zsh"], keys: [])]
        XCTAssertEqual("a", both.profile(for: "zsh").name)
    }

    func testKeysShowAsTheyWouldOnTheBar() {
        XCTAssertEqual("^j", KeyBar.label(of: ["C-j"]))
        XCTAssertEqual("alt+⏎", KeyBar.label(of: ["M-Enter"])) // alt, as keyboards mark it differently
        XCTAssertEqual("^alt+←", KeyBar.label(of: ["C-M-Left"]))
        XCTAssertEqual("esc :w ⏎", KeyBar.label(of: ["Escape", ":w", "Enter"]))
        XCTAssertEqual("ctrl", KeyBar.label(of: ["C-"]))
        XCTAssertEqual("q", KeyBar.label(of: ["q"]))
    }

    func testAPluginsProgramGetsItsOwnBarTillTheUserMakesOne() {
        Programs.supply(["aider": Supplied(keyBar: KeyDefaults(name: "aider", keys: ["Escape", "C-c"], stop: ["C-d"]), commands: [], newlineKeys: nil, clearKeys: [])])
        defer { Programs.supply([:]) }
        let p = bar.profile(for: "python3", program: "aider")
        XCTAssertEqual(["shortcuts", "esc", "^c"], p.keys.map(\.label)) // the key put first, as it left it out
        XCTAssertEqual(["C-d"], p.stop)
        XCTAssertEqual(bar.default, bar.profile(for: "python3", program: "other"))
    }

    func testAHeldKeySaysWhatItSends() {
        XCTAssertEqual("ctrl c", KeyBar.name(of: BarKey(label: "^c", keys: ["C-c"])))
        XCTAssertEqual("ctrl alt left", KeyBar.name(of: BarKey(label: "^alt+←", keys: ["C-M-Left"])))
        XCTAssertEqual("escape :w enter", KeyBar.name(of: BarKey(label: "save", keys: ["Escape", ":w", "Enter"])))
        XCTAssertEqual("shift tab", KeyBar.name(of: KeyBar.common.first { $0.label == "⇧tab" }!))
        XCTAssertEqual("ctrl + key", KeyBar.name(of: KeyBar.common.first { $0.label == "ctrl" }!))
        XCTAssertEqual("shift + key", KeyBar.name(of: KeyBar.common.first { $0.label == "shift" }!))
    }

    func testALabelTheUserWroteIsTheirs() {
        XCTAssertTrue(KeyBar.common.allSatisfy(\.hasOwnLabel))
        XCTAssertTrue(BarKey(label: "^alt+←", keys: ["C-M-Left"]).hasOwnLabel)
        XCTAssertFalse(BarKey(label: "save", keys: ["Escape", ":w", "Enter"]).hasOwnLabel)
        XCTAssertFalse(BarKey(label: "pfx", keys: ["C-f"]).hasOwnLabel)
    }

    func testClaudeAndShellsStopWithCtrlC() {
        XCTAssertEqual(["C-c"], bar.profile(for: "claude").stop)
        XCTAssertEqual(["C-c"], bar.profile(for: "zsh").stop)
    }

    func testStickyKeysGoWithTheNextKey() {
        let ctrl = KeyBar.common.first { $0.label == "ctrl" }!
        let shift = KeyBar.common.first { $0.label == "shift" }!
        XCTAssertEqual(["C-j"], ctrl.then(BarKey.typed("j")).keys)
        XCTAssertEqual(["C-Up"], ctrl.then(KeyBar.common.first { $0.label == "↑" }!).keys)
        XCTAssertEqual(["S-Up"], shift.then(KeyBar.common.first { $0.label == "↑" }!).keys)
        XCTAssertEqual(["BTab"], shift.then(KeyBar.common.first { $0.label == "tab" }!).keys) // shift tab is back tab
        XCTAssertEqual(["J"], shift.then(BarKey.typed("j")).keys) // a capital
        XCTAssertEqual(["prefix", "j"], BarKey(label: "pfx", keys: ["prefix"], sticky: true, bindings: true).then(BarKey.typed("j")).keys) // a saved prefix key still goes first
        XCTAssertEqual(["Space"], BarKey.typed(" ").keys)
    }

    func testInTheTerminalReturnIsANewLineWhereTheProgramTakesSeveral() throws {
        let claude = bar.profile(for: "claude")
        XCTAssertEqual(["C-j"], claude.returnKeys(Programs.of("claude")))
        XCTAssertEqual(["Enter"], claude.sendKeys())
        XCTAssertEqual(["Enter"], bar.profile(for: "zsh").returnKeys(Programs.of("zsh")))
        // What the user picked wins, and a bar saved before there was a choice leaves it to the program.
        var picked = claude
        picked.returnKey = ["Enter"]
        XCTAssertEqual(["Enter"], picked.returnKeys(Programs.of("claude")))
        let saved = try decode(KeyBar.self, #"{"profiles":[{"name":"c","programs":["claude"],"keys":[]}],"default":{"name":"d","keys":[]}}"#)
        XCTAssertEqual(["C-j"], saved.profile(for: "claude").returnKeys(Programs.of("claude")))
    }

    func testSurvivesStorage() throws {
        XCTAssertEqual(bar, try JSONDecoder().decode(KeyBar.self, from: JSONEncoder().encode(bar)))
        // Settings saved before keys could be sticky, or with the old zoomed-out profile, still load.
        let old = #"{"profiles":[],"default":{"name":"d","keys":[{"label":"pfx","keys":["C-f"]}]},"zoomedOut":null}"#
        XCTAssertEqual(BarKey(label: "pfx", keys: ["C-f"]), try decode(KeyBar.self, old).default.keys[0])
    }

    func testABarSavedWhenCtrlOfferedTheShortcutsGetsTheShortcutsKeyOnce() throws {
        let old = try decode(KeyBar.self, #"{"profiles":[{"name":"c","programs":["claude"],"keys":[{"label":"esc","keys":["Escape"]},{"label":"ctrl","keys":["C-"],"sticky":true}]},"# +
            #"{"name":"s","programs":["zsh"],"keys":[{"label":"tab","keys":["Tab"]},{"label":"shortcuts","keys":[],"shortcuts":true}]}],"default":{"name":"d","keys":[]}}"#)
        XCTAssertEqual(0, old.version)
        let now = old.upgraded()
        XCTAssertEqual(["shortcuts", "esc", "ctrl"], now.profiles[0].keys.map(\.label))
        XCTAssertEqual(["tab", "shortcuts"], now.profiles[1].keys.map(\.label)) // where the user put it
        XCTAssertEqual(["shortcuts"], now.default.keys.map(\.label))
        // Taken off again, it stays off.
        var off = now
        off.default.keys = []
        XCTAssertEqual(off, try decode(KeyBar.self, String(decoding: JSONEncoder().encode(off), as: UTF8.self)).upgraded())
        XCTAssertEqual(KeyBar.defaults, KeyBar.defaults.upgraded())
    }
}
