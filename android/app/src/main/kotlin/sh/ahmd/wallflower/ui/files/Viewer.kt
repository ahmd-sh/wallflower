package sh.ahmd.wallflower.ui.files

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.ahmd.wallflower.files.Fetches
import sh.ahmd.wallflower.files.Kind
import sh.ahmd.wallflower.files.iconOf
import sh.ahmd.wallflower.files.kindOf
import sh.ahmd.wallflower.files.looksLikeText
import sh.ahmd.wallflower.files.sizeOf
import sh.ahmd.wallflower.session.Session
import sh.ahmd.wallflower.ui.Glyph
import sh.ahmd.wallflower.ui.GlyphIcon
import sh.ahmd.wallflower.ui.MarkdownText
import sh.ahmd.wallflower.ui.Mono
import sh.ahmd.wallflower.ui.Motion
import sh.ahmd.wallflower.ui.Palette
import sh.ahmd.wallflower.ui.Pill
import sh.ahmd.wallflower.ui.Type
import sh.ahmd.wallflower.ui.copy
import sh.ahmd.wallflower.ui.haptics
import sh.ahmd.wallflower.ui.rememberLast
import sh.ahmd.wallflower.ui.words

/** What the viewer shows: a file on the computer, or a picture in hand. */
sealed interface Viewed {
    val name: String

    /**
     * The file at [path] on the computer (absolute, or from ~), fetched to show:
     * its [size] and [mtime], if a listing said, and a small picture of it to
     * show meanwhile ([placeholder]), if there's one.
     */
    data class OnMac(val path: String, val size: Long? = null, val mtime: Long? = null, val placeholder: File? = null) : Viewed {
        override val name: String get() = path.trimEnd('/').substringAfterLast('/')
    }

    /** A picture a mobile view has, its bytes had by [load]; null if they're gone. */
    class Picture(override val name: String, val load: suspend () -> ByteArray?) : Viewed
}

/**
 * What the viewer shows ([shown]), over everything, until it's closed; a
 * file's path can go into [pane]'s message from it. It fetches only while
 * the gateway can ([canFetch]).
 */
@Stable
class Viewer {
    var shown by mutableStateOf<Viewed?>(null)
        private set

    /** The pane whose message a file's path can go into, if there is one. */
    var pane: String? = null
        private set

    /** The gateway shows the computer's files, so paths can be opened. */
    var canFetch by mutableStateOf(false)

    fun open(viewed: Viewed, pane: String? = null) {
        this.pane = pane
        shown = viewed
    }

    fun close() {
        shown = null
    }
}

/** The app's one viewer, for pictures and the computer's files alike. */
val LocalViewer = staticCompositionLocalOf<Viewer?> { null }

/**
 * The viewer, over everything while it shows something: it opens growing
 * a little into place, and back closes it. A file's path goes to [onAdd],
 * for a pane's message.
 */
@Composable
internal fun ViewerHost(viewer: Viewer, session: Session?, fetches: Fetches, onAdd: (pane: String, path: String) -> Unit) {
    val shown = viewer.shown
    val last = rememberLast(shown)
    AnimatedVisibility(
        shown != null,
        enter = fadeIn(Motion.effect()) + scaleIn(Motion.spatial(), initialScale = 0.94f),
        exit = fadeOut(Motion.effect()) + scaleOut(Motion.spatial(), targetScale = 0.94f),
    ) {
        last?.let { v ->
            key(v) {
                ViewerPage(v, session, fetches, onClose = viewer::close, onAdd = viewer.pane?.let { pane -> { path: String -> onAdd(pane, path) } })
            }
        }
    }
}

/** What's shown of a file: coming, failed, or as what it is. */
private sealed interface Showing {
    data class Coming(val got: Long, val of: Long?) : Showing
    data class Failed(val why: String) : Showing
    class Picture(val bitmap: ImageBitmap) : Showing
    class Pdf(val file: File) : Showing
    /** Text, or [markdown]; [cut] if only its start came, the whole's size if that's known (else -1). */
    class Text(val text: String, val markdown: Boolean, val cut: Long?) : Showing
    /** Nothing it can show of it; [why] if it tried. */
    class Other(val why: String? = null) : Showing
}

@Composable
private fun ViewerPage(viewed: Viewed, session: Session?, fetches: Fetches, onClose: () -> Unit, onAdd: ((String) -> Unit)?) {
    BackHandler(onBack = onClose) // the latest to ask, so before any page's beneath
    val context = LocalContext.current
    val haptics = haptics()
    val scope = rememberCoroutineScope()
    val screen = LocalWindowInfo.current.containerSize
    val onMac = viewed as? Viewed.OnMac
    var showing by remember { mutableStateOf<Showing>(Showing.Coming(0, onMac?.size)) }
    var size by remember { mutableStateOf(onMac?.size) }
    var whole by remember { mutableStateOf<File?>(null) } // the whole file, here, to share
    var sharing by remember { mutableStateOf<Showing.Coming?>(null) }
    var shareJob by remember { mutableStateOf<Job?>(null) }
    var unshared by remember { mutableStateOf<String?>(null) } // why the last share couldn't be
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        showing = try {
            when (viewed) {
                is Viewed.Picture -> {
                    val bytes = viewed.load() ?: throw IOException("the picture's gone from the computer")
                    whole = withContext(Dispatchers.IO) { fetches.place(viewed.name).also { it.parentFile?.mkdirs(); it.writeBytes(bytes) } }
                    size = bytes.size.toLong()
                    picture(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), screen)
                }
                is Viewed.OnMac -> {
                    val kind = kindOf(viewed.name)
                    if (kind == Kind.Other) {
                        Showing.Other()
                    } else {
                        if (session == null) throw IOException("not connected")
                        val text = kind == Kind.Text || kind == Kind.Markdown
                        val limit = if (text && (viewed.size == null || viewed.size > TEXT_CAP)) TEXT_CAP else null
                        var cut: Long? = null
                        val file = fetches.kept(viewed.path, viewed.size, viewed.mtime) ?: fetches.place(viewed.name).also { f ->
                            val got = session.fetch(viewed.path, f, limit) { got, of -> showing = Showing.Coming(got, of) }
                            if (limit == null || got.size < limit) {
                                size = got.size
                                fetches.keep(viewed.path, viewed.size, viewed.mtime, f)
                            } else {
                                cut = viewed.size ?: -1
                            }
                        }
                        if (cut == null) whole = file
                        when (kind) {
                            Kind.Picture -> picture(ImageDecoder.createSource(file), screen)
                            Kind.Pdf -> Showing.Pdf(file)
                            else -> withContext(Dispatchers.IO) {
                                val bytes = file.readBytes()
                                if (!looksLikeText(bytes)) Showing.Other("it isn't text") else Showing.Text(bytes.decodeToString(), kind == Kind.Markdown, cut)
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            haptics.reject()
            Showing.Failed(e.message ?: "it couldn't be had")
        }
    }

    fun share() {
        haptics.tick()
        whole?.let { return Fetches.share(context, it) }
        val path = onMac?.path ?: return
        if (shareJob?.isActive == true || session == null) return
        unshared = null
        shareJob = scope.launch {
            try {
                val f = fetches.place(viewed.name)
                sharing = Showing.Coming(0, size)
                val got = session.fetch(path, f) { got, of -> sharing = Showing.Coming(got, of) }
                size = got.size
                whole = f
                fetches.keep(path, onMac.size, onMac.mtime, f)
                Fetches.share(context, f)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                haptics.reject()
                unshared = e.message ?: "it couldn't be had"
            } finally {
                sharing = null
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.bg0)
            .pointerInput(Unit) { detectTapGestures { } } // nothing reaches what's underneath
            .systemBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(CircleShape).clickable(onClick = onClose).padding(12.dp)) { GlyphIcon(Glyph.Close, Palette.fg, size = 14.dp) }
            Column(Modifier.weight(1f).padding(start = 4.dp, end = 12.dp)) {
                Text(viewed.name, color = Palette.fg, style = Type.body, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                val detail = when (val s = showing) {
                    is Showing.Coming -> s.of?.takeIf { it > 0 }?.let { "${sizeOf(s.got)} of ${sizeOf(it)}" } ?: "fetching…"
                    is Showing.Text -> s.cut?.let { if (it > 0) "the first ${sizeOf(TEXT_CAP)} of ${sizeOf(it)}" else "the first ${sizeOf(TEXT_CAP)}" } ?: size?.let(::sizeOf)
                    else -> size?.let(::sizeOf)
                }
                detail?.let { Text(it, color = Palette.dim, style = Type.caption, maxLines = 1) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                showing,
                transitionSpec = { fadeIn(Motion.effect()) togetherWith fadeOut(Motion.effect()) },
                contentKey = { it::class },
                label = "viewer",
            ) { s ->
                when (s) {
                    is Showing.Coming -> Coming(s, onMac?.placeholder)
                    is Showing.Failed -> Failed(s.why) { showing = Showing.Coming(0, onMac?.size); attempt++ }
                    is Showing.Picture -> Zoomable(s.bitmap)
                    is Showing.Pdf -> PdfPages(s.file)
                    is Showing.Text -> TextShown(s.text, s.markdown, s.cut?.let { "the first ${sizeOf(TEXT_CAP)} of it; share sends it whole" })
                    is Showing.Other -> Other(viewed.name, size, s.why)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val s = sharing
            if (s != null) {
                Progress(s, Modifier.size(18.dp))
                Text(
                    s.of?.takeIf { it > 0 }?.let { "fetching ${sizeOf(s.got)} of ${sizeOf(it)}" } ?: "fetching…",
                    color = Palette.grey,
                    style = Type.label,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Action("stop") { shareJob?.cancel() }
            } else {
                unshared?.let { Text(it, color = Palette.red, style = Type.label, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp)) }
                if (viewed is Viewed.Picture || onMac != null && session != null) Action("share", ::share)
                if (onMac != null && onAdd != null) {
                    Action("add to message") {
                        haptics.confirm()
                        onAdd(onMac.path)
                    }
                }
                if (onMac != null) {
                    Action("copy path") {
                        haptics.tick()
                        copy(context, onMac.path)
                    }
                }
            }
        }
    }
}

/** One of what can be done with what's shown, along the bottom. */
@Composable
private fun Action(label: String, onClick: () -> Unit) = Pill(AnnotatedString(label), on = false, font = words, input = Modifier.clickable(onClick = onClick))

@Composable
private fun Progress(c: Showing.Coming, modifier: Modifier) {
    val of = c.of?.takeIf { it > 0 }
    if (of != null) {
        CircularProgressIndicator(progress = { (c.got.toFloat() / of).coerceIn(0f, 1f) }, modifier = modifier, color = Palette.fg, trackColor = Palette.bg3, strokeWidth = 2.5.dp)
    } else {
        CircularProgressIndicator(modifier = modifier, color = Palette.fg, trackColor = Palette.bg3, strokeWidth = 2.5.dp)
    }
}

/** On its way: how far along, over the small picture of it if there's one. */
@Composable
private fun Coming(c: Showing.Coming, placeholder: File?) {
    val thumb by produceState<ImageBitmap?>(null, placeholder) {
        value = placeholder?.let { withContext(Dispatchers.IO) { runCatching { android.graphics.BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull() } }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        thumb?.let { Image(it, null, Modifier.fillMaxSize().graphicsLayer { alpha = 0.5f }, contentScale = ContentScale.Fit) }
        Progress(c, Modifier.size(36.dp))
    }
}

@Composable
private fun Failed(why: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(why, color = Palette.grey, style = Type.body, textAlign = TextAlign.Center)
        Action("try again", onRetry)
    }
}

/** A file it can't show: its icon, name and size, to share to an app that can. */
@Composable
private fun Other(name: String, size: Long?, why: String?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(iconOf(name, dir = false), color = Palette.grey, fontFamily = Mono, fontSize = 56.sp)
        Text(name, color = Palette.fg, style = Type.body, fontFamily = Mono, textAlign = TextAlign.Center)
        Text(
            listOfNotNull(size?.let(::sizeOf), why, "share it to open it in another app").joinToString(" · "),
            color = Palette.dim,
            style = Type.caption,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A picture to look closer at: pinched, it zooms about the fingers, up to
 * [MAX_ZOOM], and pans while zoomed, never past its edges; a double tap
 * zooms in where it's tapped, or back out.
 */
@Composable
private fun Zoomable(bitmap: ImageBitmap) {
    val scope = rememberCoroutineScope()
    val zoom = remember { Animatable(1f) }
    val pan = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    fun bound(o: Offset, s: Float): Offset {
        if (area.width == 0 || area.height == 0) return Offset.Zero
        val fit = min(area.width.toFloat() / bitmap.width, area.height.toFloat() / bitmap.height)
        val maxX = max(0f, (bitmap.width * fit * s - area.width) / 2)
        val maxY = max(0f, (bitmap.height * fit * s - area.height) / 2)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { area = it }
            .pointerInput(bitmap) {
                detectTapGestures(onDoubleTap = { at ->
                    val center = Offset(area.width / 2f, area.height / 2f)
                    val (s, o) = if (zoom.value > 1.05f) 1f to Offset.Zero else DOUBLE_TAP_ZOOM to bound((at - center) * (1 - DOUBLE_TAP_ZOOM), DOUBLE_TAP_ZOOM)
                    scope.launch { zoom.animateTo(s, Motion.spatial()) }
                    scope.launch { pan.animateTo(o, Motion.spatial()) }
                })
            }
            .pointerInput(bitmap) {
                detectTransformGestures { centroid, move, change, _ ->
                    val was = zoom.value
                    val s = (was * change).coerceIn(1f, MAX_ZOOM)
                    val center = Offset(area.width / 2f, area.height / 2f)
                    val o = (centroid - center) * (1 - s / was) + pan.value * (s / was) + move
                    scope.launch {
                        zoom.snapTo(s)
                        pan.snapTo(bound(o, s))
                    }
                }
            },
    ) {
        Image(
            bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom.value
                scaleY = zoom.value
                translationX = pan.value.x
                translationY = pan.value.y
            },
        )
    }
}

/** A PDF's pages, one under another, each drawn at the screen's width as it scrolls into view. */
@Composable
private fun PdfPages(file: File) {
    val width = LocalWindowInfo.current.containerSize.width
    val pdf = remember(file) { runCatching { Pdf(PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))) }.getOrNull() }
    DisposableEffect(pdf) { onDispose { pdf?.close() } }
    if (pdf == null) return Other(file.name, file.length(), "this PDF can't be read")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(pdf.pages) { i ->
            val page by produceState<ImageBitmap?>(null, i) { value = withContext(Dispatchers.IO) { pdf.render(i, width) } }
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(page?.let { it.width.toFloat() / it.height } ?: A4)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White),
            ) {
                page?.let { Image(it, null, Modifier.fillMaxSize()) }
            }
        }
    }
}

/** A PDF's renderer, which draws one page at a time, and is closed only between them. */
private class Pdf(private val renderer: PdfRenderer) {
    val pages = renderer.pageCount
    private var closed = false

    fun render(i: Int, width: Int): ImageBitmap? = synchronized(this) {
        if (closed) return null
        runCatching {
            renderer.openPage(i).use { p ->
                val bitmap = Bitmap.createBitmap(width, max(1, width * p.height / p.width), Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.asImageBitmap()
            }
        }.getOrNull()
    }

    fun close() {
        Thread {
            synchronized(this) {
                closed = true
                renderer.close()
            }
        }.start()
    }
}

/** Text in the terminal's font, laid out a stretch at a time as it scrolls; markdown rendered. [footer] says what's left out. */
@Composable
private fun TextShown(text: String, markdown: Boolean, footer: String?) {
    val note = @Composable { f: String -> Text(f, color = Palette.dim, style = Type.caption, modifier = Modifier.padding(top = 16.dp)) }
    if (markdown) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            SelectionContainer { MarkdownText(text, breaks = false) } // a document's lines wrap
            footer?.let { note(it) }
        }
        return
    }
    val stretches = remember(text) { text.lines().chunked(LINES_AT_A_TIME).map { it.joinToString("\n") } }
    val style = TextStyle(color = Palette.fg, fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp)
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            items(stretches) { Text(it, style = style) }
            footer?.let { f -> item { note(f) } }
        }
    }
}

/** A picture decoded at about the size it can be seen at (turned as its camera meant), or no bigger than [MAX_SIDE]. */
private suspend fun picture(source: ImageDecoder.Source, screen: IntSize): Showing = withContext(Dispatchers.IO) {
    val most = min(MAX_SIDE, max(screen.width, screen.height) * 3)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val side = max(info.size.width, info.size.height)
        if (side > most) {
            val f = most.toFloat() / side
            decoder.setTargetSize(max(1, (info.size.width * f).toInt()), max(1, (info.size.height * f).toInt()))
        }
    }
    Showing.Picture(bitmap.asImageBitmap())
}

/** How much of a text file is fetched to show: its start, if it's longer. */
private const val TEXT_CAP = 256_000L
private const val LINES_AT_A_TIME = 80
private const val MAX_ZOOM = 8f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val MAX_SIDE = 4096
private const val A4 = 1f / 1.414f
