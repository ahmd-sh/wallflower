package sh.ahmd.wallflower.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pictures in mobile views, decoded off the main thread at about the size they
 * show, rather than the size they were sent at, and kept a while by entry,
 * so scrolling back to one doesn't decode it again.
 */
internal object Pictures {
    private val kept = object : LruCache<String, ImageBitmap>(KEPT_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun kept(id: String): ImageBitmap? = kept.get(id)

    /** [bytes] as a picture that fills [width] by [height] pixels, or as near as halving it allows. */
    suspend fun decode(id: String, bytes: ByteArray, width: Int, height: Int): ImageBitmap? = kept(id) ?: withContext(Dispatchers.Default) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()
    }?.also { kept.put(id, it) }

    private const val KEPT_BYTES = 24 shl 20
}
