package sh.ahmd.wallflower.ui.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.ahmd.wallflower.BarKey
import sh.ahmd.wallflower.ui.FloatAbove
import sh.ahmd.wallflower.ui.Glyph
import sh.ahmd.wallflower.ui.GlyphIcon
import sh.ahmd.wallflower.ui.Mono
import sh.ahmd.wallflower.ui.Opener
import sh.ahmd.wallflower.ui.Palette
import sh.ahmd.wallflower.ui.Type
import sh.ahmd.wallflower.ui.faceOf
import sh.ahmd.wallflower.ui.glyphPlaceholders
import sh.ahmd.wallflower.ui.glyphs
import sh.ahmd.wallflower.ui.haptics

/**
 * While [menu] is open, the pane's [shortcuts] over the bar, on a panel of
 * their own, then a pill to add one. Three rows of them at most: more go on
 * pages to swipe between. One tapped goes and they shut; a tap anywhere
 * else puts them away.
 */
@Composable
internal fun ShortcutsUp(menu: Opener, anchor: Rect, shortcuts: List<BarKey>, onShortcut: (BarKey) -> Unit, onAdd: () -> Unit) {
    if (!menu.open) return
    val haptics = haptics()
    fun pick(key: BarKey) {
        haptics.key()
        menu.open = false
        onShortcut(key)
    }
    fun add() {
        menu.open = false
        onAdd()
    }
    FloatAbove(anchor, gap = 16.dp, onDismiss = menu::dismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(PANEL)
                .background(Palette.bg1)
                .border(1.dp, Palette.border, PANEL)
                .padding(PAD),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val measurer = rememberTextMeasurer()
                val density = LocalDensity.current
                val width = constraints.maxWidth
                val label = Type.label
                val pages = remember(shortcuts, width, density, label) {
                    with(density) {
                        val sides = (PILL_PAD * 2).roundToPx()
                        val widths = shortcuts.map { s ->
                            val face = faceOf(s)
                            measurer.measure(face, FACE, placeholders = glyphPlaceholders(face), density = density).size.width + sides
                        } + (measurer.measure(ADD, label, density = density).size.width + (14.dp + 6.dp).roundToPx() + sides)
                        paged(widths, width, GAP.roundToPx(), ROWS)
                    }
                }
                val rows = pages.maxOf { it.size }
                val pager = rememberPagerState { pages.size }
                Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                    HorizontalPager(pager, Modifier.height(SHORTCUT * rows + GAP * (rows - 1)), pageSpacing = PAD * 2, verticalAlignment = Alignment.Top) { page ->
                        Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                            for (row in pages[page]) {
                                Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                                    for (i in row) {
                                        val s = shortcuts.getOrNull(i)
                                        if (s == null) {
                                            AddShortcut(::add)
                                        } else {
                                            ShortcutPlace({ pick(s) }) {
                                                Text(faceOf(s), color = Palette.fg, fontFamily = Mono, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, inlineContent = glyphs(Palette.fg))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (pages.size > 1) {
                        Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            repeat(pages.size) { p -> Box(Modifier.size(6.dp).clip(CircleShape).background(if (p == pager.currentPage) Palette.fg else Palette.bg3)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Items [widths] wide, laid in rows [width] wide, [gap] apart, [rows] rows
 * to a page: each page's rows, as the items' places. One too wide for a row
 * has a row to itself.
 */
internal fun paged(widths: List<Int>, width: Int, gap: Int, rows: Int): List<List<List<Int>>> {
    val pages = ArrayList<List<List<Int>>>()
    var page = ArrayList<List<Int>>()
    var row = ArrayList<Int>()
    var used = 0
    widths.forEachIndexed { i, w ->
        if (row.isNotEmpty() && used + gap + w > width) {
            page.add(row)
            row = ArrayList()
            if (page.size == rows) {
                pages.add(page)
                page = ArrayList()
            }
        }
        used = if (row.isEmpty()) w else used + gap + w
        row.add(i)
    }
    if (row.isNotEmpty()) page.add(row)
    if (page.isNotEmpty()) pages.add(page)
    return pages
}

/** A shortcut on the panel: lit while pressed, and [onTap] tapped; [muted], it has no fill till then. */
@Composable
private fun ShortcutPlace(onTap: () -> Unit, modifier: Modifier = Modifier, muted: Boolean = false, content: @Composable () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .height(SHORTCUT)
            .clip(CircleShape)
            .background(
                when {
                    pressed -> Palette.bg3
                    muted -> Color.Transparent
                    else -> Palette.bg2
                },
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { tap() },
                )
            }
            .padding(horizontal = PILL_PAD),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The pill after the shortcuts that adds one: muted, its edge dashed. */
@Composable
private fun AddShortcut(onTap: () -> Unit) {
    val ink = Palette.grey
    val edge = Palette.dim
    ShortcutPlace(
        onTap,
        Modifier.drawWithContent {
            drawContent()
            val w = 1.dp.toPx()
            drawRoundRect(
                edge,
                topLeft = Offset(w / 2, w / 2),
                size = Size(size.width - w, size.height - w),
                cornerRadius = CornerRadius((size.height - w) / 2),
                style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
            )
        },
        muted = true,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GlyphIcon(Glyph.Plus, ink, size = 14.dp)
            Text(ADD, color = ink, style = Type.label, maxLines = 1)
        }
    }
}

private const val ROWS = 3
private const val ADD = "add shortcut"
private val SHORTCUT = 40.dp
private val GAP = 6.dp
private val PAD = 8.dp
private val PILL_PAD = 16.dp
private val PANEL = RoundedCornerShape(20.dp)
private val FACE = TextStyle(fontFamily = Mono, fontSize = 14.sp)
