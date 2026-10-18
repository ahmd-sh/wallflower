package sh.ahmd.wallflower.session

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import sh.ahmd.wallflower.protocol.Keys
import sh.ahmd.wallflower.protocol.Paste

/** Composed text the gateway never confirmed, so it may not have reached tmux. */
data class Undelivered(val pane: String, val text: String)

/** Upload paths: under [dir], names of the characters the gateway keeps, which never end in . or -. */
fun uploadPaths(dir: String) = Regex(Regex.escape(dir) + "/[A-Za-z0-9._-]*[A-Za-z0-9_]")

/** [text] as runs, each an upload's path or not. */
fun split(text: String, uploads: Regex): List<Pair<String, Boolean>> {
    val parts = ArrayList<Pair<String, Boolean>>()
    var at = 0
    for (m in uploads.findAll(text)) {
        if (m.range.first > at) parts += text.substring(at, m.range.first) to false
        parts += m.value to true
        at = m.range.last + 1
    }
    if (at < text.length) parts += text.substring(at) to false
    return parts
}

/**
 * What the phone types into panes: keys, bytes, and composed text, which it
 * makes sure arrives, or comes back through [undelivered].
 */
internal class Input(private val link: Link, private val uploadDir: () -> String?) {
    private val _undelivered = MutableSharedFlow<Undelivered>(extraBufferCapacity = 16)
    val undelivered: SharedFlow<Undelivered> = _undelivered.asSharedFlow()

    private class Submitted(val ping: Int, val pane: String, val text: String)

    private val pending = ArrayList<Submitted>() // guarded by the link's lock

    /** tmux key names; with [bindings], through tmux's key bindings. */
    fun keys(pane: String, keys: List<String>, bindings: Boolean = false) = link.send(Keys(pane, keys, bindings))

    /** Bytes typed as they are, as a mouse wheel's. */
    fun bytes(pane: String, bytes: ByteArray) = link.sendData(pane, bytes)

    /**
     * Sends composed text and presses Enter, unless not [enter]: typed if
     * it's one line, pasted if it has several, so the app doesn't run each
     * line, after [clear] keys (whatever empties the app's line first).
     * Returns false, sending nothing, while not connected. Text the gateway
     * doesn't confirm before the connection drops comes back through
     * [undelivered].
     */
    fun submit(pane: String, text: String, enter: Boolean = true, clear: List<String> = emptyList()): Boolean = synchronized(link.lock) {
        if (!link.up) return false
        if (clear.isNotEmpty()) link.send(Keys(pane, clear))
        val uploads = uploadDir()?.let(::uploadPaths)
        val sent = if (uploads != null && uploads.containsMatchIn(text)) {
            // Each upload is pasted on its own, as a file dropped on a terminal, so the app can take it in as one.
            split(text, uploads).all { (part, upload) ->
                if (upload || '\n' in part) link.send(Paste(pane, part)) else link.sendData(pane, part.encodeToByteArray())
            } && (!enter || link.send(Keys(pane, listOf("Enter"))))
        } else if ('\n' in text) {
            link.send(Paste(pane, text)) && (!enter || link.send(Keys(pane, listOf("Enter"))))
        } else {
            link.sendData(pane, (if (enter) text + "\r" else text).encodeToByteArray())
        }
        if (sent) pending += Submitted(link.ping(Link.PONG_TIMEOUT_MS), pane, text)
        sent
    }

    /** Messages are handled in order: everything sent before ping [id] arrived. */
    fun arrived(id: Int) = synchronized(link.lock) { pending.removeAll { it.ping <= id } }

    /** The connection's gone, and with it whatever it didn't confirm. Under the link's lock. */
    fun down() {
        pending.forEach { _undelivered.tryEmit(Undelivered(it.pane, it.text)) }
        pending.clear()
    }
}
