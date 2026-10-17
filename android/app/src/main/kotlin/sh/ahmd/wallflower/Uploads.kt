package sh.ahmd.wallflower

import android.content.ContentResolver
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
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

/**
 * Files on their way to the Mac, each for a pane. Once the gateway says
 * where it saved one, its path goes into that pane's draft at the cursor,
 * as a file dropped on a terminal does: from then on the draft is the
 * attachment, kept and sent like the rest of it.
 */
class Uploads(
    private val resolver: ContentResolver,
    private val prefs: SharedPreferences,
    private val drafts: Drafts,
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

    fun add(session: Session, pane: String, uris: List<Uri>) {
        for (uri in uris) {
            val (name, size) = describe(uri)
            val item = Item(UUID.randomUUID().toString().take(8), pane, uri, name, size)
            _items.update { it + item }
            scope.launch(Dispatchers.IO) {
                try {
                    val path = resolver.openInputStream(uri)!!.use { input ->
                        session.upload(item.id, name, size, input) { sent -> change(item.id) { it.copy(sent = sent) } }
                    }
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
    }
}
