package sh.ahmd.wallflower.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import sh.ahmd.wallflower.program.Aside

/**
 * The program's own list of its tasks, in a mobile view's place while it
 * shows it: its filters to pick from, its tasks in their groups to open
 * one, the one under its cursor lit, and a way back and to a new one. What's
 * out of sight above or below is a page away. Something sent while it's up
 * waits, as what's typed would go to it: it says so a moment ([nudged]). Each is keys to the program
 * ([onKeys]), which moves its own cursor; what it shows next says where it went.
 */
@Composable
internal fun TasksPage(tasks: Aside.Tasks, onKeys: (List<String>) -> Unit, nudged: Long, modifier: Modifier = Modifier) {
    val haptics = haptics()
    // Something sent while it's up waits: it says so a moment.
    val before = remember { nudged }
    val held by produceState(false, nudged) {
        if (nudged != 0L && nudged != before) {
            value = true
            delay(HELD_MS)
            value = false
        }
    }
    val send = { keys: List<String> ->
        if (keys.isNotEmpty()) {
            haptics.tick()
            onKeys(keys)
        }
    }
    Column(modifier.fillMaxSize().background(Palette.bg0)) {
        Text(tasks.named, color = Palette.fg, style = Type.title, modifier = Modifier.padding(start = 20.dp, top = 14.dp, end = 120.dp)) // clear of the pane's controls
        AnimatedVisibility(held) { Text("go back first, then send", color = Palette.orange, style = Type.caption, modifier = Modifier.padding(start = 20.dp, top = 2.dp)) }
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tasks.filters.forEachIndexed { i, f ->
                Text(
                    "${f.name.lowercase()} ${f.count}",
                    color = if (f.current) Palette.fg else Palette.grey,
                    style = Type.label,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (f.current) Palette.bg3 else Palette.bg1)
                        .clickable { send(tasks.showing(i)) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (tasks.above) item(key = "above") { More("more above") { send(tasks.pageUp) } }
            tasks.says?.let { says -> item(key = "says") { Text(says.lowercase(), color = Palette.grey, style = Type.body, modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)) } }
            var at = 0 // the first task of each group, of all of them
            tasks.groups.forEachIndexed { g, group ->
                if (group.name.isNotEmpty()) {
                    item(key = "group $g ${group.name}") {
                        Text(
                            if (group.count.isEmpty()) group.name else "${group.name} · ${group.count}",
                            color = Palette.dim,
                            style = Type.caption,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = if (g == 0) 4.dp else 14.dp, bottom = 4.dp),
                        )
                    }
                }
                val first = at
                itemsIndexed(group.tasks) { i, task -> TaskRow(task) { send(tasks.opening(first + i)) } }
                at += group.tasks.size
            }
            if (tasks.below) item(key = "below") { More("more below") { send(tasks.pageDown) } }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action("back", Modifier.weight(1f)) { send(tasks.leave) }
            Action("new task", Modifier.weight(1f)) { send(tasks.new) }
        }
    }
}

/** A task: its mark, then its name; lit under the program's cursor. */
@Composable
private fun TaskRow(task: Aside.Tasks.Task, onOpen: () -> Unit) = Row(
    Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(if (task.current) Palette.bg2 else Palette.bg0)
        .clickable(onClick = onOpen)
        .padding(horizontal = 10.dp, vertical = 11.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
) {
    Mark(task.mark)
    Text(task.name, color = Palette.fg, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** What a task's mark says, in the app's colors: needing the user orange, working purple, something new blue; either of those last, the words' color; nothing, a ring. */
@Composable
private fun Mark(mark: Aside.Tasks.Mark) {
    val dot = Modifier.size(8.dp).clip(CircleShape)
    when (mark) {
        Aside.Tasks.Mark.NeedsYou -> Box(dot.background(Palette.orange))
        Aside.Tasks.Mark.Working -> Box(dot.background(Palette.purple))
        Aside.Tasks.Mark.Updated -> Box(dot.background(Palette.blue))
        Aside.Tasks.Mark.Lit -> Box(dot.background(Palette.fg))
        Aside.Tasks.Mark.Idle -> Box(dot.border(1.dp, Palette.dim, CircleShape))
    }
}

@Composable
private fun More(text: String, onClick: () -> Unit) = Text(
    text,
    color = Palette.blue,
    style = Type.label,
    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
)

@Composable
private fun Action(text: String, modifier: Modifier, onClick: () -> Unit) = Box(
    modifier
        .clip(RoundedCornerShape(14.dp))
        .background(Palette.bg1)
        .border(1.dp, Palette.border, RoundedCornerShape(14.dp))
        .clickable(onClick = onClick)
        .padding(vertical = 11.dp),
    contentAlignment = Alignment.Center,
) { Text(text, color = Palette.fg, style = Type.label) }

/** How long something sent while the list is up says it waits. */
private const val HELD_MS = 3_000L
