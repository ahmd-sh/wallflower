package sh.ahmd.wallflower.ui.mobile

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import sh.ahmd.wallflower.ui.Haptics
import sh.ahmd.wallflower.ui.Motion
import sh.ahmd.wallflower.ui.copy
import sh.ahmd.wallflower.ui.haptics

/** Whether the conversation is on screen, so its working marks move. */
internal val LocalLive = compositionLocalOf { true }

/** What was left out, as "… 12 more lines"; null if nothing was. */
internal fun more(n: Int, thing: String) = if (n > 0) "… %,d more %s%s".format(n, thing, if (n == 1) "" else "s") else null

/**
 * Feels a fold opening or shutting: after a [tap], its next change of height
 * plays a slide lasting as long as the spring takes to move that far.
 */
internal class Fold(private val haptics: Haptics) {
    private var tapped = false
    private var height = 0
    private var open: Boolean? = null

    fun tap() {
        tapped = true
    }

    /** Its content goes as it shuts, all of it at once (it animates out after). */
    fun leave() {
        if (tapped) feel(height)
        tapped = false
        height = 0
    }

    /** Whether it's open now, for a fold that opens by itself: a change is felt as if tapped. */
    fun shown(open: Boolean) {
        val was = this.open
        this.open = open
        if (was == null || was == open) return
        tap()
        if (!open) leave()
    }

    /** Its content's height, laid out at the size it's animating to. */
    fun laidOut(h: Int) {
        val moved = kotlin.math.abs(h - height)
        height = h
        if (tapped && moved > 0) feel(moved)
    }

    private fun feel(px: Int) {
        tapped = false
        haptics.slide(Motion.settleMillis(px.toFloat()))
    }
}

@Composable
internal fun rememberFold(): Fold {
    val haptics = haptics()
    return remember(haptics) { Fold(haptics) }
}

/** Puts text on the clipboard, felt, and says it [copied] for a moment after. */
internal class Copier(private val context: Context, private val haptics: Haptics) {
    var copied by mutableStateOf(false)

    fun copy(text: String) {
        copy(context, text)
        haptics.confirm()
        copied = true
    }
}

@Composable
internal fun rememberCopier(): Copier {
    val context = LocalContext.current
    val haptics = haptics()
    val copier = remember(haptics) { Copier(context, haptics) }
    LaunchedEffect(copier.copied) {
        if (copier.copied) {
            delay(1_500)
            copier.copied = false
        }
    }
    return copier
}
