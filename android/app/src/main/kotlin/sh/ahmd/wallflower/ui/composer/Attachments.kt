package sh.ahmd.wallflower.ui.composer

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.ahmd.wallflower.Uploads
import sh.ahmd.wallflower.program.Command
import sh.ahmd.wallflower.ui.Glyph
import sh.ahmd.wallflower.ui.GlyphIcon
import sh.ahmd.wallflower.ui.MenuAction
import sh.ahmd.wallflower.ui.Colors
import sh.ahmd.wallflower.ui.Mono
import sh.ahmd.wallflower.ui.Palette
import sh.ahmd.wallflower.ui.PopupMenu
import sh.ahmd.wallflower.ui.haptics
import sh.ahmd.wallflower.ui.wordSize
import sh.ahmd.wallflower.ui.words

/**
 * Files in the composer: [uploads] on their way to the Mac, which saves them
 * in [dir], and the draft's own, by their paths there, photos shown by their
 * [thumbnail]. [onAttach] sends picked files; [onDismiss] lets a failed one go.
 */
internal class Attachments(
    val uploads: List<Uploads.Item>,
    val dir: String?,
    val thumbnail: (String) -> File?,
    val onAttach: (List<Uri>) -> Unit,
    val onDismiss: (String) -> Unit,
)

/** What the draft holds, each with × to take it out, then what's on its way; why one failed beneath. */
@Composable
internal fun AttachmentsRow(state: TextFieldState, paths: Regex?, attachments: Attachments) {
    val haptics = haptics()
    val text = state.text.toString()
    val attached = remember(text, paths) { paths?.findAll(text)?.map { it.value }?.distinct()?.toList().orEmpty() }
    val uploads = attachments.uploads
    if (attached.isEmpty() && uploads.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (path in attached) {
            key(path) {
                Attached(path, attachments.thumbnail) {
                    haptics.tick()
                    state.edit {
                        val at = asCharSequence().indexOf(path)
                        if (at >= 0) cutUpload(at, at + path.length)
                    }
                }
            }
        }
        for (u in uploads) key(u.id) { UploadChip(u) { attachments.onDismiss(u.id) } }
    }
    uploads.firstOrNull { it.error != null }?.let { u ->
        Text(
            "${u.name}: ${u.error}",
            color = Palette.red,
            fontFamily = words,
            fontSize = wordSize(11.sp, 12.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
        )
    }
}

/** + : photos, through the system's picker, or any file. The keyboard stays up for it, and comes back after ([onBack]). */
@Composable
internal fun AttachButton(
    enabled: Boolean,
    typing: () -> Boolean,
    onAttach: (List<Uri>) -> Unit,
    onBack: () -> Unit,
    commands: List<Command> = emptyList(),
    onCommand: (Command) -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    var wasTyping by remember { mutableStateOf(false) }
    val picked: (List<Uri>) -> Unit = {
        if (it.isNotEmpty()) onAttach(it)
        if (wasTyping) onBack()
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACH), picked)
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), picked)
    Box(Modifier.padding(end = 6.dp)) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Palette.bg2)
                .border(1.dp, Palette.border, CircleShape)
                .clickable(enabled = enabled) { // the menu's opening is felt
                    wasTyping = typing()
                    open = true
                },
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(Glyph.Plus, if (enabled) Palette.fg else Palette.dim, size = 14.dp) }
        PopupMenu(
            expanded = open,
            onDismiss = { open = false },
            keepKeyboard = true,
            actions = listOf(
                MenuAction("photos") {
                    open = false
                    photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                MenuAction("files") {
                    open = false
                    files.launch(arrayOf("*/*"))
                },
            ) + commands.map { c ->
                MenuAction(c.text, Palette.purple, font = Mono, detail = c.about) {
                    open = false
                    onCommand(c)
                }
            },
        )
    }
}

/** A file in the draft: a photo as itself (its preview, found off the main thread), anything else by its name; × takes it out. */
@Composable
private fun Attached(path: String, thumbnail: (String) -> File?, onRemove: () -> Unit) {
    val image by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { thumbnail(path)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
    }
    Tile(image, path.substringAfterLast('/')) { Remove(Modifier.align(Alignment.TopEnd), onRemove) }
}

/** A file on its way, and how far along; failed, tap to let it go (why is said beneath). */
@Composable
private fun UploadChip(u: Uploads.Item, onDismiss: () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val thumb by produceState<ImageBitmap?>(null, u.uri) {
        value = withContext(Dispatchers.IO) { runCatching { resolver.loadThumbnail(u.uri, android.util.Size(192, 192), null).asImageBitmap() }.getOrNull() }
    }
    Tile(thumb, u.name, Modifier.clickable(enabled = u.error != null, onClick = onDismiss)) {
        Box(Modifier.matchParentSize().background(if (u.error != null) Palette.red.copy(alpha = 0.3f) else Palette.bg0.copy(alpha = 0.55f)))
        if (u.error != null) {
            Remove(Modifier.align(Alignment.TopEnd), onDismiss)
        } else {
            CircularProgressIndicator(
                progress = { if (u.size > 0) u.sent.toFloat() / u.size else 0f },
                modifier = Modifier.align(Alignment.Center).size(22.dp),
                color = Palette.fg,
                trackColor = Palette.bg3,
                strokeWidth = 2.5.dp,
            )
        }
    }
}

/** A square for a file: its picture, or a paperclip and its name. */
@Composable
private fun Tile(image: ImageBitmap?, name: String, modifier: Modifier = Modifier, over: @Composable BoxScope.() -> Unit) {
    Box(modifier.size(TILE).clip(RoundedCornerShape(12.dp)).background(Palette.bg2)) {
        if (image != null) {
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(CLIP, color = Palette.grey, fontFamily = Mono, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center).padding(bottom = 10.dp))
            Text(
                name,
                color = Palette.fg,
                fontFamily = Mono,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 5.dp, end = 5.dp, bottom = 6.dp),
            )
        }
        over()
    }
}

@Composable
private fun Remove(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .padding(4.dp)
            .size(20.dp)
            .clip(CircleShape)
            .background(Palette.bg0.copy(alpha = 0.8f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(Glyph.Close, Palette.fg, size = 9.dp) }
}

/** An upload's path in the box shows as its name, marked as a file in [colors]. */
internal class UploadNames(private val paths: Regex, private val colors: Colors) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        val found = paths.findAll(asCharSequence().toString()).toList()
        var shift = 0
        val names = ArrayList<IntRange>()
        for (m in found) {
            val name = "$CLIP " + m.value.substringAfterLast('/')
            val start = m.range.first + shift
            replace(start, m.range.last + 1 + shift, name)
            names += start until start + name.length
            shift += name.length - m.value.length
        }
        for (r in names) addStyle(SpanStyle(color = colors.aqua, background = colors.bg2), r.first, r.last + 1)
    }
}

/** Deleting into an upload's path deletes all of it, as one thing: it shows as one. */
@OptIn(ExperimentalFoundationApi::class)
internal class WholeUploads(private val paths: Regex) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount != 1) return
        val now = changes.getRange(0)
        val was = changes.getOriginalRange(0)
        if (!now.collapsed || was.collapsed) return // only a deletion
        val hit = paths.findAll(originalText.toString()).firstOrNull { it.range.first < was.max && was.min <= it.range.last } ?: return
        revertAllChanges()
        cutUpload(hit.range.first, hit.range.last + 1)
    }
}

/** Takes an upload's path out, with the space after it, or its line if it had one to itself. */
private fun TextFieldBuffer.cutUpload(start: Int, end: Int) {
    val s = asCharSequence()
    val alone = start == 0 || s[start - 1] == '\n'
    val until = when {
        end < length && s[end] == ' ' -> end + 1
        end < length && s[end] == '\n' && alone -> end + 1
        else -> end
    }
    replace(start, until, "")
    selection = TextRange(start)
}

private const val CLIP = "" // the font's paperclip
private const val MAX_ATTACH = 10
private val TILE = 56.dp
