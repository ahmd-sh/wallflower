package sh.ahmd.wallflower.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import sh.ahmd.wallflower.markdown.Block
import sh.ahmd.wallflower.markdown.ImagePaths
import sh.ahmd.wallflower.markdown.Markdown

/**
 * What opens a picture's path that's mentioned in markdown ([ImagePaths]),
 * as it's written; none where paths can't be opened.
 */
val LocalPicturePaths = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** Markdown, rendered in wallflower's fonts: words in [words], code in [Mono]; pictures' paths tap to open, where they can be ([LocalPicturePaths]). */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { Markdown.parse(text) }
    Blocks(blocks, modifier)
}

@Composable
private fun Blocks(blocks: List<Block>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (b in blocks) BlockView(b)
    }
}

@Composable
private fun BlockView(b: Block) {
    val body = bodyStyle()
    when (b) {
        is Block.Heading -> Text(
            inline(b.text),
            style = body.copy(
                fontSize = when (b.level) { 1 -> 21.sp; 2 -> 18.sp; else -> 16.sp },
                lineHeight = when (b.level) { 1 -> 28.sp; 2 -> 25.sp; else -> 22.sp },
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.padding(top = 4.dp),
        )
        is Block.Paragraph -> Text(inline(b.text), style = body)
        is Block.Code -> Text(
            b.text,
            style = TextStyle(color = Palette.fg, fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp),
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Palette.bg1)
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        )
        is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Palette.bg3))
            Blocks(b.blocks, Modifier.padding(start = 12.dp))
        }
        is Block.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            b.items.forEachIndexed { i, item ->
                Row {
                    Text(
                        if (b.ordered) "${b.start + i}." else "•",
                        style = body.copy(color = Palette.grey),
                        modifier = Modifier.widthIn(min = if (b.ordered) 22.dp else 14.dp),
                    )
                    Blocks(item, Modifier.padding(start = 4.dp))
                }
            }
        }
        is Block.Table -> Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Palette.bg1)
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
        ) {
            // Columns of single-line cells line up without measuring rows.
            for (c in b.header.indices) {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    Text(inline(b.header[c]), style = body.copy(fontWeight = FontWeight.SemiBold), softWrap = false, modifier = Modifier.padding(vertical = 6.dp))
                    for (row in b.rows) {
                        Text(inline(row[c]), style = body, softWrap = false, modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }
        }
        Block.Rule -> Rule(Modifier.padding(vertical = 4.dp), Palette.bg3)
    }
}

@Composable
private fun bodyStyle() = TextStyle(
    color = Palette.fg,
    fontFamily = words,
    fontSize = wordSize(14.sp, 15.sp),
    lineHeight = wordSize(21.sp, 22.sp),
)

@Composable
private fun inline(text: String): AnnotatedString {
    val font = words
    val colors = Palette
    val paths = LocalPicturePaths.current
    val opening by rememberUpdatedState(paths)
    return remember(text, font, colors, paths != null) { styled(text, font, colors, if (paths != null) { p -> opening?.invoke(p) } else null) }
}

private fun styled(text: String, words: FontFamily, colors: Colors, open: ((String) -> Unit)? = null): AnnotatedString = buildAnnotatedString {
    // A picture's path, to tap: underlined, in aqua unless it's code.
    fun picture(path: String, code: Boolean, piece: () -> Unit) {
        val look = SpanStyle(color = if (code) Color.Unspecified else colors.aqua, textDecoration = TextDecoration.Underline)
        withLink(LinkAnnotation.Clickable(path, TextLinkStyles(look)) { open?.invoke(path) }) { piece() }
    }
    for (span in Markdown.inline(text)) {
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.SemiBold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            textDecoration = if (span.strike) TextDecoration.LineThrough else null,
            fontFamily = if (span.code) Mono else words,
            fontSize = if (span.code) 0.9.em else TextUnit.Unspecified,
            color = if (span.code) colors.orange else Color.Unspecified,
            background = if (span.code) colors.bg2 else Color.Unspecified,
        )
        val link = span.link
        if (link != null) {
            withLink(LinkAnnotation.Url(link, TextLinkStyles(SpanStyle(color = colors.blue, textDecoration = TextDecoration.Underline)))) {
                withStyle(style) { append(span.text) }
            }
        } else if (open != null && span.code && ImagePaths.whole(span.text)) {
            picture(span.text, code = true) { withStyle(style) { append(span.text) } }
        } else if (open != null && !span.code) {
            var at = 0
            for (r in ImagePaths.find(span.text)) {
                withStyle(style) { append(span.text.substring(at, r.first)) }
                val path = span.text.substring(r)
                picture(path, code = false) { withStyle(style) { append(path) } }
                at = r.last + 1
            }
            withStyle(style) { append(span.text.substring(at)) }
        } else {
            withStyle(style) { append(span.text) }
        }
    }
}
