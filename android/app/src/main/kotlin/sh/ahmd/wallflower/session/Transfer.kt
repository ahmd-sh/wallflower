package sh.ahmd.wallflower.session

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.selects.select
import sh.ahmd.wallflower.protocol.Picture
import sh.ahmd.wallflower.protocol.Upload
import sh.ahmd.wallflower.protocol.Uploaded

/** Files sent to the computer, and pictures asked of it. */
internal class Transfer(private val link: Link) {
    private val uploaded = ConcurrentHashMap<String, CompletableDeferred<Uploaded>>()
    private val pictures = ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()

    fun uploaded(m: Uploaded) {
        uploaded[m.id]?.complete(m)
    }

    fun picture(id: String, bytes: ByteArray, offset: Int, length: Int) {
        pictures.remove(id)?.complete(if (length > 0) bytes.copyOfRange(offset, offset + length) else null)
    }

    /** A picture entry's bytes, asked of the gateway; null if it doesn't have them, or the connection drops. */
    suspend fun picture(pane: String, id: String): ByteArray? {
        val ended = link.ended() ?: return null
        val fresh = CompletableDeferred<ByteArray?>()
        val reply = pictures.putIfAbsent(id, fresh) ?: fresh.also {
            if (!link.send(Picture(pane, id), on = ended)) {
                pictures.remove(id, it)
                it.complete(null)
            }
        }
        return try {
            reply.unlessLost(ended)
        } catch (_: IOException) {
            pictures.remove(id, reply)
            null
        }
    }

    /**
     * Sends [size] bytes from [input] to the computer as upload [id] and returns
     * where the gateway saved it. It goes a chunk at a time, each followed by
     * a ping, with only [AHEAD] chunks unanswered: whatever's typed
     * meanwhile waits behind those alone rather than behind the whole file
     * in the network's buffers, and [progress] hears what has truly arrived.
     * Throws if the connection drops or the gateway won't save it.
     */
    suspend fun upload(id: String, name: String, size: Long, input: InputStream, progress: (Long) -> Unit): String {
        val ended = link.ended() ?: throw IOException("not connected")
        val reply = CompletableDeferred<Uploaded>()
        uploaded[id] = reply
        try {
            if (!link.send(Upload(id, name, size), on = ended)) throw IOException("the connection dropped")
            var sent = 0L
            val ahead = ArrayDeque<Pair<Long, CompletableDeferred<Unit>>>() // bytes sent through a chunk, and its arrival
            suspend fun settle(down: Int) {
                while (ahead.size > down) {
                    val (through, arrived) = ahead.removeFirst()
                    arrived.unlessLost(ended)
                    progress(through)
                }
            }
            while (sent < size) {
                val chunk = ByteArray(CHUNK)
                val n = input.read(chunk, 0, minOf(CHUNK.toLong(), size - sent).toInt())
                if (n < 0) throw IOException("$name ended early")
                if (!link.sendUpload(id, chunk, n, on = ended)) throw IOException("the connection dropped")
                sent += n
                ahead.addLast(sent to CompletableDeferred<Unit>().also { link.ping(Link.PONG_TIMEOUT_MS, it, on = ended) })
                settle(AHEAD)
            }
            settle(0)
            val r = reply.unlessLost(ended)
            return r.path ?: throw IOException(r.error ?: "not saved")
        } finally {
            uploaded.remove(id)
        }
    }

    private suspend fun <T> Deferred<T>.unlessLost(ended: Deferred<Unit>): T = select {
        onAwait { it }
        ended.onAwait { throw IOException("the connection dropped") }
    }

    companion object {
        const val CHUNK = 32 * 1024
        const val AHEAD = 8
    }
}
