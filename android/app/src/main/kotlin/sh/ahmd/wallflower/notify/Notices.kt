package sh.ahmd.wallflower.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import sh.ahmd.wallflower.MainActivity
import sh.ahmd.wallflower.R
import sh.ahmd.wallflower.program.Notice
import sh.ahmd.wallflower.program.Program
import sh.ahmd.wallflower.protocol.State
import sh.ahmd.wallflower.session.Blocked
import sh.ahmd.wallflower.session.Host
import sh.ahmd.wallflower.session.Status
import sh.ahmd.wallflower.session.called

/**
 * What wallflower puts in the phone's notifications: what the Mac's
 * programs notify, the latest per pane, as their [Program] has it said; and
 * the connection, kept while the user is away or lost. Every one can be
 * swiped away; on the lock screen, what the programs said stays hidden.
 */
class Notices(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(MAC, "From your Mac", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "What programs on the Mac notify, as they would on its desktop"
                },
                NotificationChannel(CONNECTION, "Connection", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Connected to the Mac while you're away, or lost"
                },
            ),
        )
    }

    /** What [pane]'s program said ([notice]), in place of what it said before. */
    fun show(pane: String, notice: Notice, state: State?) {
        val window = state?.windows?.firstOrNull { w -> w.panes.any { it.id == pane } }
        val program = window?.panes?.firstOrNull { it.id == pane }?.command?.takeIf { it.isNotEmpty() }
        val where = window?.let { "${it.index} ${it.name}" }
        val hidden = Notification.Builder(context, MAC)
            .setSmallIcon(R.drawable.ic_wallflower)
            .setContentTitle(program ?: "wallflower")
            .setContentText(where ?: "on your Mac")
            .build()
        val notification = Notification.Builder(context, MAC)
            .setSmallIcon(R.drawable.ic_wallflower)
            .setContentTitle(notice.title ?: program ?: "your Mac")
            .setContentText(notice.body)
            .setStyle(Notification.BigTextStyle().bigText(notice.body))
            .setSubText(where)
            .setContentIntent(open(pane))
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(hidden)
            .build()
        manager.notify(pane, FROM_MAC, notification)
    }

    /** What [pane]'s program said has been seen. */
    fun cancel(pane: String) = manager.cancel(pane, FROM_MAC)

    /** The service's, keeping the connection to [host] while the user is away. */
    fun connected(host: Host?, status: Status?): Notification {
        val mac = host.called
        val builder = Notification.Builder(context, CONNECTION)
            .setSmallIcon(R.drawable.ic_wallflower)
            .setContentIntent(open(null))
            .addAction(Notification.Action.Builder(null, "Disconnect", ConnectionService.disconnect(context)).build())
            .setShowWhen(false)
        when (status) {
            is Status.Connected -> builder.setContentTitle("Connected to $mac")
            is Status.Disconnected -> builder.setContentTitle("Reconnecting to $mac…")
            is Status.NeedsUser -> builder.setContentTitle("Lost the connection to $mac").setContentText(what(status.blocked, mac))
            else -> builder.setContentTitle("Connecting to $mac…")
        }
        return builder.build()
    }

    /** The connection to [host] was lost, and can't come back without the user: [why]. */
    fun lost(host: Host?, why: Blocked) {
        val mac = host.called
        val notification = Notification.Builder(context, CONNECTION)
            .setSmallIcon(R.drawable.ic_wallflower)
            .setContentTitle("Lost the connection to $mac")
            .setContentText(what(why, mac))
            .setContentIntent(open(null))
            .setAutoCancel(true)
            .build()
        manager.notify(LOST, notification)
    }

    /** The connection's back, or the user is: what said it was lost goes. */
    fun back() = manager.cancel(LOST)

    /** What the user does about [blocked], in a line. */
    private fun what(blocked: Blocked, mac: String) = when (blocked) {
        is Blocked.KeyLocked -> "Tap to unlock and reconnect."
        is Blocked.UnknownHost -> "Tap to confirm it's really $mac."
        is Blocked.HostKeyChanged -> "Its identity changed. Tap to check before reconnecting."
        is Blocked.NotAuthorized -> "It doesn't let this phone in any more. Tap to see what to do."
        is Blocked.Refused -> "Tap to see why."
    }

    fun clear() = manager.cancelAll()

    /** Opens the app, on [pane] if one's given. */
    private fun open(pane: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        pane?.let { intent.putExtra(EXTRA_PANE, it) }
        return PendingIntent.getActivity(context, pane.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val EXTRA_PANE = "pane"
        const val CONNECTED = 1
        private const val LOST = 2
        private const val FROM_MAC = 3
        private const val MAC = "mac"
        private const val CONNECTION = "connection"
    }
}
