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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import sh.ahmd.wallflower.prefs.Preferences
import sh.ahmd.wallflower.read.Prompts
import sh.ahmd.wallflower.ui.composer.Drafts
import sh.ahmd.wallflower.session.HardwareKey
import sh.ahmd.wallflower.session.Host
import sh.ahmd.wallflower.session.HostKeys
import sh.ahmd.wallflower.session.Session
import sh.ahmd.wallflower.session.SshTransport
import sh.ahmd.wallflower.session.TcpTransport

class AppModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("wallflower", Context.MODE_PRIVATE)
    val key = HardwareKey()
    val hostKeys = HostKeys(prefs)
    val drafts = Drafts(prefs, viewModelScope)
    val prompts = Prompts(prefs)
    val keyBar = KeyBarStore(prefs)
    val settings = Preferences(prefs)
    val uploads = Uploads(app.contentResolver, prefs, drafts, File(app.cacheDir, "thumbs"), viewModelScope)
    private var listening: Job? = null

    private val _host = MutableStateFlow(Host.load(prefs))
    val host: StateFlow<Host?> = _host.asStateFlow()

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _hasKey = MutableStateFlow(key.exists)
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    init {
        _host.value?.let(::connect)
    }

    fun configure(host: Host) {
        host.save(prefs)
        _host.value = host
        connect(host)
    }

    private fun connect(host: Host) {
        _session.value?.stop()
        listening?.cancel()
        val transport = if (host.dev) TcpTransport() else SshTransport(host, key, hostKeys)
        val session = Session(transport, viewModelScope)
        listening = viewModelScope.launch {
            launch { session.undelivered.collect { drafts.restore(it.pane, it.text) } }
            launch { session.state.filterNotNull().collect { s -> drafts.keep(s.windows.flatMapTo(HashSet()) { w -> w.panes.map { it.id } }) } }
            session.uploadDir.filterNotNull().collect(uploads::savedIn)
        }
        _session.value = session
    }

    fun onForeground() {
        _session.value?.probe()
    }

    /** Creates the key if needed; returns why it couldn't, e.g. no screen lock. */
    fun createKey(): String? = try {
        if (!key.exists) key.generate()
        _hasKey.value = true
        null
    } catch (e: IllegalStateException) {
        "Set a screen lock first: the key is guarded by your fingerprint or PIN. (${e.message})"
    }

    /** The command that authorizes this phone's key on the Mac. */
    fun authorizeCommand(): String =
        "~/go/bin/wallflower authorize '${key.authorizedKey("wallflower@" + Build.MODEL.replace(' ', '-'))}'"

    override fun onCleared() {
        _session.value?.stop()
    }
}
