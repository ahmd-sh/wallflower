package sh.ahmd.wallflower

import android.content.ContentResolver
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Size
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.ahmd.wallflower.session.Session
import sh.ahmd.wallflower.ui.composer.Drafts

/**
 * Files on their way to the computer, each for a pane. Once the gateway says
 * where it saved one, its path goes into that pane's draft at the cursor,
 * as a file dropped on a terminal does: from then on the draft is the
 * attachment, kept and sent like the rest of it. An image leaves a small
 * preview behind in [thumbs], by its path, for the draft to show.
 */
class Uploads(
    private val resolver: ContentResolver,
    private val prefs: SharedPreferences,
    private val drafts: Drafts,
    private val thumbs: File,
    private val scope: CoroutineScope,
) {
    data class Item(val id: String, val pane: String, val uri: Uri, val name: String, val size: Long, val sent: Long = 0, val error: String? = null)

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    private val _dir = MutableStateFlow(prefs.getString(DIR, null))
    /** Where the gateway saves uploads, as it last said, so drafts can show theirs even offline. */
    val dir: StateFlow<String?> = _dir.asStateFlow()

    fun savedIn(dir: String) {
        if (dir == _dir.value) return
        _dir.value = dir
        prefs.edit().putString(DIR, dir).apply()
    }

    /** The preview kept for an uploaded image, by its path on the computer, if there is one. */
    fun thumbnail(path: String): File? = File(thumbs, key(path)).takeIf { it.exists() }

    fun add(session: Session, pane: String, uris: List<Uri>) {
        scope.launch(Dispatchers.IO) { prune() }
        for (uri in uris) {
            scope.launch(Dispatchers.IO) {
                val (name, size) = describe(uri) // a query, or reading it all where its size isn't said
                val item = Item(UUID.randomUUID().toString().take(8), pane, uri, name, size)
                _items.update { it + item }
                try {
                    val path = resolver.openInputStream(uri)!!.use { input ->
                        session.upload(item.id, name, size, input) { sent -> change(item.id) { it.copy(sent = sent) } }
                    }
                    keep(uri, path)
                    withContext(Dispatchers.Main) { drafts.insert(pane, path) }
                    dismiss(item.id)
                } catch (e: Exception) {
                    change(item.id) { it.copy(error = e.message ?: "couldn't send it") }
                }
            }
        }
    }

    fun dismiss(id: String) = _items.update { list -> list.filter { it.id != id } }

    private fun change(id: String, f: (Item) -> Item) = _items.update { list -> list.map { if (it.id == id) f(it) else it } }

    private fun keep(uri: Uri, path: String) {
        if (resolver.getType(uri)?.startsWith("image/") != true) return
        runCatching {
            val bitmap = resolver.loadThumbnail(uri, Size(THUMB, THUMB), null)
            thumbs.mkdirs()
            File(thumbs, key(path)).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
    }

    /** Previews go when their uploads do, on the computer. */
    private fun prune() {
        val old = System.currentTimeMillis() - KEPT_MS
        thumbs.listFiles()?.filter { it.lastModified() < old }?.forEach { it.delete() }
    }

    private fun key(path: String) = MessageDigest.getInstance("SHA-1").digest(path.encodeToByteArray()).joinToString("") { "%02x".format(it) } + ".jpg"

    private fun describe(uri: Uri): Pair<String, Long> {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: uri.lastPathSegment ?: "upload"
                val size = if (c.isNull(1)) -1 else c.getLong(1)
                if (size >= 0) return name to size
            }
        }
        // Without a size up front, counting it is the only way to say it.
        val size = resolver.openInputStream(uri)?.use { s -> generateSequence { s.skip(1 shl 20).takeIf { it > 0 } }.sum() } ?: 0L
        return (uri.lastPathSegment ?: "upload") to size
    }

    private companion object {
        const val DIR = "uploads"
        const val THUMB = 192
        const val KEPT_MS = 14L * 24 * 60 * 60 * 1000
    }
}
