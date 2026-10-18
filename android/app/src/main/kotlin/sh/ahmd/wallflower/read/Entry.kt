package sh.ahmd.wallflower.read

/** What a program is doing, when it says. */
enum class Activity {
    /** On a turn, its steps included. */
    Working,

    /** For the user to answer something mid-turn. */
    Waiting,

    /** Done until the next message. */
    Idle,
}

/** Who said something: the user, or the program in the pane. */
enum class Party { User, Program }

/** One part of a pane's read view: something said, or a step taken. */
sealed interface Entry {
    val id: String

    /** What either side said, in markdown. */
    data class Said(override val id: String, val by: Party, val text: String) : Entry

    /** A step the program took: a tool it ran, what it was asked, what came of it, and any change it made to a file. */
    data class Step(
        override val id: String,
        val name: String,
        val input: String,
        val output: String = "",
        /** Unified diff hunks. */
        val diff: String = "",
        val status: Status = Status.Running,
    ) : Entry {
        enum class Status { Running, Done, Failed }
    }

    /** The program thinking it through. */
    data class Thought(override val id: String, val text: String) : Entry

    /** A picture either side shared, base64. */
    data class Picture(override val id: String, val by: Party, val mime: String, val data: String) : Entry

    /** Something that happened rather than was said: an interruption, a compaction. */
    data class Note(override val id: String, val text: String) : Entry
}
