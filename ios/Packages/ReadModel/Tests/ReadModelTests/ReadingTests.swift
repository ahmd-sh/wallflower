import Wire
import XCTest
@testable import ReadModel

final class ReadingTests: XCTestCase {
    private func text(_ id: String) -> WireEntry { WireEntry(id: id, from: "program", kind: "said", text: id) }
    private func user(_ id: String, _ text: String) -> WireEntry { WireEntry(id: id, from: "user", kind: "said", text: text) }

    func testTheLatestMessageBackInTheProgramsLineJustAfterAStopIsTakenBack() {
        let r = Reading().apply(Entries(pane: "%1", reset: true, entries: [user("u1", "fix it"), text("a"), user("u2", "now write\nthe tests")]))
        var stopped = r
        stopped.stopped = 1_000
        XCTAssertEqual(["u1", "a"], stopped.takenBack("now write the tests", now: 2_000).shown.map(\.id))
        XCTAssertEqual(r, r.takenBack("now write the tests", now: 2_000)) // no stop: history brought back with ↑
        XCTAssertEqual(stopped, stopped.takenBack("now write the tests", now: 9_000)) // a stop long ago
        XCTAssertEqual(stopped, stopped.takenBack("fix it", now: 2_000)) // only the latest
        XCTAssertEqual(stopped, stopped.takenBack("", now: 2_000))
        // Sent again as it was, not taken back after all: the next message recorded ends it.
        let again = stopped.takenBack("now write the tests", now: 2_000).apply(Entries(pane: "%1", entries: [user("u3", "now write the tests")]))
        XCTAssertEqual(["u1", "a", "u2", "u3"], again.shown.map(\.id))
    }

    func testAUsersStepIsACommandANotesOutputItsDetailAndTheTitleStays() {
        let r = Reading()
            .apply(
                Entries(
                    pane: "%1", reset: true,
                    entries: [
                        WireEntry(id: "c", from: "user", kind: "step", text: "~/x", name: "/add-dir", status: "done", output: "Added ~/x"),
                        WireEntry(id: "n", from: "user", kind: "note", text: "Conversation compacted", output: "the summary"),
                    ],
                    title: "fix the tests"
                )
            )
            .apply(Entries(pane: "%1", context: 5))
        XCTAssertEqual(
            [.command(Entry.Command(id: "c", name: "/add-dir", args: "~/x", output: "Added ~/x")), .note(Entry.Note(id: "n", text: "Conversation compacted", detail: "the summary"))],
            r.entries
        )
        XCTAssertEqual("fix the tests", r.title)
        XCTAssertEqual("tests", r.apply(Entries(pane: "%1", title: "tests")).title)
        XCTAssertEqual(nil, r.apply(Entries(pane: "%1", reset: true)).title)
    }

    func testEntriesReplaceTheirsByIdOrComeLast() {
        let r = Reading()
            .apply(Entries(pane: "%1", reset: true, more: true, entries: [text("a"), WireEntry(id: "t", from: "program", kind: "step", text: "ls", name: "Bash", status: "running")]))
            .apply(Entries(pane: "%1", entries: [WireEntry(id: "t", from: "program", kind: "step", text: "ls", name: "Bash", status: "done", output: "x", outputCut: 3), text("b")]))
        XCTAssertEqual(["a", "t", "b"], r.entries.map(\.id))
        XCTAssertEqual(.step(Entry.Step(id: "t", name: "Bash", input: "ls", output: "x", status: .done, outputCut: 3)), r.entries[1])
        XCTAssertEqual(true, r.more)
        XCTAssertEqual(true, r.loaded)
    }

    func testTheWiresKindsBecomeTheReadModels() {
        let r = Reading().apply(
            Entries(
                pane: "%1", reset: true,
                entries: [
                    user("u", "hi"),
                    WireEntry(id: "th", from: "program", kind: "thought", text: "hmm"),
                    WireEntry(id: "f", from: "program", kind: "step", text: "rm x", name: "Bash", status: "failed", diff: "@@", diffCut: 2),
                    WireEntry(id: "i", from: "user", kind: "picture", mime: "image/png", size: 2),
                    WireEntry(id: "n", from: "user", kind: "note", text: "compacted"),
                    WireEntry(id: "z", from: "program", kind: "something newer", text: "shown as a note"),
                ]
            )
        )
        XCTAssertEqual(
            [
                .said(Entry.Said(id: "u", by: .user, text: "hi")),
                .thought(Entry.Thought(id: "th", text: "hmm")),
                .step(Entry.Step(id: "f", name: "Bash", input: "rm x", diff: "@@", status: .failed, diffCut: 2)),
                .picture(Entry.Picture(id: "i", by: .user, mime: "image/png", size: 2)),
                .note(Entry.Note(id: "n", text: "compacted")),
                .note(Entry.Note(id: "z", text: "shown as a note")),
            ],
            r.entries
        )
    }

    func testRewoundItHidesTheMessageQuotedAndWhatFollowed() {
        let said = user("u2", "Use the Write tool to create /private/tmp/claude-502/rw.txt containing hi")
        var r = Reading().apply(Entries(pane: "%1", reset: true, entries: [user("u1", "one"), text("a1"), said, text("a2")]))
        let all = r
        r = r.rewoundTo(quoting("Use the Write tool to create /private/tmp/claude -502/rw.txt containing hi"))
        XCTAssertEqual(["u1", "a1"], r.shown.map(\.id))
        XCTAssertEqual(["u1", "a1"], r.apply(Entries(pane: "%1", context: 5)).shown.map(\.id))
        XCTAssertEqual(["u1", "a1"], r.rewoundTo(quoting("…create /private/tmp/claude…")).shown.map(\.id))
        XCTAssertEqual(["u1", "a1"], r.apply(Entries(pane: "%1", reset: true, entries: [user("u1", "one"), text("a1"), said, text("a2")])).shown.map(\.id))
        XCTAssertEqual(4, all.rewoundTo(quoting("something else")).shown.count)
    }

    func testARepeatedMessageRewoundHidesFromItsLatestSoNothingStillSaidIsHidden() {
        // The quote can't tell two "yes"es apart: hiding from the later one may leave some of what was
        // taken back showing until the transcript says (the next message), but never hides what's still there.
        let entries = [user("u1", "yes"), text("a1"), user("u2", "yes"), text("a2")]
        let r = Reading().apply(Entries(pane: "%1", reset: true, entries: entries)).rewoundTo(quoting("yes"))
        XCTAssertEqual(["u1", "a1"], r.shown.map(\.id))
        // The one held to edit, when it's known, whichever of them it is.
        XCTAssertEqual([], Reading().apply(Entries(pane: "%1", reset: true, entries: entries)).rewoundTo(quoting("yes"), held: "u1").shown.map(\.id))
    }

    func testAResetStartsOver() {
        let r = Reading()
            .apply(Entries(pane: "%1", reset: true, entries: [text("a")]))
            .apply(Entries(pane: "%1", reset: true, error: "the conversation ended"))
        XCTAssertEqual([], r.entries)
        XCTAssertEqual("the conversation ended", r.problem)
    }

    func testTheOldestGoPastTheLimit() {
        let r = Reading()
            .apply(Entries(pane: "%1", reset: true, entries: (0..<Reading.MAX_ENTRIES).map { text("\($0)") }))
            .apply(Entries(pane: "%1", entries: [text("new")]))
        XCTAssertEqual(Reading.MAX_ENTRIES, r.entries.count)
        XCTAssertEqual("1", r.entries.first?.id)
        XCTAssertEqual("new", r.entries.last?.id)
        XCTAssertEqual(true, r.more)
    }

    func testWhatTheProgramIsDoingIsItsPanes() {
        XCTAssertEqual(.working, activityOf(Pane(id: "%1", index: 0, active: true, cols: 56, rows: 20, command: "claude", activity: Pane.WORKING)))
        XCTAssertEqual(nil, activityOf(Pane(id: "%1", index: 0, active: true, cols: 56, rows: 20, command: "zsh")))
    }

    func testTheContextKeepsUntilItChangesAndGoesWithANewConversation() {
        let r = Reading()
            .apply(Entries(pane: "%1", reset: true, entries: [text("a")], context: 1000))
            .apply(Entries(pane: "%1", entries: [text("b")]))
        XCTAssertEqual(1000, r.context)
        let more = r.apply(Entries(pane: "%1", context: 2500))
        XCTAssertEqual(2500, more.context)
        XCTAssertEqual(["a", "b"], more.entries.map(\.id))
        XCTAssertEqual(0, more.apply(Entries(pane: "%1", context: 0)).context)
        XCTAssertEqual(0, more.apply(Entries(pane: "%1", reset: true)).context)
    }

    func testAnotherAgentsMessageIsTheirsByName() {
        let r = Reading().apply(
            Entries(pane: "%1", reset: true, entries: [WireEntry(id: "t1", from: "program", kind: "note", text: "Server done", name: "satchel-server", output: "Tests pass.")])
        )
        XCTAssertEqual([.agent(Entry.Agent(id: "t1", name: "satchel-server", about: "Server done", message: "Tests pass."))], r.entries)
        XCTAssertEqual(
            [.note(Entry.Note(id: "n1", text: "Compacted"))],
            Reading().apply(Entries(pane: "%1", reset: true, entries: [WireEntry(id: "n1", from: "program", kind: "note", text: "Compacted")])).entries
        )
    }

    func testWhatsQueuedStaysTillItChanges() {
        let r = Reading().apply(Entries(pane: "%1", reset: true, entries: [text("a")], queued: ["and then"]))
        XCTAssertEqual(["and then"], r.queued)
        XCTAssertEqual(["and then"], r.apply(Entries(pane: "%1", entries: [text("b")])).queued)
        XCTAssertEqual([], r.apply(Entries(pane: "%1", queued: [])).queued)
        XCTAssertEqual([], r.apply(Entries(pane: "%1", reset: true)).queued)
    }

    func testRewoundStaysThroughAResetThatStillHasItsMessage() {
        let said = [text("a"), user("u1", "fix it"), text("b")]
        let r = Reading().apply(Entries(pane: "%1", reset: true, entries: said)).rewoundTo { $0 == "fix it" }
        XCTAssertEqual(["a"], r.apply(Entries(pane: "%1", reset: true, entries: said)).shown.map(\.id)) // reconnected
        let next = [text("a"), user("u2", "fix it properly")]
        XCTAssertEqual(["a", "u2"], r.apply(Entries(pane: "%1", reset: true, entries: next)).shown.map(\.id)) // the next message took it back
        // Hidden wrongly (it wasn't taken back after all), a message of the user's after it ends that.
        let after = said + [user("u3", "and again")]
        XCTAssertEqual(["a", "u1", "b", "u3"], r.apply(Entries(pane: "%1", reset: true, entries: after)).shown.map(\.id))
    }
}

/// Whether a message is the one Claude's /rewind quotes as `quote`, as restoring it says.
private func quoting(_ quote: String) -> (String) -> Bool {
    let quote = bare(quote)
    return { quotes(quote, bare($0)) }
}

private func bare(_ text: String) -> String {
    text.filter { !$0.isWhitespace }
}

/// Whether `message` is what Claude quotes as `quote`: its words from the start, to the end unless cut short with ….
private func quotes(_ quote: String, _ message: String) -> Bool {
    let parts = quote.components(separatedBy: "…")
    guard message.hasPrefix(parts[0]) else { return false }
    var at = message.index(message.startIndex, offsetBy: parts[0].count)
    for part in parts.dropFirst() where !part.isEmpty {
        guard let found = message.range(of: part, range: at..<message.endIndex) else { return false }
        at = found.upperBound
    }
    return quote.hasSuffix("…") || message.hasSuffix(parts[parts.count - 1]) && (parts.count > 1 || message.count == quote.count)
}
