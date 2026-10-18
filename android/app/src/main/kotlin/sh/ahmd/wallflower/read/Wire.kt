package sh.ahmd.wallflower.read

import sh.ahmd.wallflower.protocol.Entries
import sh.ahmd.wallflower.protocol.Pane

// The read view as the wire has it, into the read model.

/** With [m] applied: a reset replaces everything; otherwise its entries come in, and the context it says replaces what was. */
fun Reading.apply(m: Entries): Reading {
    if (m.reset) {
        val entries = m.entries.map(::entryOf)
        return Reading(
            entries.takeLast(Reading.MAX_ENTRIES), m.more || entries.size > Reading.MAX_ENTRIES, m.error,
            loaded = true, context = m.context ?: 0,
        )
    }
    return with(m.entries.map(::entryOf)).copy(
        problem = if (m.entries.isEmpty()) m.error ?: problem else m.error,
        loaded = true,
        context = m.context ?: context,
    )
}

/** What a pane's program is doing, if it says. */
fun activityOf(pane: Pane?): Activity? = when (pane?.activity) {
    Pane.WORKING -> Activity.Working
    Pane.WAITING -> Activity.Waiting
    Pane.IDLE -> Activity.Idle
    else -> null
}

private fun partyOf(from: String) = if (from == "user") Party.User else Party.Program

/** An entry of a kind this client doesn't know shows as a note of its text. */
internal fun entryOf(e: sh.ahmd.wallflower.protocol.Entry): Entry = when (e.kind) {
    "said" -> Entry.Said(e.id, partyOf(e.from), e.text)
    "step" -> Entry.Step(
        e.id, e.name, e.text, e.output, e.diff,
        when (e.status) {
            "failed" -> Entry.Step.Status.Failed
            "done" -> Entry.Step.Status.Done
            else -> Entry.Step.Status.Running
        },
        outputCut = e.outputCut,
        diffCut = e.diffCut,
    )
    "thought" -> Entry.Thought(e.id, e.text)
    "picture" -> Entry.Picture(e.id, partyOf(e.from), e.mime, e.size)
    else -> Entry.Note(e.id, e.text)
}
