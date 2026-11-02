package sh.ahmd.wallflower.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import sh.ahmd.wallflower.connection

/** A notification's button pressed (with the phone unlocked): answers what its pane asks. */
class AnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pane = intent.getStringExtra(Notices.EXTRA_PANE) ?: return
        val option = intent.getStringExtra(Notices.EXTRA_OPTION) ?: return
        val done = goAsync()
        context.connection.answer(pane, option) { done.finish() }
    }
}
