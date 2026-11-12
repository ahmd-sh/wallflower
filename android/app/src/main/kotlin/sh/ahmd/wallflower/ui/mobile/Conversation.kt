package sh.ahmd.wallflower.ui.mobile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sh.ahmd.wallflower.program.Aside
import sh.ahmd.wallflower.program.Asked
import sh.ahmd.wallflower.read.Entry
import sh.ahmd.wallflower.read.Item
import sh.ahmd.wallflower.read.Party
import sh.ahmd.wallflower.read.Reading
import sh.ahmd.wallflower.read.items
import sh.ahmd.wallflower.screen.Prompt
import sh.ahmd.wallflower.ui.Motion
import sh.ahmd.wallflower.ui.Palette
import sh.ahmd.wallflower.ui.PromptCard
import sh.ahmd.wallflower.ui.Type

/**
 * The conversation, newest at the foot, in [list], which follows the newest
 * as [MobileView] says; scrolled [away] from it, a way back.
 */
@Composable
internal fun Conversation(
    items: List<Item>,
    reading: Reading,
    status: Status?,
    asking: Prompt?,
    since: Long,
    doing: String?,
    asked: Asked?,
    nudged: Long,
    aside: Aside?,
    answer: List<String>,
    footer: (@Composable () -> Unit)?,
    picture: suspend (String) -> ByteArray?,
    onKeys: (List<String>) -> Unit,
    onPicked: (Prompt, Prompt.Option) -> Unit,
    onEdit: ((Entry.Said) -> Unit)?,
    feel: Fold,
    feelAside: Fold,
    list: LazyListState,
    away: Boolean,
) = Box(Modifier.fillMaxSize()) {
    val scope = rememberCoroutineScope()
    LazyColumn(
        Modifier.fillMaxSize(),
        state = list,
        reverseLayout = true,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 48.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom), // a short conversation sits at the foot, by the composer
    ) {
        // What's aside of the program's line takes its place, at the foot.
        if (aside != null && aside !is Aside.Tasks) {
            item(key = "aside") {
                AnimatedContent(
                    aside,
                    Modifier.animateItem(fadeInSpec = Motion.effect(), placementSpec = Motion.spatial(), fadeOutSpec = Motion.effect()),
                    transitionSpec = { fadeIn(Motion.effect()) togetherWith fadeOut(Motion.effect()) using SizeTransform(clip = false) },
                    contentKey = { it::class },
                    label = "aside",
                ) { a ->
                    AsideCard(a, answer, nudged, onKeys, Modifier.onSizeChanged { s -> feelAside.laidOut(s.height) })
                }
            }
        }
        val newest = items.lastOrNull()
        val besideCopy = newest?.copies(ongoing = status != null) == true
        if (footer != null && !besideCopy) {
            item(key = "context") {
                Box(
                    Modifier.fillMaxWidth().animateItem(fadeInSpec = Motion.effect(), placementSpec = Motion.spatial(), fadeOutSpec = Motion.effect()),
                    contentAlignment = Alignment.CenterEnd,
                ) { footer() }
            }
        }
        // What waits for the program to take it is below its working, as Claude shows it.
        reading.queued.asReversed().forEachIndexed { i, text ->
            item(key = "queued $i $text") {
                Box(Modifier.animateItem(fadeInSpec = Motion.effect(), placementSpec = Motion.spatial(), fadeOutSpec = Motion.effect())) { Queued(text) }
            }
        }
        if (status != null) {
            item(key = "status") {
                // One item that changes between them, so a menu that flickers off and on doesn't leave and come back.
                var shown by remember { mutableStateOf(asking) }
                if (asking != null) shown = asking
                AnimatedContent(
                    status,
                    Modifier.animateItem(fadeInSpec = Motion.effect(), placementSpec = Motion.spatial(), fadeOutSpec = Motion.effect()),
                    transitionSpec = { fadeIn(Motion.effect()) togetherWith fadeOut(Motion.effect()) using SizeTransform(clip = false) },
                    label = "status",
                ) { now ->
                    when (now) {
                        Status.Asking -> shown?.let { p ->
                            PromptCard(p, onKeys, Modifier.onSizeChanged { s -> feel.laidOut(s.height) }, nudged.takeIf { aside == null } ?: 0) { picked -> onPicked(p, picked) }
                        }
                        Status.Working -> Working(since, doing)
                        Status.Waiting -> Waiting(asked) { asked?.let { onKeys(it.keys) } }
                        Status.Seeking -> Working(0, "finding your message")
                    }
                }
            }
        }
        items(items.asReversed(), key = { it.key }, contentType = { it::class }) {
            Box(Modifier.animateItem(fadeInSpec = Motion.effect(), placementSpec = Motion.spatial(), fadeOutSpec = Motion.effect())) {
                ItemView(it, picture, ongoing = status != null && it === newest, beside = footer.takeIf { _ -> it === newest }, onEdit = onEdit)
            }
        }
        if (reading.more) item(key = "more") { Note("earlier messages are in the terminal") }
    }
    AnimatedVisibility(
        visible = away,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        enter = fadeIn(Motion.effect()) + slideInVertically(Motion.spatial()) { it },
        exit = fadeOut(Motion.effect()) + slideOutVertically(Motion.spatial()) { it },
    ) {
        Text(
            "↓ latest",
            color = Palette.fg,
            style = Type.label,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Palette.bg2)
                .clickable { scope.launch { list.animateScrollToItem(0) } }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** Whether [this] ends with a copy button: the reply that ends a turn, once the turn is done. */
private fun Item.copies(ongoing: Boolean) = this is Item.Said && last && !ongoing

/** [ongoing]: the newest item of a turn still going, whose reply isn't the last word yet. [beside]: on its copy row's right. */
@Composable
private fun ItemView(
    item: Item,
    picture: suspend (String) -> ByteArray?,
    ongoing: Boolean,
    beside: (@Composable () -> Unit)? = null,
    onEdit: ((Entry.Said) -> Unit)? = null,
) {
    when (item) {
        is Item.Steps -> Steps(item.entries)
        is Item.Said -> when (val e = item.entry) {
            is Entry.Picture -> Picture(e, picture)
            is Entry.Note -> Note(e.text, e.detail)
            is Entry.Agent -> AgentMessage(e)
            is Entry.Command -> Command(e)
            is Entry.Said -> if (e.by == Party.User) UserPrompt(e.text, onEdit?.let { { it(e) } }) else Reply(e.text, copyable = item.copies(ongoing), beside)
            is Entry.Step, is Entry.Thought -> Unit // folded into steps
        }
    }
}
