import XCTest
import MarkdownKit

final class DiffTests: XCTestCase {
    func testHunksSayWhereTheyAreInTheFileAsItIsNow() {
        let diff = "@@ -3,3 +5,4 @@\n a\n-old\n+new\n+more\n\\ No newline at end of file\n@@ -0,0 +1,1 @@\n+first\n… 12 more lines"
        XCTAssertEqual(
            diffLines(diff),
            [
                DiffLine(kind: .hunk, text: "line 5"),
                DiffLine(kind: .context, text: "a"),
                DiffLine(kind: .removed, text: "old"),
                DiffLine(kind: .added, text: "new"),
                DiffLine(kind: .added, text: "more"),
                DiffLine(kind: .note, text: "\\ No newline at end of file"),
                DiffLine(kind: .hunk, text: "line 1"),
                DiffLine(kind: .added, text: "first"),
                DiffLine(kind: .note, text: "… 12 more lines"),
            ]
        )
        let counts = diffCounts(diffLines(diff))
        XCTAssertEqual(counts.added, 3)
        XCTAssertEqual(counts.removed, 1)
    }

    func testAnyLineEndingEndsALineAndANewFileStartsAtLineOne() {
        XCTAssertEqual(
            diffLines("@@ -1,2 +0,0 @@\r\n-gone\r-too\n"),
            [
                DiffLine(kind: .hunk, text: "line 1"),
                DiffLine(kind: .removed, text: "gone"),
                DiffLine(kind: .removed, text: "too"),
                DiffLine(kind: .note, text: ""),
            ]
        )
    }

    func testAMalformedHeaderIsANote() {
        XCTAssertEqual(diffLines("@@ -a +b @@"), [DiffLine(kind: .note, text: "@@ -a +b @@")])
        XCTAssertEqual(diffLines("@@ -٣ +٥ @@"), [DiffLine(kind: .note, text: "@@ -٣ +٥ @@")])
    }
}
