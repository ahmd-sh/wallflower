package sh.ahmd.wallflower.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import sh.ahmd.wallflower.MainActivity
import sh.ahmd.wallflower.R
import sh.ahmd.wallflower.protocol.State
import sh.ahmd.wallflower.screen.Prompt
import sh.ahmd.wallflower.session.Blocked
import sh.ahmd.wallflower.session.Host
import sh.ahmd.wallflower.session.Status
import sh.ahmd.wallflower.protocol.Notification as Notified

/**
 * What wallflower puts in the phone's notifications: what the Mac's
 * programs notify, the latest per pane, with what the pane asks and buttons
 * answering it where its program can tell; and the connection, kept while
 * the user is away or lost. Every one can be swiped away; on the lock
 * screen, what the programs said stays hidden, and buttons need the phone
 * unlocked.
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

    /**
     * What [n]'s program said, in place of what it said before; with what
     * its pane asks ([prompt]), [answers] to it as buttons, and a [note].
     * Only an [alert] buzzes, not what fills one in.
     */
    fun show(n: Notified, state: State?, prompt: Prompt? = null, answers: List<Prompt.Option> = emptyList(), note: String? = null, alert: Boolean = true) {
        val window = state?.windows?.firstOrNull { w -> w.panes.any { it.id == n.pane } }
        val program = window?.panes?.firstOrNull { it.id == n.pane }?.command?.takeIf { it.isNotEmpty() }
        val where = window?.let { "${it.index} ${it.name}" }
        val hidden = Notification.Builder(context, MAC)
            .setSmallIcon(R.drawable.ic_wallflower)
            .setContentTitle(program ?: "wallflower")
            .setContentText(where ?: "on your Mac")
            .build()
        val text = listOfNotNull(n.body, prompt?.lines?.joinToString("\n") { it.text }, note).joinToString("\n\n")
        val notification = Notification.Builder(context, MAC)
            .setSmallIcon(R.drawable.ic_wallflower)
            .setContentTitle(n.title ?: program ?: "your Mac")
            .setContentText(note ?: n.body)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSubText(where)
            .setContentIntent(open(n.pane))
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alert)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(hidden)
        answers.forEachIndexed { i, option ->
            val answer = PendingIntent.getBroadcast(
                context,
                "${n.pane}/$i".hashCode(),
                Intent(context, AnswerReceiver::class.java)
                    .setAction("answer ${n.pane} $i")
                    .putExtra(EXTRA_PANE, n.pane)
                    .putExtra(EXTRA_OPTION, option.label),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            notification.addAction(Notification.Action.Builder(null, option.label, answer).setAuthenticationRequired(true).build())
        }
        manager.notify(n.pane, FROM_MAC, notification.build())
    }

    /** Whether what [pane]'s program said is still in the notifications. */
    fun showing(pane: String) = manager.activeNotifications.any { it.id == FROM_MAC && it.tag == pane }

    /** A button pressed that couldn't answer, the app not connected. */
    fun unanswered(pane: String) {
        val notification = Notification.Builder(context, MAC)
            .setSmallIcon(R.drawable.ic_wallflower)
            .setContentTitle("couldn't answer")
            .setContentText("Not connected to your Mac: tap to open wallflower.")
            .setContentIntent(open(pane))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(pane, FROM_MAC, notification)
    }

    /** What [pane]'s program said has been seen. */
    fun cancel(pane: String) = manager.cancel(pane, FROM_MAC)

    /** The service's, keeping the connection to [host] while the user is away. */
    fun connected(host: Host?, status: Status?): Notification =
        Notification.Builder(context, CONNECTION)
            .setSmallIcon(R.drawable.ic_wallflower)
            .setContentTitle(
                when (status) {
                    is Status.Connected -> "connected to your Mac"
                    is Status.Disconnected -> "reconnecting to your Mac"
                    else -> "connecting to your Mac"
                },
            )
            .setContentText(host?.let { if (it.dev) "development transport" else "${it.user}@${it.address}" })
            .setContentIntent(open(null))
            .addAction(Notification.Action.Builder(null, "disconnect", ConnectionService.disconnect(context)).build())
            .setShowWhen(false)
            .build()

    /** The connection was lost, and can't come back without the user ([why]); null: it's back. */
    fun lost(why: Status.NeedsUser?) {
        if (why == null) {
            manager.cancel(LOST)
            return
        }
        val notification = Notification.Builder(context, CONNECTION)
            .setSmallIcon(R.drawable.ic_wallflower)
            .setContentTitle("lost the connection to your Mac")
            .setContentText(
                when (why.blocked) {
                    is Blocked.KeyLocked -> "Tap to reconnect: the key needs your fingerprint or screen lock."
                    else -> "Tap to see why: ${why.blocked.message}"
                },
            )
            .setContentIntent(open(null))
            .setAutoCancel(true)
            .build()
        manager.notify(LOST, notification)
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
        const val EXTRA_OPTION = "option"
        const val CONNECTED = 1
        private const val LOST = 2
        private const val FROM_MAC = 3
        private const val MAC = "mac"
        private const val CONNECTION = "connection"
    }
}
