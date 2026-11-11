package sh.ahmd.wallflower.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sh.ahmd.wallflower.files.Fetches
import sh.ahmd.wallflower.files.Kind
import sh.ahmd.wallflower.files.ago
import sh.ahmd.wallflower.files.iconOf
import sh.ahmd.wallflower.files.kindOf
import sh.ahmd.wallflower.files.sizeOf
import sh.ahmd.wallflower.prefs.Pref
import sh.ahmd.wallflower.protocol.FileEntry
import sh.ahmd.wallflower.protocol.Listing
import sh.ahmd.wallflower.session.Session
import sh.ahmd.wallflower.session.Status
import sh.ahmd.wallflower.ui.Glyph
import sh.ahmd.wallflower.ui.GlyphIcon
import sh.ahmd.wallflower.ui.MenuAction
import sh.ahmd.wallflower.ui.Mono
import sh.ahmd.wallflower.ui.Motion
import sh.ahmd.wallflower.ui.Palette
import sh.ahmd.wallflower.ui.PopupMenu
import sh.ahmd.wallflower.ui.Rule
import sh.ahmd.wallflower.ui.Type
import sh.ahmd.wallflower.ui.copy
import sh.ahmd.wallflower.ui.grotesque
import sh.ahmd.wallflower.ui.haptics
import sh.ahmd.wallflower.ui.rememberLast
import sh.ahmd.wallflower.ui.rememberOpener
import sh.ahmd.wallflower.ui.slides
import sh.ahmd.wallflower.ui.wordSize
import sh.ahmd.wallflower.ui.words

/** A folder as last listed, or why it couldn't be. */
private sealed interface Listed {
    data class Done(val listing: Listing) : Listed
    data class Lost(val why: String) : Listed
}

/**
 * The Mac's files, from [start] (a pane's directory, or ~): a folder's
 * folders, then its files, hidden ones only when asked ([hidden]). A folder
 * tapped goes in, and back comes out again; a step of the path along the
 * top goes there. A file tapped opens in the viewer. Held, an entry offers
 * to share it, put its path in [pane]'s message ([onAdd]), or copy its path.
 * A folder seen before shows as it was at once, and as it is once listed.
 */
@Composable
internal fun FilesPage(
    session: Session,
    start: String,
    pane: String?,
    hidden: Pref<Boolean>,
    fetches: Fetches,
    onAdd: ((String) -> Unit)?,
    onClose: () -> Unit,
) {
    val haptics = haptics()
    val viewer = LocalViewer.current
    val trail = remember { mutableStateListOf(start) } // folders gone into, as asked for
    val listed = remember { mutableStateMapOf<String, Listed>() }
    val here = trail.last()
    val showHidden by hidden.flow.collectAsStateWithLifecycle()
    var sharing by remember { mutableStateOf<Sharing?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(here) {
        session.status.first { it is Status.Connected }
        val l = try {
            Listed.Done(session.list(here))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Listed.Lost(e.message ?: "couldn't be listed")
        }
        listed[here] = l
        if (l is Listed.Done && l.listing.error == null) listed[l.listing.path] = l // as the path along the top goes to it
    }
    val back: () -> Unit = {
        if (trail.size > 1) trail.removeAt(trail.lastIndex) else onClose()
    }
    BackHandler(enabled = trail.size > 1) { back() }

    fun go(path: String) {
        if (path == (listed[here] as? Listed.Done)?.listing?.path) return
        haptics.tick()
        trail += path
    }

    fun share(path: String, entry: FileEntry) {
        sharing?.job?.cancel()
        val job = scope.launch {
            try {
                val f = fetches.kept(path, entry.size, entry.mtime) ?: fetches.place(entry.name).also { f ->
                    session.fetch(path, f) { got, of -> sharing = sharing?.copy(got = got, of = of) }
                    fetches.keep(path, entry.size, entry.mtime, f)
                }
                Fetches.share(context, f)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                haptics.reject()
            } finally {
                sharing = null
            }
        }
        sharing = Sharing(entry.name, 0, entry.size, job)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.bg0)
            .pointerInput(Unit) { detectTapGestures { } } // nothing reaches the terminal underneath
            .systemBarsPadding()
            .imePadding(),
    ) {
        val listing = (listed[here] as? Listed.Done)?.listing
        val path = listing?.path ?: here
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹",
                color = Palette.fg,
                fontFamily = Mono,
                fontSize = 22.sp,
                modifier = Modifier.clip(CircleShape).clickable(onClick = back).padding(horizontal = 14.dp, vertical = 4.dp),
            )
            Text(
                nameOf(path),
                color = Palette.fg,
                fontFamily = words,
                fontSize = wordSize(16.sp, 22.sp),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val eye by animateColorAsState(if (showHidden) Palette.orange else Palette.grey, Motion.effect(), label = "hidden")
            Text(
                if (showHidden) "" else "",
                color = eye,
                fontFamily = Mono,
                fontSize = 16.sp,
                modifier = Modifier.clip(CircleShape).clickable {
                    haptics.toggle(!showHidden)
                    hidden.set(!showHidden)
                }.padding(12.dp),
            )
            Box(Modifier.clip(CircleShape).clickable(onClick = onClose).padding(13.dp)) { GlyphIcon(Glyph.Close, Palette.fg, size = 14.dp) }
        }
        Crumbs(path, onGo = ::go)
        Rule(color = Palette.border)

        AnimatedContent(
            targetState = trail.size to here,
            transitionSpec = {
                val sign = if (targetState.first >= initialState.first) 1 else -1
                (slideInHorizontally(Motion.spatial()) { sign * it / 5 } + fadeIn(Motion.effect())) togetherWith
                    (slideOutHorizontally(Motion.spatial()) { -sign * it / 5 } + fadeOut(Motion.effect()))
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "folder",
        ) { (_, at) ->
            when (val l = listed[at]) {
                null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = Palette.grey, trackColor = Palette.bg2, strokeWidth = 2.5.dp)
                }
                is Listed.Lost -> Said(l.why)
                is Listed.Done -> if (l.listing.error != null) {
                    Said(l.listing.error!!)
                } else {
                    Entries(
                        l.listing,
                        showHidden,
                        onOpen = { full, e ->
                            if (e.dir) {
                                go(full)
                            } else if (e.kind == FileEntry.OTHER) {
                                haptics.reject()
                            } else {
                                haptics.tick()
                                viewer?.open(Viewed.OnMac(full, e.size, e.mtime), pane)
                            }
                        },
                        onShare = ::share,
                        onAdd = onAdd,
                        onCopy = { full ->
                            haptics.tick()
                            copy(context, full)
                        },
                    )
                }
            }
        }

        AnimatedVisibility(sharing != null, enter = fadeIn(Motion.effect()) + expandVertically(Motion.spatial()), exit = fadeOut(Motion.effect()) + shrinkVertically(Motion.spatial())) {
            val s = rememberLast(sharing) ?: return@AnimatedVisibility
            Row(
                Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(12.dp)).background(Palette.bg1).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val of = s.of.takeIf { it > 0 }
                if (of != null) {
                    CircularProgressIndicator(progress = { (s.got.toFloat() / of).coerceIn(0f, 1f) }, modifier = Modifier.size(18.dp), color = Palette.fg, trackColor = Palette.bg3, strokeWidth = 2.5.dp)
                } else {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Palette.fg, trackColor = Palette.bg3, strokeWidth = 2.5.dp)
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(s.name, color = Palette.fg, style = Type.label, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    Text(of?.let { "${sizeOf(s.got)} of ${sizeOf(it)}, to share" } ?: "fetching, to share", color = Palette.dim, style = Type.caption)
                }
                Box(Modifier.clip(CircleShape).clickable { s.job.cancel() }.padding(12.dp)) { GlyphIcon(Glyph.Close, Palette.grey, size = 12.dp) }
            }
        }
    }
}

/** A file on its way to be shared: how much of it has come. */
private data class Sharing(val name: String, val got: Long, val of: Long, val job: Job)

/** The folders [path] is in, from the top (the home as ~), each a step to tap; the last is where it is. */
@Composable
private fun Crumbs(path: String, onGo: (String) -> Unit) {
    val steps = remember(path) { stepsOf(path) }
    val scroll = rememberScrollState()
    LaunchedEffect(path) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(start = 18.dp, end = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { i, (name, full) ->
            if (i > 0) Text("›", color = Palette.dim, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 2.dp))
            val last = i == steps.lastIndex
            Text(
                name,
                color = if (last) Palette.fg else Palette.grey,
                fontFamily = Mono,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = !last) { onGo(full) }.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}

/** A folder's entries: its folders, then its files, by name; hidden ones only if [showHidden]. */
@Composable
private fun Entries(
    listing: Listing,
    showHidden: Boolean,
    onOpen: (String, FileEntry) -> Unit,
    onShare: (String, FileEntry) -> Unit,
    onAdd: ((String) -> Unit)?,
    onCopy: (String) -> Unit,
) {
    val shown = remember(listing, showHidden) {
        listing.entries.filter { showHidden || !it.hidden }.sortedWith(compareBy<FileEntry> { !it.dir }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
    if (shown.isEmpty()) {
        val unseen = listing.entries.count { it.hidden }
        return Said(if (unseen > 0) "only hidden files here ($unseen)" else "nothing here")
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(shown, key = { it.name }) { e ->
            val full = join(listing.path, e.name)
            EntryRow(e, onOpen = { onOpen(full, e) }, onShare = { onShare(full, e) }.takeIf { e.kind == FileEntry.FILE }, onAdd = onAdd?.let { { it(full) } }, onCopy = { onCopy(full) })
        }
        if (listing.more) item { Said("and more, past the first ${listing.entries.size}", Modifier.padding(vertical = 12.dp)) }
    }
}

/** An entry: its icon, its name, and what it is (a folder, or its size) and when it changed; held, what can be done with it. */
@Composable
private fun EntryRow(e: FileEntry, onOpen: () -> Unit, onShare: (() -> Unit)?, onAdd: (() -> Unit)?, onCopy: () -> Unit) {
    val menu = rememberOpener()
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .slides(menu)
                .combinedClickable(
                    hapticFeedbackEnabled = false, // the menu's opening is felt, as the setting allows
                    onClick = onOpen,
                    onLongClick = { menu.open = true },
                )
                .graphicsLayer { alpha = if (e.hidden) 0.55f else 1f }
                .padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (e.kind == FileEntry.OTHER) "" else iconOf(e.name, e.dir),
                color = colorOf(e),
                fontFamily = Mono,
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(26.dp),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(e.name + if (e.dir) "/" else "", color = if (e.kind == FileEntry.OTHER) Palette.grey else Palette.fg, fontFamily = Mono, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                val what = listOfNotNull(
                    when (e.kind) {
                        FileEntry.DIR -> "folder"
                        FileEntry.FILE -> sizeOf(e.size)
                        else -> if (e.link) "a link to nothing" else "not a file"
                    },
                    e.mtime.takeIf { it > 0 }?.let { ago(it) },
                ).joinToString(" · ")
                Text(what, color = Palette.dim, style = Type.caption, maxLines = 1)
            }
            if (e.link) Text("", color = Palette.dim, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
        }
        PopupMenu(
            menu,
            actions = listOfNotNull(
                onShare?.let { MenuAction("share") { menu.open = false; it() } },
                onAdd?.let { MenuAction("add to message") { menu.open = false; it() } },
                MenuAction("copy path") { menu.open = false; onCopy() },
            ),
        )
    }
}

/** A line said in the middle of where entries would be: why there are none. */
@Composable
private fun Said(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Palette.grey, style = Type.label, textAlign = TextAlign.Center, lineHeight = if (grotesque) 20.sp else Type.label.lineHeight)
    }
}

@Composable
private fun colorOf(e: FileEntry): Color = when {
    e.dir -> Palette.blue
    e.kind == FileEntry.OTHER -> Palette.dim
    else -> when (kindOf(e.name)) {
        Kind.Picture -> Palette.aqua
        Kind.Pdf -> Palette.red
        Kind.Markdown -> Palette.purple
        Kind.Text -> Palette.green
        Kind.Other -> Palette.grey
    }
}

/** A folder's name to head it: its own, ~ for a home, / for the top. */
internal fun nameOf(path: String): String = stepsOf(path).lastOrNull()?.first ?: path

/** The steps down to [path] from the top, each its name (~ for a home) and where it is. */
internal fun stepsOf(path: String): List<Pair<String, String>> {
    if (!path.startsWith('/')) return listOf(path to path)
    val parts = path.split('/').filter { it.isNotEmpty() }
    val home = parts.size >= 2 && parts[0] in HOMES
    val steps = mutableListOf("/" to "/")
    parts.forEachIndexed { i, _ ->
        val full = "/" + parts.take(i + 1).joinToString("/")
        when {
            home && i == 0 -> Unit
            home && i == 1 -> steps += "~" to full
            else -> steps += parts[i] to full
        }
    }
    return steps
}

/** [name] in the folder at [dir]. */
private fun join(dir: String, name: String) = if (dir.endsWith('/')) dir + name else "$dir/$name"

private val HOMES = setOf("Users", "home")
