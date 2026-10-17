package sh.ahmd.wallflower.markdown

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.ahmd.wallflower.markdown.Block.Code
import sh.ahmd.wallflower.markdown.Block.Heading
import sh.ahmd.wallflower.markdown.Block.ListBlock
import sh.ahmd.wallflower.markdown.Block.Paragraph
import sh.ahmd.wallflower.markdown.Block.Quote
import sh.ahmd.wallflower.markdown.Block.Rule
import sh.ahmd.wallflower.markdown.Block.Table

class MarkdownTest {
    @Test
    fun aTypicalReply() {
        val reply = """
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
        """.trimIndent()
        assertEquals(
            listOf(
                Heading(2, "What changed"),
                Paragraph("The gateway now **reclaims** the window.\nSecond line stays a line."),
                ListBlock(
                    false, 1,
                    listOf(
                        listOf(Paragraph("first")),
                        listOf(Paragraph("second, with `code`"), ListBlock(false, 1, listOf(listOf(Paragraph("nested"))))),
                    ),
                ),
                ListBlock(true, 1, listOf(listOf(Paragraph("one")), listOf(Paragraph("two")))),
                Code("kotlin", "val x = 1\n  indented()"),
                Quote(listOf(Paragraph("quoted\nmore"))),
                Table(listOf("file", "lines"), listOf(listOf("a.go", "12"), listOf("b|c", "3"))),
                Rule,
            ),
            Markdown.parse(reply),
        )
    }

    @Test
    fun listItemsWithParagraphsAndOrderedStarts() {
        val md = "3. first\n\n   more of it\n4. second\n\nafter"
        assertEquals(
            listOf(
                ListBlock(true, 3, listOf(listOf(Paragraph("first"), Paragraph("more of it")), listOf(Paragraph("second")))),
                Paragraph("after"),
            ),
            Markdown.parse(md),
        )
    }

    @Test
    fun anUnclosedFenceRunsToTheEnd() {
        assertEquals(listOf(Paragraph("text"), Code("", "still code")), Markdown.parse("text\n```\nstill code"))
    }

    @Test
    fun inlineStyles() {
        assertEquals(
            listOf(
                Span("a "),
                Span("bold", bold = true),
                Span(" "),
                Span("it", italic = true),
                Span(" "),
                Span("both", bold = true, italic = true),
                Span(" "),
                Span("gone", strike = true),
                Span(" "),
                Span("x*y", code = true),
                Span(" "),
                Span("site", link = "https://a.b"),
                Span(" see "),
                Span("https://c.d/e", link = "https://c.d/e"),
                Span("."),
            ),
            Markdown.inline("a **bold** *it* ***both*** ~~gone~~ `x*y` [site](https://a.b) see https://c.d/e."),
        )
    }

    @Test
    fun literalAsterisksAndUnderscoresStay() {
        assertEquals(listOf(Span("snake_case_name and 2 * 3 * 4 *")), Markdown.inline("snake_case_name and 2 * 3 * 4 *"))
        assertEquals(listOf(Span("*not italic*")), Markdown.inline("\\*not italic\\*"))
        assertEquals(listOf(Span("a "), Span("b", bold = true), Span(" `c")), Markdown.inline("a __b__ `c"))
    }
}
