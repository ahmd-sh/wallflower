import XCTest
import ScreenReading

/// Codex CLI 0.167's screens, at 64 columns.
final class CodexTests: XCTestCase {
    private let codex = Codex.shared

    private func screen(_ name: String) -> Screen { captured("codex-\(name).ansi", 64) }

    func testAnEmptyComposerHoldsOnlyItsPlaceholder() {
        XCTAssertEqual("", codex.line(screen("idle"))?.text)
        XCTAssertNil(codex.prompt(screen("idle")))
        XCTAssertNil(codex.status(screen("idle")))
        XCTAssertNil(codex.asked(screen("idle")))
    }

    func testALongLineIsWrappedAtWordsAndALongWordWhereTheRowEnds() {
        let line = codex.line(screen("typed"))
        XCTAssertEqual(
            "the quick brown fox jumps over the lazy dog and keeps running far beyond the edge of this narrow pane "
                + "averyveryverylongwordthatcannotfitonarowatallnomatterhowhardittries end\nand a second line",
            line?.text
        )
        XCTAssertEqual(5, line?.rows)
    }

    func testTheCommandsPopupIsAboveTheComposerNotInIt() {
        XCTAssertEqual("/", codex.line(screen("slash"))?.text)
        XCTAssertNil(codex.prompt(screen("slash")))
    }

    func testWorkingItSaysSoAboveTheComposer() {
        XCTAssertEqual("Working", codex.status(screen("working")))
        XCTAssertEqual("", codex.line(screen("working"))?.text)
        XCTAssertNil(codex.asked(screen("working")))
    }

    func testItsQuestionsWaitBelowItsWorkingToBeBroughtUp() {
        let s = screen("working-asks")
        XCTAssertEqual("Working", codex.status(s))
        XCTAssertEqual(Asked(what: "1 question", keys: ["S-Left"]), codex.asked(s))
        XCTAssertNil(codex.prompt(s))
        XCTAssertEqual("a message typed while it works", codex.line(screen("working-typed"))?.text)
    }

    func testBroughtUpAQuestionTakesTheComposersPlace() throws {
        let s = screen("question")
        XCTAssertNil(codex.line(s))
        XCTAssertNil(codex.asked(s))
        let p = try XCTUnwrap(codex.prompt(s))
        XCTAssertEqual(["Red", "Blue", "Green", "Other"], p.options.map(\.label))
        XCTAssertEqual(["Which colour do you pick?"], p.lines.map(\.text).filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }) // not the conversation's rows above
        XCTAssertEqual(["2"], p.keys(p.options[1])) // a number answers it at once
        XCTAssertEqual("Working", codex.status(s))
    }

    func testAnApprovalIsAMenu() throws {
        let s = screen("approval")
        XCTAssertNil(codex.line(s))
        let p = try XCTUnwrap(codex.prompt(s))
        XCTAssertEqual(3, p.options.count)
        XCTAssertEqual("Yes, proceed (y)", p.options[0].label)
        XCTAssertEqual("No, and tell Codex what to do differently (esc)", p.options[2].label)
        XCTAssertTrue(p.options[0].current)
        XCTAssertEqual("Would you like to run the following command?", p.lines.first?.text.trimmingCharacters(in: .whitespaces))
    }

    func testAFolderToTrustIsPickedByTheCursorAsANumberOnlyMovesToTrust() throws {
        let p = try XCTUnwrap(codex.prompt(screen("trust")))
        XCTAssertEqual(["Trust and continue", "Back to Agent Command Center"], p.options.map(\.label))
        XCTAssertEqual(["Enter"], p.keys(p.options[0]))
        XCTAssertEqual(["Down", "Enter"], p.keys(p.options[1]))
        let approval = try XCTUnwrap(codex.prompt(screen("approval")))
        XCTAssertEqual(["1"], approval.keys(approval.options[0])) // elsewhere a number answers
    }

    func testItsTitleIsTheConversationsNameThenTheProjects() {
        XCTAssertEqual("Approve touch command", codex.label("Approve touch command | wallflower"))
        XCTAssertEqual("Approve touch command", codex.label("⠴ Approve touch command | wallflower"))
        XCTAssertEqual("Approve touch command", codex.label("[ ! ] Action Required | Approve touch command | wallflower"))
        XCTAssertNil(codex.label("wallflower"))
        XCTAssertNil(codex.label("[ . ] Action Required | wallflower"))
    }

    func testItsModeIsPlanWhenItsFooterSaysSo() {
        XCTAssertEqual(Mode(name: "plan", mark: "", hue: .aqua), codex.mode(captured("codex-plan.ansi", 80)))
        XCTAssertEqual(Mode(name: "default", mark: ""), codex.mode(screen("idle")))
        XCTAssertNil(codex.mode(screen("question")))
    }

    func testItHasItsOwnBarAndStop() {
        XCTAssertTrue(Programs.of("codex") === codex)
        XCTAssertEqual(["Escape"], codex.keyBar?.stop)
    }

    /// The agent command center, at 42 columns.
    private func agents(_ name: String = "") -> Aside.Tasks? {
        if case .tasks(let t) = codex.aside(captured("codex-agents\(name).ansi", 42)) { return t }
        return nil
    }

    func testItsAgentsAreItsOwnListOfTasksInGroups() {
        let s = captured("codex-agents.ansi", 42)
        XCTAssertNil(codex.line(s))
        XCTAssertNil(codex.prompt(s))
        let t = agents()!
        // Those that don't fit are there too, how many they hold unknown.
        XCTAssertEqual(["All", "Needs you", "Working", "Ready", "Inactive"], t.filters.map(\.name))
        XCTAssertEqual([25, 1, 0, nil, nil], t.filters.map(\.count))
        XCTAssertEqual(["All"], t.filters.filter(\.current).map(\.name))
        XCTAssertEqual(["Pinned", "/Users/sam/dev/wallflower"], t.groups.map(\.name))
        XCTAssertEqual(["1", "24"], t.groups.map(\.count))
        XCTAssertEqual(["second task"], t.groups[0].tasks.map(\.name))
        let tasks = t.tasks
        XCTAssertEqual("Untitled task", tasks[1].name)
        XCTAssertEqual("Error: task number 22 with a fai…", tasks[2].name)
        XCTAssertEqual(["task number 21 with a fairly lon…"], tasks.filter(\.current).map(\.name))
        XCTAssertEqual(.idle, tasks[1].mark)
        XCTAssertEqual(.lit, tasks[2].mark) // ●, with something needing the user: it, or one out of sight
        XCTAssertFalse(t.above)
        XCTAssertTrue(t.below)
        XCTAssertNil(t.says)
        XCTAssertEqual(["Escape"], t.leave)
    }

    func testATaskIsOpenedByMovingTheCursorToIt() {
        let t = agents()!
        XCTAssertEqual(["Up", "Up", "Up", "Enter"], t.opening(0)) // past the group's heading and the row between
        XCTAssertEqual(["Down", "Down", "Enter"], t.opening(5))
        XCTAssertEqual(["Enter"], t.opening(t.tasks.firstIndex { $0.current }!))
        XCTAssertEqual(["Tab", "Tab"], t.showing(2))
        XCTAssertEqual(["Tab", "Tab", "Tab", "Tab"], t.showing(4)) // out of sight
        XCTAssertEqual([], t.showing(0))
    }

    func testItsMarksSayWhatEachTaskIsDoingAsFarAsTheyCan() {
        let marks = Dictionary(uniqueKeysWithValues: agents("-marks")!.tasks.map { ($0.name, $0.mark) })
        XCTAssertEqual(.needsYou, marks["build fix"])
        XCTAssertEqual(.lit, marks["docs pass"])
        let working = Dictionary(uniqueKeysWithValues: agents("-working")!.tasks.map { ($0.name, $0.mark) })
        XCTAssertEqual(.lit, working["fix the build"]) // working, or needing the user: both are
        let needs = agents("-needs-you")!
        XCTAssertEqual([.needsYou], needs.tasks.map(\.mark)) // what the filter holds
        XCTAssertEqual("/Users/…/dev/wallflower", needs.groups.first?.name)
        XCTAssertEqual("1 of 24", needs.groups.first?.count)
    }

    func testFilteredItCanSayThereAreNoneOrShowOtherFilters() {
        let none = agents("-none")!
        XCTAssertEqual("No matching tasks", none.says)
        XCTAssertTrue(none.groups.isEmpty)
        XCTAssertEqual(["Working"], none.filters.filter(\.current).map(\.name))
        let ready = agents("-filtered")!
        XCTAssertEqual(["All", "Needs you", "Working", "Ready", "Inactive"], ready.filters.map(\.name))
        XCTAssertEqual([nil, nil, 0, 1, 23], ready.filters.map(\.count))
        XCTAssertEqual(["Ready"], ready.filters.filter(\.current).map(\.name))
        XCTAssertEqual(["BTab"], ready.showing(2))
        XCTAssertEqual(["BTab", "BTab", "BTab"], ready.showing(0)) // out of sight
        XCTAssertEqual(["Tab"], ready.showing(4))
    }

    func testScrolledThereIsMoreAboveAndItsGroupsHeadingIsOutOfSight() {
        let t = agents("-scrolled")!
        XCTAssertTrue(t.above)
        XCTAssertFalse(t.below)
        XCTAssertEqual([""], t.groups.map(\.name))
        XCTAssertEqual("task number 19 with a fairly lon…", t.tasks.first?.name)
        XCTAssertEqual("say hi", t.tasks.last?.name)
        XCTAssertEqual(["say hi"], t.tasks.filter(\.current).map(\.name))
    }

    func testItsKeysAreAViewOfItsOwn() {
        XCTAssertEqual(.view(Aside.View(name: "agents' keys", leave: ["Escape"])), codex.aside(captured("codex-agents-help.ansi", 42)))
        XCTAssertNil(codex.aside(screen("idle")))
        XCTAssertNil(codex.aside(screen("trust")))
    }
}

