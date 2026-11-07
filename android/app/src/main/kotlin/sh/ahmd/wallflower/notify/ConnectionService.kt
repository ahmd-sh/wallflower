package sh.ahmd.wallflower.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import sh.ahmd.wallflower.connection
import sh.ahmd.wallflower.session.Host
import sh.ahmd.wallflower.session.Status

/**
 * Keeps the app's process, and so its connection, while the user is away:
 * Android freezes an app soon after it leaves the screen otherwise. Its
 * notification says how the connection is; it stops with the session.
 */
class ConnectionService : Service() {
    private val scope = MainScope()

    override fun onBind(intent: Intent?) = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        running = true
        val connection = connection
        scope.launch {
            val status = connection.session.flatMapLatest { s -> s?.status ?: flowOf(null) }
            val work = combine(connection.session.flatMapLatest { s -> s?.state ?: flowOf(null) }, connection.settings.statusBar.flow) { state, on ->
                if (on) atWork(state) else null
            }.distinctUntilChanged()
            combine(connection.session, status, connection.host, work) { s, st, h, w -> Shown(s != null, st, h, w) }.collect { shown ->
                if (!shown.session) {
                    stopSelf()
                } else {
                    // Swiped away, it stays away.
                    val manager = getSystemService(NotificationManager::class.java)
                    if (manager.activeNotifications.any { it.id == Notices.CONNECTED }) {
                        manager.notify(Notices.CONNECTED, connection.notices.connected(shown.host, shown.status, shown.work))
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DISCONNECT) {
            connection.disconnect()
            return START_NOT_STICKY
        }
        foreground()
        return START_NOT_STICKY
    }

    private fun foreground() {
        val c = connection
        startForeground(Notices.CONNECTED, c.notices.connected(c.host.value, c.session.value?.status?.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private data class Shown(val session: Boolean, val status: Status?, val host: Host?, val work: AtWork?)

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val DISCONNECT = "disconnect"
        @Volatile private var running = false

        /** Call while the app is on screen: Android doesn't let one start from the background. */
        fun start(context: Context) {
            if (running) return
            context.startForegroundService(Intent(context, ConnectionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }

        /** Ends the session, from the service's notification. */
        fun disconnect(context: Context): PendingIntent =
            PendingIntent.getService(context, 0, Intent(context, ConnectionService::class.java).setAction(DISCONNECT), PendingIntent.FLAG_IMMUTABLE)
    }
}
