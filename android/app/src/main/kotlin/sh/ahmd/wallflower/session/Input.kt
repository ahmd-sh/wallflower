package sh.ahmd.wallflower.session

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import sh.ahmd.wallflower.protocol.Keys
import sh.ahmd.wallflower.protocol.Submit

/** Composed text the gateway never confirmed, so it may not have reached the pane. */
data class Undelivered(val pane: String, val text: String)

/** Upload paths: under [dir], names of the characters the gateway keeps, which never end in . or -. */
fun uploadPaths(dir: String) = Regex(Regex.escape(dir) + "/[A-Za-z0-9._-]*[A-Za-z0-9_]")

/**
 * What the phone types into panes: keys, bytes, and composed text, which it
 * makes sure arrives, or comes back through [undelivered].
 */
internal class Input(private val link: Link) {
    private val _undelivered = MutableSharedFlow<Undelivered>(extraBufferCapacity = 16)
    val undelivered: SharedFlow<Undelivered> = _undelivered.asSharedFlow()

    private class Submitted(val ping: Int, val pane: String, val text: String)

    private val pending = ArrayList<Submitted>() // guarded by the link's lock

    /** Keys by wallflower's names (Enter, C-c); with [bindings], through the multiplexer's key bindings. */
    fun keys(pane: String, keys: List<String>, bindings: Boolean = false) = link.send(Keys(pane, keys, bindings))

    /** Bytes typed as they are, as a mouse wheel's. */
    fun bytes(pane: String, bytes: ByteArray) = link.sendData(pane, bytes)

    /**
     * Sends composed text in place of the app's input line, emptied first by
     * [clear] keys, then Enter, unless not [enter]; the gateway types it as
     * the app takes it best. Returns false, sending nothing, while not
     * connected. Text the gateway doesn't confirm before the connection drops
     * comes back through [undelivered].
     */
    fun submit(pane: String, text: String, enter: Boolean = true, clear: List<String> = emptyList()): Boolean = synchronized(link.lock) {
        if (!link.up || !link.send(Submit(pane, text, enter, clear))) return false
        pending += Submitted(link.ping(Link.PONG_TIMEOUT_MS), pane, text)
        true
    }

    /** Messages are handled in order: everything sent before ping [id] arrived. */
    fun arrived(id: Int) = synchronized(link.lock) { pending.removeAll { it.ping <= id } }

    /** The connection's gone, and with it whatever it didn't confirm. Under the link's lock. */
    fun down() {
        pending.forEach { _undelivered.tryEmit(Undelivered(it.pane, it.text)) }
        pending.clear()
    }
}
