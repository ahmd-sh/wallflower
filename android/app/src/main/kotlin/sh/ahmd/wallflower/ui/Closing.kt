package sh.ahmd.wallflower.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import sh.ahmd.wallflower.prefs.Preferences
import sh.ahmd.wallflower.protocol.Pane
import sh.ahmd.wallflower.protocol.Window
import sh.ahmd.wallflower.session.Session

/**
 * Closing panes and windows. It asks first, unless "don't ask again" said
 * not to (settings bring the question back); a window's last pane closes
 * the window.
 */
@Stable
internal class Closing(private val session: Session, private val settings: Preferences, private val haptics: Haptics) {
    /** What it's asking about closing. */
    var asking by mutableStateOf<Pair<Window, Pane?>?>(null)
        private set

    fun close(window: Window, pane: Pane?) {
        val ask = if (whole(window, pane)) settings.askClosingWindow.value else settings.askClosingPane.value
        if (ask) asking = window to pane else kill(window, pane)
    }

    fun confirm(dontAskAgain: Boolean) {
        val (w, p) = asking ?: return
        val whole = whole(w, p)
        if (dontAskAgain) (if (whole) settings.askClosingWindow else settings.askClosingPane).set(false)
        kill(w, if (whole) null else p)
        asking = null
    }

    fun dismiss() {
        asking = null
    }

    private fun kill(window: Window, pane: Pane?) {
        haptics.confirm()
        if (pane != null) session.kill(pane) else session.kill(window)
    }
}

private fun whole(window: Window, pane: Pane?) = pane == null || window.panes.size == 1

/** The question [closing] asks, if it's asking; the last of [windows] ends the session. */
@Composable
internal fun CloseDialog(closing: Closing, windows: List<Window>) {
    val (w, p) = closing.asking ?: return
    if (whole(w, p)) {
        ConfirmClose(
            title = "close ${w.name}?",
            body = (if (w.panes.size == 1) "Its pane, and whatever runs in it," else "Its ${w.panes.size} panes, and whatever runs in them,") +
                " will be killed." + if (windows.size == 1) " It's the last tab, so the session ends too." else "",
            action = "close tab",
            onConfirm = closing::confirm,
            onDismiss = closing::dismiss,
        )
    } else if (p != null) {
        ConfirmClose(
            title = "close pane ${p.index}?",
            body = "${p.command} in pane ${p.index} will be killed.",
            action = "close pane",
            onConfirm = closing::confirm,
            onDismiss = closing::dismiss,
        )
    }
}
