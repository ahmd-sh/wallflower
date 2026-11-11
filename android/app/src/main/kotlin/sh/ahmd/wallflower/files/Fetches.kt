package sh.ahmd.wallflower.files

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Files fetched from the Mac, kept in the app's cache, each in a folder of
 * its own under its own name, so a share names it right. One a listing
 * described (its size and when it changed) is fetched once while the app
 * runs; the rest, whenever they're shown. What's left from before goes
 * when the app starts, once whatever it was shared with has had time to
 * take it.
 */
class Fetches(private val dir: File) {
    private val kept = ConcurrentHashMap<String, File>()

    init {
        Thread {
            val old = System.currentTimeMillis() - KEPT_MS
            dir.listFiles()?.filter { it.lastModified() < old }?.forEach { it.deleteRecursively() }
        }.start()
    }

    /** Where a file of [name] goes: somewhere new, so it can't meet another. */
    fun place(name: String): File = File(File(dir, UUID.randomUUID().toString().take(12)), name.ifEmpty { "file" })

    /** The whole of the file at [path] as fetched before, if a listing said its [size] and [mtime] and it's still here. */
    fun kept(path: String, size: Long?, mtime: Long?): File? = key(path, size, mtime)?.let { kept[it] }?.takeIf { it.exists() }

    /** [file] is the whole of the file at [path], as a listing described it. */
    fun keep(path: String, size: Long?, mtime: Long?, file: File) {
        key(path, size, mtime)?.let { kept[it] = file }
    }

    private fun key(path: String, size: Long?, mtime: Long?) = if (size == null || mtime == null || mtime == 0L) null else "$path\u0000$size\u0000$mtime"

    companion object {
        private const val KEPT_MS = 60 * 60 * 1000L

        /** Offers [file] to another app through the system's share sheet. */
        fun share(context: Context, file: File) {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType(mimeOf(file.name))
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(file.name, uri) // a preview in the sheet
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }
}
