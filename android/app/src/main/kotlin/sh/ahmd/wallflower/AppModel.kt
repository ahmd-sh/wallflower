package sh.ahmd.wallflower

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
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
    val keyBar = KeyBarStore(prefs)
    val confirm = Confirmations(prefs)
    val appearance = Appearance(prefs)
    val conversationViews = ConversationViews(prefs)
    val uploads = Uploads(app.contentResolver, prefs, drafts, File(app.cacheDir, "thumbs"), viewModelScope)
    private var listening: Job? = null

    /** Edited messages on their way, by pane: rewinding (null), or why that failed. */
    val resending = mutableStateMapOf<String, String?>()

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
            session.uploadDir.filterNotNull().collect(uploads::savedIn)
        }
        _session.value = session
    }

    /**
     * Sends the message being edited in [pane]: the conversation goes back to
     * before it (the code too if [code]), the box's text goes as a message in
     * its place, and what the box held before comes back.
     */
    fun resend(pane: String, code: Boolean) {
        val session = _session.value ?: return
        val editing = drafts.editing[pane] ?: return
        if (pane in resending && resending[pane] == null) return
        val text = drafts.of(pane).text.toString()
        resending[pane] = null
        viewModelScope.launch {
            try {
                session.rewind(pane, editing.entry, code)
            } catch (e: IOException) {
                resending[pane] = e.message ?: "couldn't rewind"
                return@launch
            }
            resending.remove(pane)
            drafts.endEdit(pane)
            if (text.isNotBlank() && !session.submit(pane, text)) drafts.restore(pane, text)
        }
    }

    fun cancelEdit(pane: String) {
        drafts.endEdit(pane)
        resending.remove(pane)
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
