package sh.ahmd.wallflower.ui.mobile

import android.webkit.MimeTypeMap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.ahmd.wallflower.read.Entry
import sh.ahmd.wallflower.read.Party
import sh.ahmd.wallflower.ui.Glyph
import sh.ahmd.wallflower.ui.GlyphIcon
import sh.ahmd.wallflower.ui.MarkdownText
import sh.ahmd.wallflower.ui.MenuAction
import sh.ahmd.wallflower.ui.Motion
import sh.ahmd.wallflower.ui.Palette
import sh.ahmd.wallflower.ui.Pictures
import sh.ahmd.wallflower.ui.PopupMenu
import sh.ahmd.wallflower.ui.Type
import sh.ahmd.wallflower.ui.files.LocalViewer
import sh.ahmd.wallflower.ui.files.Viewed
import sh.ahmd.wallflower.ui.haptics
import sh.ahmd.wallflower.ui.rememberOpener
import sh.ahmd.wallflower.ui.slides
import sh.ahmd.wallflower.ui.wordSize
import sh.ahmd.wallflower.ui.words

/** Something the user said: tapped, it opens if it's cut short; held, it offers to [onEdit] it and send it again. */
@Composable
internal fun UserPrompt(text: String, onEdit: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    var cut by remember { mutableStateOf(false) }
    val menu = rememberOpener()
    val copier = rememberCopier()
    val style = TextStyle(color = Palette.fg, fontFamily = words, fontSize = wordSize(14.sp, 15.sp), lineHeight = wordSize(21.sp, 22.sp))
    Column(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalAlignment = Alignment.End) {
        Column(
            Modifier
                .animateContentSize(Motion.spatial())
                .clip(RoundedCornerShape(18.dp))
                .background(Palette.bg2)
                .slides(menu)
                .combinedClickable(
                    hapticFeedbackEnabled = false, // the menu's opening is felt, as the setting allows
                    onClick = { open = !open },
                    onLongClick = { menu.open = true },
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text,
                style = style,
                maxLines = if (open) Int.MAX_VALUE else 12,
                overflow = TextOverflow.Clip,
                onTextLayout = { if (!open) cut = it.hasVisualOverflow },
            )
            // What's cut off is said on a line of its own, as something to open.
            if (cut && !open) Text("…", style = style.copy(color = Palette.blue, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 16.sp))
            PopupMenu(
                menu,
                actions = listOfNotNull(
                    onEdit?.let { edit -> MenuAction("edit") { menu.open = false; edit() } },
                    MenuAction("copy") { menu.open = false; copier.copy(text) },
                ),
            )
        }
        CopyIcon { text }
    }
}

@Composable
internal fun Reply(text: String, copyable: Boolean, beside: (@Composable () -> Unit)? = null) {
    val copier = rememberCopier()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Held, words select, to copy some of it.
        SelectionContainer { MarkdownText(text) }
        if (copyable) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { copier.copy(text) }
                    .padding(vertical = 4.dp, horizontal = 2.dp),
            ) {
                Crossfade(copier.copied, animationSpec = Motion.effect(), label = "copy") {
                    Text(if (it) "copied" else "copy", color = Palette.dim, style = Type.caption)
                }
            }
            beside?.invoke()
        }
    }
}

/** A small copy button, ticked a moment after; [text] is what it copies, made when tapped. */
@Composable
internal fun CopyIcon(modifier: Modifier = Modifier, text: () -> String) {
    val copier = rememberCopier()
    Box(modifier.clip(CircleShape).clickable { copier.copy(text()) }.padding(6.dp)) {
        Crossfade(copier.copied, animationSpec = Motion.effect(), label = "copy") {
            GlyphIcon(if (it) Glyph.Check else Glyph.Copy, Palette.dim, size = 13.dp)
        }
    }
}

/** A picture, as big as it fits; tapped, it opens in the viewer, at its full size. */
@Composable
internal fun Picture(e: Entry.Picture, fetch: suspend (String) -> ByteArray?) {
    val viewer = LocalViewer.current
    val haptics = haptics()
    val width = LocalWindowInfo.current.containerSize.width
    val height = with(LocalDensity.current) { PICTURE_HEIGHT.roundToPx() }
    val bitmap by produceState(Pictures.kept(e.id), e.id) {
        if (value == null) value = fetch(e.id)?.let { Pictures.decode(e.id, it, width, height) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (e.by == Party.User) Arrangement.End else Arrangement.Start) {
        val shown by animateFloatAsState(if (bitmap != null) 1f else 0f, Motion.effect(), label = "picture")
        bitmap?.let {
            Image(
                it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .heightIn(max = PICTURE_HEIGHT)
                    .graphicsLayer { alpha = shown }
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = viewer != null) {
                        haptics.tick()
                        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(e.mime) ?: "png"
                        viewer?.open(Viewed.Picture("picture.$ext") { fetch(e.id) })
                    },
            )
        }
    }
}

/** Something the user sent that waits for the program to take it: theirs, faint, said to be waiting. */
@Composable
internal fun Queued(text: String) {
    Column(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text,
            color = Palette.grey,
            fontFamily = words,
            fontSize = wordSize(14.sp, 15.sp),
            lineHeight = wordSize(21.sp, 22.sp),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, Palette.bg3, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
        Text("queued", color = Palette.dim, style = Type.caption, modifier = Modifier.padding(end = 6.dp))
    }
}

private val PICTURE_HEIGHT = 260.dp
