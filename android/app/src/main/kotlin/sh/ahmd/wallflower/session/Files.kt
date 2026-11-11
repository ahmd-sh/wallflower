package sh.ahmd.wallflower.session

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.selects.select
import sh.ahmd.wallflower.protocol.Cancel
import sh.ahmd.wallflower.protocol.Fetch
import sh.ahmd.wallflower.protocol.FetchedFile
import sh.ahmd.wallflower.protocol.Got
import sh.ahmd.wallflower.protocol.ListDir
import sh.ahmd.wallflower.protocol.Listing

/**
 * The Mac's files, read-only, as the gateway shows them: directories listed,
 * and files fetched, written to the phone as their frames come, each said
 * to have arrived ([Got]) so the gateway sends the next. What the gateway
 * sends of a fetch arrives on the link's thread, in order.
 */
internal class Files(private val link: Link) {
    private val ids = AtomicInteger()
    private val listings = ConcurrentHashMap<String, CompletableDeferred<Listing>>()
    private val fetching = ConcurrentHashMap<String, Fetching>()

    /** A fetch on its way into [to], [progress] hearing how much of how much has come. */
    private class Fetching(val to: File, val progress: (Long, Long) -> Unit) {
        val done = CompletableDeferred<FetchedFile>()
        var out: OutputStream? = null
        var head: FetchedFile? = null
        var got = 0L

        @Synchronized
        fun finish(error: Throwable? = null) {
            runCatching { out?.close() }
            out = null
            if (error != null) done.completeExceptionally(error) else head?.let(done::complete)
        }
    }

    fun listing(m: Listing) {
        listings[m.id]?.complete(m)
    }

    fun fetched(m: FetchedFile) {
        val f = fetching[m.id] ?: return
        if (m.error != null) return f.finish(IOException(m.error))
        if (f.head != null) return
        synchronized(f) {
            f.head = m
            f.out = try {
                f.to.parentFile?.mkdirs()
                f.to.outputStream().buffered(1 shl 16)
            } catch (e: IOException) {
                return f.finish(e)
            }
        }
        f.progress(0, m.size)
        if (m.size == 0L) f.finish()
    }

    fun file(id: String, bytes: ByteArray, offset: Int, length: Int) {
        val f = fetching[id] ?: return
        val size = f.head?.size ?: return
        synchronized(f) {
            val out = f.out ?: return
            try {
                out.write(bytes, offset, length)
            } catch (e: IOException) {
                return f.finish(e)
            }
            f.got += length
        }
        link.send(Got(id, f.got))
        f.progress(f.got, size)
        if (f.got >= size) f.finish()
    }

    /** A directory's entries, or why it couldn't be read; throws if the connection drops. */
    suspend fun list(path: String): Listing {
        val ended = link.ended() ?: throw IOException("not connected")
        val id = "l${ids.incrementAndGet()}"
        val reply = CompletableDeferred<Listing>()
        listings[id] = reply
        try {
            if (!link.send(ListDir(id, path), on = ended)) throw IOException("the connection dropped")
            return reply.unlessLost(ended)
        } finally {
            listings.remove(id)
        }
    }

    /**
     * Fetches the file at [path] into [to] (only the first [limit] bytes, if
     * given) and returns what the gateway said of it: its whole path, and how
     * much came. However it ends short (cancelled, or the phone can't write
     * it), the gateway's told to stop sending it, as what it sends waits on
     * what's said to have come; and what came is removed.
     */
    suspend fun fetch(path: String, to: File, limit: Long? = null, progress: (Long, Long) -> Unit = { _, _ -> }): FetchedFile {
        val ended = link.ended() ?: throw IOException("not connected")
        val id = "f${ids.incrementAndGet()}"
        val f = Fetching(to, progress)
        fetching[id] = f
        var whole = false
        try {
            if (!link.send(Fetch(id, path, limit), on = ended)) throw IOException("the connection dropped")
            return f.done.unlessLost(ended).also { whole = true }
        } finally {
            fetching.remove(id)
            if (!whole) {
                link.send(Cancel(id), on = ended)
                f.finish(CancellationException())
                to.delete()
            }
        }
    }

    private suspend fun <T> Deferred<T>.unlessLost(ended: Deferred<Unit>): T = select {
        onAwait { it }
        ended.onAwait { throw IOException("the connection dropped") }
    }
}
