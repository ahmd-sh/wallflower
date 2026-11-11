import XCTest
@testable import ReadModel

final class ItemsTests: XCTestCase {
    private func user(_ id: String) -> Entry { .said(Entry.Said(id: id, by: .user, text: id)) }
    private func reply(_ id: String) -> Entry { .said(Entry.Said(id: id, by: .program, text: id)) }
    private func step(_ id: String, output: String = "") -> Entry { .step(Entry.Step(id: id, name: "Bash", input: "ls \(id)", output: output, status: .done)) }
    private func thought(_ id: String, _ text: String) -> Entry { .thought(Entry.Thought(id: id, text: text)) }

    /// Items as short strings: said ids (* for a turn's last reply), steps in brackets.
    private func shape(_ entries: [Entry]) -> String {
        items(entries).map { i in
            switch i {
            case let .said(entry, last): entry.id + (last ? "*" : "")
            case let .steps(entries): "[" + entries.map(\.id).joined(separator: ",") + "]"
            }
        }.joined(separator: " ")
    }

    func testStepsBetweenWhatsSaidFoldIntoOne() {
        XCTAssertEqual("u1 [t1,th,t2] r1*", shape([user("u1"), step("t1"), thought("th", "hmm"), step("t2"), reply("r1")]))
    }

    func testEachTurnsLastReplyIsMarked() {
        XCTAssertEqual("u1 r1 [t] r2* u2 r3*", shape([user("u1"), reply("r1"), step("t"), reply("r2"), user("u2"), reply("r3")]))
    }

    func testANoteOrPictureAfterTheReplyLeavesItTheLastWord() {
        let picture = Entry.picture(Entry.Picture(id: "p", by: .program, mime: "image/png", size: 2))
        XCTAssertEqual("u1 r1* n p", shape([user("u1"), reply("r1"), .note(Entry.Note(id: "n", text: "interrupted")), picture]))
    }

    func testATurnStillGoingEndsWithItsSteps() {
        XCTAssertEqual("u1 r1* u2 [t]", shape([user("u1"), reply("r1"), user("u2"), step("t")]))
    }

    func testATraceSaysWhatRanAndWhatCameOfIt() {
        let edit = Entry.step(Entry.Step(id: "e", name: "Edit", input: "/x.go", output: "updated", diff: "@@ -1 +1 @@\n-a\n+b", status: .done))
        XCTAssertEqual(
            "Bash: ls t\nfine\n\nthinking: hmm\n\nEdit: /x.go\n@@ -1 +1 @@\n-a\n+b",
            trace([step("t", output: "fine"), thought("th", "hmm"), edit])
        )
    }
}
