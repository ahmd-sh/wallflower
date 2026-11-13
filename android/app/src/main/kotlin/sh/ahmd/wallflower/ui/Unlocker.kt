package sh.ahmd.wallflower.ui

/**
 * Asks the user to unlock the phone's key ([prompt]: fingerprint or screen
 * lock), one ask at a time, and not at all while the key doesn't need it
 * ([needed]: unlocking the phone counts). Asked by itself ([ask]), as the key
 * locks or the app comes back to it, it asks once a visit: turned down, it
 * waits for the app's next time in front ([left] says it went) or a tap on
 * unlock ([unlock]). On the main thread.
 */
class Unlocker(private val needed: () -> Boolean, private val prompt: (onDone: (Boolean) -> Unit) -> Unit) {
    private var asking = false
    private var declined = false

    /** By itself: unless it was turned down this visit. */
    fun ask(onDone: (Boolean) -> Unit) {
        if (!declined) unlock(onDone)
    }

    /** A tap on unlock. */
    fun unlock(onDone: (Boolean) -> Unit) {
        if (asking) return
        if (!needed()) return onDone(true)
        asking = true
        prompt { ok ->
            asking = false
            declined = !ok
            onDone(ok)
        }
    }

    /** The app went to the background: back in front, it asks again. */
    fun left() {
        declined = false
    }
}
