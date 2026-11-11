package sh.ahmd.wallflower

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import sh.ahmd.wallflower.files.Fetches
import sh.ahmd.wallflower.ui.files.Viewer
import sh.ahmd.wallflower.prefs.Preferences
import sh.ahmd.wallflower.session.HardwareKey
import sh.ahmd.wallflower.session.Host
import sh.ahmd.wallflower.session.Session
import sh.ahmd.wallflower.ui.composer.Drafts

/** What the app's screen works with; the connection itself outlives it ([Connection]). */
class AppModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("wallflower", Context.MODE_PRIVATE)
    private val connection = app.connection
    val key get() = connection.key
    val hostKeys get() = connection.hostKeys
    val drafts = Drafts(prefs, viewModelScope)
    val keyBar = KeyBarStore(prefs)
    val settings get() = connection.settings
    val uploads = Uploads(app.contentResolver, prefs, drafts, File(app.cacheDir, "thumbs"), viewModelScope)
    val fetches = Fetches(File(app.cacheDir, "files"))
    /** The one viewer, for pictures and the computer's files. */
    val viewer = Viewer()

    val host: StateFlow<Host?> get() = connection.host
    val session: StateFlow<Session?> get() = connection.session

    private val _hasKey = MutableStateFlow(key.exists)
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    private val _unlockSeconds = MutableStateFlow(key.unlockSeconds)
    /** How long an unlock lets the key sign, so the app can reconnect on its own while the user is away. */
    val unlockSeconds: StateFlow<Int?> = _unlockSeconds.asStateFlow()

    init {
        connection.ensure()
        viewModelScope.launch {
            connection.session.filterNotNull().collectLatest { session ->
                launch { session.undelivered.collect { drafts.restore(it.pane, it.text) } }
                launch { session.state.filterNotNull().collect { s -> drafts.keep(s.windows.flatMapTo(HashSet()) { w -> w.panes.map { it.id } }) } }
                session.uploadDir.filterNotNull().collect(uploads::savedIn)
            }
        }
    }

    /** A pane to show, from a notification tapped. */
    val opening = MutableStateFlow<String?>(null)

    fun configure(host: Host) = connection.configure(host)

    /** The app shows [pane]: what its program notified has been seen. */
    fun seen(pane: String) = connection.seen(pane)

    /** Ends the session: the app's done with the computer until it's opened again. */
    fun disconnect() = connection.disconnect()

    /** Creates the key if needed; returns why it couldn't, e.g. no screen lock. */
    fun createKey(): String? = if (key.exists) null else newKey(HardwareKey.AUTH_SECONDS)

    /** Makes a new key, usable for [seconds] after an unlock, in place of the old; it needs authorizing on the computer. */
    fun newKey(seconds: Int): String? = try {
        key.generate(seconds)
        _hasKey.value = true
        _unlockSeconds.value = key.unlockSeconds
        null
    } catch (e: IllegalStateException) {
        "Set a screen lock first: the key is guarded by your fingerprint or PIN. (${e.message})"
    }

    /** The command that authorizes this phone's key on the computer. */
    fun authorizeCommand(): String = "wallflower authorize '${key.authorizedKey(comment)}'"

    /** What this phone's key is called on the computer. */
    private val comment get() = "wallflower@" + Build.MODEL.replace(' ', '-')
}
