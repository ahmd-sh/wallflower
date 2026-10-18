package sh.ahmd.wallflower.read

import sh.ahmd.wallflower.protocol.Entries

// The read view as the wire has it, into the read model.

/** With [m] applied: a reset replaces everything; otherwise its entries come in, and what it says of the program replaces what was. */
fun Reading.apply(m: Entries): Reading {
    val activity = activityOf(m.state)
    if (m.reset) {
        val entries = m.entries.map(::entryOf)
        return Reading(
            entries.takeLast(Reading.MAX_ENTRIES), m.more || entries.size > Reading.MAX_ENTRIES, m.error,
            loaded = true, activity = activity, since = m.since, context = m.context ?: 0,
        )
    }
    return with(m.entries.map(::entryOf)).copy(
        problem = if (m.entries.isEmpty()) m.error ?: problem else m.error,
        loaded = true,
        activity = activity ?: this.activity,
        since = if (activity != null) m.since else since,
        context = m.context ?: context,
    )
}

private fun activityOf(state: String?) = when (state) {
    Entries.WORKING -> Activity.Working
    Entries.WAITING -> Activity.Waiting
    Entries.IDLE -> Activity.Idle
    else -> null
}

private fun partyOf(role: String) = if (role == "user") Party.User else Party.Program

/** An entry of a kind this client doesn't know shows as a note of its text. */
internal fun entryOf(e: sh.ahmd.wallflower.protocol.Entry): Entry = when (e.kind) {
    "text" -> Entry.Said(e.id, partyOf(e.role), e.text)
    "tool" -> Entry.Step(
        e.id, e.name, e.text, e.output, e.diff,
        when {
            e.error -> Entry.Step.Status.Failed
            e.done -> Entry.Step.Status.Done
            else -> Entry.Step.Status.Running
        },
    )
    "thinking" -> Entry.Thought(e.id, e.text)
    "image" -> Entry.Picture(e.id, partyOf(e.role), e.mime, e.data)
    else -> Entry.Note(e.id, e.text)
}
