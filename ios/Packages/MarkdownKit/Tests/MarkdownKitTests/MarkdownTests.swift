import XCTest
import MarkdownKit

final class MarkdownTests: XCTestCase {
    func testATypicalReply() {
        let reply = #"""
            ## What changed

            The gateway now **reclaims** the window.
            Second line stays a line.

            - first
            - second, with `code`
              - nested
            1. one
            2. two

            ```kotlin
            val x = 1
              indented()
            ```

            > quoted
            > more

            | file | lines |
            |------|------:|
            | a.go | 12 |
            | b\|c | 3 | extra |

            ---
            """#
        XCTAssertEqual(
            Markdown.parse(reply),
            [
                .heading(level: 2, text: "What changed"),
                .paragraph("The gateway now **reclaims** the window.\nSecond line stays a line."),
                .list(
                    ordered: false, start: 1,
                    items: [
                        [.paragraph("first")],
                        [.paragraph("second, with `code`"), .list(ordered: false, start: 1, items: [[.paragraph("nested")]])],
                    ]
                ),
                .list(ordered: true, start: 1, items: [[.paragraph("one")], [.paragraph("two")]]),
                .code(language: "kotlin", text: "val x = 1\n  indented()"),
                .quote([.paragraph("quoted\nmore")]),
                .table(header: ["file", "lines"], rows: [["a.go", "12"], ["b|c", "3"]]),
                .rule,
            ]
        )
    }

    func testListItemsWithParagraphsAndOrderedStarts() {
        let md = "3. first\n\n   more of it\n4. second\n\nafter"
        XCTAssertEqual(
            Markdown.parse(md),
            [
                .list(ordered: true, start: 3, items: [[.paragraph("first"), .paragraph("more of it")], [.paragraph("second")]]),
                .paragraph("after"),
            ]
        )
    }

    func testAnUnclosedFenceRunsToTheEnd() {
        XCTAssertEqual(Markdown.parse("text\n```\nstill code"), [.paragraph("text"), .code(language: "", text: "still code")])
    }

    func testInlineStyles() {
        XCTAssertEqual(
            Markdown.inline("a **bold** *it* ***both*** ~~gone~~ `x*y` [site](https://a.b) see https://c.d/e."),
            [
                Span("a "),
                Span("bold", bold: true),
                Span(" "),
                Span("it", italic: true),
                Span(" "),
                Span("both", bold: true, italic: true),
                Span(" "),
                Span("gone", strike: true),
                Span(" "),
                Span("x*y", code: true),
                Span(" "),
                Span("site", link: "https://a.b"),
                Span(" see "),
                Span("https://c.d/e", link: "https://c.d/e"),
                Span("."),
            ]
        )
    }

    func testLiteralAsterisksAndUnderscoresStay() {
        XCTAssertEqual(Markdown.inline("snake_case_name and 2 * 3 * 4 *"), [Span("snake_case_name and 2 * 3 * 4 *")])
        XCTAssertEqual(Markdown.inline(#"\*not italic\*"#), [Span("*not italic*")])
        XCTAssertEqual(Markdown.inline("a __b__ `c"), [Span("a "), Span("b", bold: true), Span(" `c")])
    }

    func testDelimitersAreCodeUnitsNotCharacters() {
        XCTAssertEqual(Markdown.inline("👋 **hi**\u{301} é"), [Span("👋 "), Span("hi", bold: true), Span("\u{301} é")])
        XCTAssertEqual(Markdown.parse("```\u{301}\ncode\n```"), [.code(language: "\u{301}", text: "code")])
    }
}
