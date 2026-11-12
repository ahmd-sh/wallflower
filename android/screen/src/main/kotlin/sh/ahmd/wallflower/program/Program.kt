package sh.ahmd.wallflower.program

import sh.ahmd.wallflower.screen.InputLine
import sh.ahmd.wallflower.screen.Prompt
import sh.ahmd.wallflower.screen.Screen

/**
 * The phone's half of a program's plugin: what wallflower can read off the
 * program's screen, and how to drive it beyond typing. The computer's half says
 * which panes run it, and names it by the same id (see [Programs]).
 */
interface Program {
    /** The program's input line, if wallflower can see one on its screen. */
    fun line(screen: Screen): InputLine?

    /** Choices the program offers on its screen, to answer by touch. */
    fun prompt(screen: Screen): Prompt? = null

    /** What the program says on its screen it's doing, in its own words, while it works; null if it doesn't say. */
    fun status(screen: Screen): String? = null

    /** Questions the program asked that wait on its screen for the user to bring them up; null if none do. */
    fun asked(screen: Screen): Asked? = null

    /**
     * What the program's notification says on the phone: a desktop
     * notification ([title], [body]), or its terminal's bell (no [body]),
     * given its [screen] as it came if the program [readsScreenToNotify]
     * (null if it couldn't be read); null: nothing. A bell says nothing
     * unless the program has it say something: shells and editors ring it
     * at a mistyped key.
     */
    fun notice(title: String?, body: String?, screen: Screen?): Notice? = body?.let { Notice(title, it) }

    /** Whether [notice] needs the program's screen, which takes opening its pane a moment. */
    val readsScreenToNotify: Boolean get() = false

    /** What empties a row of the input line; empty if nothing does, and the box adds to the line. */
    val clearKeys: List<String>

    /**
     * Keys that empty [line], [keys] being what empties a row (the
     * program's, or the user's own): once per row it takes, and once more.
     * [unseen]: the program can't be seen, as under its mobile view.
     */
    fun clear(line: InputLine, keys: List<String> = clearKeys, unseen: Boolean = false): List<String> =
        List(line.rows * 2 + 1) { keys }.flatten()

    /** What starts a new line of what's being typed without sending it, if the program takes several; a shell runs the line. */
    val newlineKeys: List<String>? get() = null

    /** Whether what's sent while it works waits for it to take later (queued), rather than going to what it's doing. */
    val queues: Boolean get() = false

    /** What the program is sent to compact its conversation, if it can. */
    val compact: String? get() = null

    /**
     * What takes the suggestion in the program's empty input line into the
     * line, whole, if it shows suggestions: cut short to fit the screen
     * ("…"), what the screen says isn't all of one.
     */
    val acceptSuggestion: List<String>? get() = null

    /** Commands the composer's + offers for the program's panes, which start what's sent. */
    val commands: List<Command> get() = emptyList()

    /**
     * What brings a message the user sent back into the input line, to edit
     * and send again, if the program can: [latest] being their newest, which
     * the program is still on while [working].
     */
    fun recall(latest: Boolean, working: Boolean): Recall? = null

    /**
     * Walking [prompt], opened by [recall]'s command, to the user's message
     * that [said]: where the list of their messages has its cursor, or
     * whether what picking one asks is about that message; null if it's neither.
     */
    fun walk(prompt: Prompt, said: String): Walk? = null

    /** What picking [option] of [prompt] does besides answering it, if wallflower needs to know. */
    fun picked(prompt: Prompt, option: Prompt.Option): Effect? = null

    /**
     * What answering [prompt] did besides, whoever answered it (at the computer,
     * say), as the input [line] the program shows once it's gone tells; null
     * if nothing wallflower needs to know.
     */
    fun settled(prompt: Prompt, line: InputLine?): Effect? = null

    /**
     * What a tab shows for the program's window, from the terminal title it
     * sets ([title]), in place of a name the multiplexer gave the window
     * after the program's command; null keeps that name.
     */
    fun label(title: String): String? = null

    /** The key bar its panes get until the user sets them one; programs with the same one share it. */
    val keyBar: KeyDefaults? get() = null

    /** Shortcuts its panes offer, on the bar's shortcuts key, besides the user's own. */
    val shortcuts: List<Shortcut> get() = emptyList()

    /**
     * What the program shows in its input line's place that it isn't asking,
     * where keys are its own rather than typed (a view of its own, an answer
     * it gives aside); null while there's nothing.
     */
    fun aside(screen: Screen): Aside? = null

    /** The mode the program says on its screen it's in; null if it doesn't say, or it can't be seen. */
    fun mode(screen: Screen): Mode? = null

    /** What steps the program on to its next mode, if it goes round modes with a key. */
    val nextMode: List<String>? get() = null

    /** Modes the program may have, by the names [mode] reads, in no order that means anything: which it has depends on how it's set up. */
    val modes: List<String> get() = emptyList()

    /** The color the program shows [mode] in. */
    fun hue(mode: String): Hue = Hue.Grey
}

/** A color by the theme's name for it, so a program can say what it shows in color and the theme says how that looks. */
enum class Hue { Grey, Red, Orange, Yellow, Green, Aqua, Blue, Purple }

/**
 * A key bar a program ships: its [name], its [keys] by wallflower's names
 * (`arrows`: the arrows around enter, in one key; `ctrl`, `alt`: sticky;
 * `shortcuts`: the shortcuts, first if it's left out), and
 * what the send button presses when there's nothing to send ([stop]).
 */
data class KeyDefaults(val name: String, val keys: List<String>, val stop: List<String> = listOf("C-c"))

/**
 * A shortcut a program ships: the [keys] it sends by wallflower's names, text
 * typed as it is (`/compact` then `Enter` runs it, `/btw` alone starts what's
 * sent), and its [label], if not the keys as the bar shows them.
 */
data class Shortcut(val keys: List<String>, val label: String? = null)

/** A command a program takes, as the + offers it: its [text], and what it's [about]. */
data class Command(val text: String, val about: String)

/** Questions waiting to be answered ("2 questions"), and the keys that bring them up. */
data class Asked(val what: String, val keys: List<String>)

/** What brings back a message the user sent: [Keys], or a [Command] sent as if typed. */
sealed interface Recall {
    data class Keys(val keys: List<String>) : Recall

    data class Command(val text: String) : Recall
}

/** A prompt on the way to one of the user's messages, from the list of them, oldest first. */
sealed interface Walk {
    /**
     * The list: its cursor on the message with [back] of theirs newer than
     * it (-1 below the newest), going [last] back at most. The keys that
     * move the cursor [up] a message (back), or [down], and that [pick] the
     * one under it.
     */
    data class Listed(val back: Int, val last: Int, val up: List<String>, val down: List<String>, val pick: List<String>) : Walk

    /** What picking one asks: whether it's [on] the message walked to, and the keys [back] to the list, its cursor where it was. */
    data class Picked(val on: Boolean, val back: List<String>) : Walk
}

/** What a program shows in its input line's place besides asking something, where keys are its own; [leave] puts it away. */
sealed interface Aside {
    val leave: List<String>

    /** A view of its own, as Claude's detailed transcript: what it [is]. */
    data class View(val name: String, override val leave: List<String>) : Aside

    /**
     * An answer to a question asked aside, as with Claude's /btw: the
     * [question], and the [rows] of its answer that show (null while it's
     * being answered), wrapped at [width] and scrolled [step] rows at a time
     * by [up] and [down] when it's [taller] than its room.
     */
    data class Answer(
        val question: String,
        val rows: List<String>?,
        val width: Int,
        val taller: Boolean,
        override val leave: List<String>,
        val up: List<String>,
        val down: List<String>,
        val step: Int,
    ) : Aside {
        /**
         * What's been read of the answer: [kept], read before (scrolled
         * elsewhere, or less of it written), with the rows showing now laid
         * over where they meet. Rows that don't meet it are another stretch,
         * or another answer, and stand alone.
         */
        fun over(kept: List<String>): List<String> {
            val shown = rows ?: return kept
            if (kept.isEmpty() || shown.isEmpty()) return shown
            if (java.util.Collections.indexOfSubList(kept, shown) >= 0) return kept
            // A row or two alike (blanks between paragraphs) isn't enough to say they meet, unless it's all that was kept.
            fun meets(k: Int, of: Int) = k >= 2 || k == of
            for (k in minOf(kept.size, shown.size) downTo 1) {
                if (meets(k, kept.size) && kept.subList(kept.size - k, kept.size) == shown.subList(0, k)) return kept + shown.drop(k)
            }
            for (k in minOf(kept.size, shown.size) downTo 1) {
                if (meets(k, kept.size) && shown.subList(shown.size - k, shown.size) == kept.subList(0, k)) return shown + kept.drop(k)
            }
            return shown
        }

        /** [rows] of the answer as text: each row the program wrapped (the next one's first word wouldn't fit beside it) joined to the next. */
        fun text(rows: List<String>): String {
            val out = StringBuilder()
            var previous: String? = null
            for (row in rows) {
                if (previous != null) {
                    val word = row.trimStart().substringBefore(' ')
                    out.append(if (previous.isNotEmpty() && word.isNotEmpty() && previous.length + 1 + word.length > width) " " else "\n")
                }
                out.append(if (previous != null && out.endsWith(" ")) row.trimStart() else row)
                previous = row
            }
            return out.toString().trim('\n')
        }
    }

    /**
     * The program's own list of its tasks, its conversations, to open one
     * from, as Codex's agent command center: what it's [named], its
     * [filters] (each with how many it holds, if it shows; one is current), and
     * the [groups] of tasks it shows, one under its cursor. There may be
     * more [above] and [below] than shows; with none, what it [says]
     * instead. [up] and [down] move its cursor, [open] opens the task under
     * it, [next] and [previous] go between filters, [pageUp] and [pageDown]
     * scroll, and [new] starts a task.
     */
    data class Tasks(
        val named: String,
        val filters: List<Filter>,
        val groups: List<Group>,
        val above: Boolean,
        val below: Boolean,
        val says: String?,
        override val leave: List<String>,
        val up: List<String>,
        val down: List<String>,
        val open: List<String>,
        val next: List<String>,
        val previous: List<String>,
        val pageUp: List<String>,
        val pageDown: List<String>,
        val new: List<String>,
    ) : Aside {
        /** A filter, with how many it holds, if that's shown. */
        data class Filter(val name: String, val count: Int?, val current: Boolean)

        data class Group(val name: String, val count: String, val tasks: List<Task>)

        data class Task(val name: String, val mark: Mark, val current: Boolean)

        /** What a task's mark says of it: it needs the user, it's working, something's new in it since it was looked at, one of those (which can't be told apart), or nothing. */
        enum class Mark { NeedsYou, Working, Updated, Lit, Idle }

        val tasks: List<Task> get() = groups.flatMap { it.tasks }

        /** The keys that move the cursor to the [at]th task and open it. */
        fun opening(at: Int): List<String> {
            val all = tasks
            if (at !in all.indices) return emptyList()
            return moves(at - all.indexOfFirst { it.current }.coerceAtLeast(0), down, up) + open
        }

        /** The keys that go to the [at]th filter. */
        fun showing(at: Int): List<String> {
            val from = filters.indexOfFirst { it.current }.takeIf { it >= 0 } ?: return emptyList()
            if (at !in filters.indices) return emptyList()
            return moves(at - from, next, previous)
        }

        private fun moves(by: Int, forward: List<String>, back: List<String>) = List(kotlin.math.abs(by)) { if (by > 0) forward else back }.flatten()
    }
}

/** A mode a program says it's in: its [name], the [mark] it shows with it (empty if it's not been seen), and the [hue] it's shown in. */
data class Mode(val name: String, val mark: String, val hue: Hue = Hue.Grey)

/**
 * The modes a program goes round, a [Program.nextMode] at a time, as far as
 * they've been seen: which came after which, and how each is marked. Those
 * the program may have ([Program.modes]) stand in for those not seen yet, in
 * no order; one a whole round didn't come to goes, till it's seen.
 */
class Cycle(private val known: List<String>, private val hue: (String) -> Hue = { Hue.Grey }) {
    private val after = HashMap<String, String>()
    private val marks = LinkedHashMap<String, String>()
    private val missed = HashSet<String>()

    /** [mode] is on screen. */
    fun saw(mode: Mode) {
        marks[mode.name] = mode.mark
        missed -= mode.name
    }

    /** [to] came up one step on from [from]. */
    fun stepped(from: Mode, to: Mode) {
        saw(from)
        saw(to)
        after[from.name] = to.name
    }

    /** A whole round didn't come to [name]: what was seen around it is out of date. */
    fun missed(name: String) {
        missed += name
        after.remove(name)
        after.values.removeAll { it == name }
    }

    /** The modes from [at], as far round as they've been seen to follow, then the rest, unordered. */
    fun from(at: Mode): List<Mode> {
        val out = mutableListOf(at)
        var name = at.name
        while (true) {
            val next = after[name]?.takeIf { n -> out.none { it.name == n } } ?: break
            out += Mode(next, marks[next].orEmpty(), hue(next))
            name = next
        }
        for (n in marks.keys + known) if (n !in missed && out.none { it.name == n }) out += Mode(n, marks[n].orEmpty(), hue(n))
        return out
    }
}

/** A notification as the phone shows it. */
data class Notice(val title: String?, val body: String)

/** Something picking an option did that the program won't record until later. */
sealed interface Effect {
    /** Its conversation went back to before the latest of the user's messages that [said] matches. */
    class Rewound(val said: (String) -> Boolean) : Effect
}

/**
 * A program the phone has no half of its own for, as a plugin the user gave
 * the gateway describes it, in data, since the phone loads no code: its key
 * bar, commands, shortcuts, and the keys that start a new line and empty
 * one. It reads a line only from prompt marks (OSC 133), if it makes them.
 */
class Supplied(
    override val keyBar: KeyDefaults?,
    override val commands: List<Command>,
    override val newlineKeys: List<String>?,
    override val clearKeys: List<String>,
    override val shortcuts: List<Shortcut> = emptyList(),
) : Program by Shell(clearKeys, keyBar)

/** The programs wallflower ships, and those the gateway's plugins describe, by the id the gateway names a pane's program by. */
object Programs {
    private val shells = KeyDefaults("shell", listOf("shortcuts", "ctrl", "Tab", "arrows", "C-r", "C-c", "C-d", "Escape"))

    /** Commands more than keys, as a shell's keys are its own; these do the same in any. */
    private val shellShortcuts = listOf(
        Shortcut(listOf("clear", "Enter")),
        Shortcut(listOf("cd -", "Enter")),
        Shortcut(listOf("!!", "Enter")),
        Shortcut(listOf("C-z"), "suspend"),
    )
    private val other = Shell()

    val builtin: Map<String, Program> = linkedMapOf(
        "claude" to Claude,
        "codex" to Codex,
        "zsh" to Shell(listOf("C-x", "C-k"), shells, shellShortcuts), // kill-buffer, as zsh binds it for emacs mode
        "shell" to Shell(keyBar = shells, shortcuts = shellShortcuts),
    )

    @Volatile
    private var supplied: Map<String, Program> = emptyMap()

    /** The programs the gateway's plugins describe, by id, in place of those before. */
    fun supply(programs: Map<String, Program>) {
        supplied = programs.mapKeys { it.key.lowercase() }
    }

    /** The ids of the programs the gateway's plugins describe. */
    fun suppliedIds(): Set<String> = supplied.keys - builtin.keys

    /** A program a plugin of the user's describes, by its id; null if it's one wallflower ships, or none is. */
    fun supplied(id: String?): Program? = id?.lowercase()?.takeIf { it !in builtin }?.let { supplied[it] }

    /** A pane's program, by its id; one nothing describes (or none) is a plain terminal's. */
    fun of(id: String?): Program = id?.lowercase()?.let { builtin[it] ?: supplied[it] } ?: other
}
