package sh.ahmd.wallflower.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// The wire format is defined in docs/protocol.md. Field order matches the Go
// structs so both sides encode identical bytes (checked by the golden tests).

const val VERSION = 4
const val DEFAULT_HISTORY = 1000

val WireJson = Json {
    classDiscriminator = "t"
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
sealed interface ClientMessage

/** The first message: [mux] names the multiplexer the session is in (tmux, rex; null: the gateway's default), [away] as in [Away]. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("hello")
data class ClientHello(
    val v: Int = VERSION,
    val cols: Int,
    val rows: Int,
    val mux: String? = null,
    val session: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val away: Boolean = false,
) : ClientMessage

@Serializable
@SerialName("resize")
data class Resize(val cols: Int, val rows: Int) : ClientMessage

@Serializable
@SerialName("open")
data class Open(val pane: String, val history: Int? = null) : ClientMessage

@Serializable
@SerialName("close")
data class Close(val pane: String) : ClientMessage

/**
 * Keys by wallflower's names (Enter, Escape, Up, C-c, M-Enter, BTab, ...),
 * anything else typed as it is; bytes go in data frames, composed text in
 * [Submit]. With [bindings], as if typed on a keyboard attached to the
 * multiplexer, so its key bindings apply, if it can ([Mux.BINDINGS]);
 * "prefix" stands for its prefix key.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("keys")
data class Keys(
    val pane: String,
    val keys: List<String>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val bindings: Boolean = false,
) : ClientMessage

/**
 * Composed text in place of whatever the program's input line holds: [clear]
 * keys empty the line first, then the gateway sends the text as the program
 * takes it best, and Enter if [enter].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("submit")
data class Submit(
    val pane: String,
    val text: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val enter: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val clear: List<String> = emptyList(),
) : ClientMessage

@Serializable
@SerialName("select")
data class Select(val window: String? = null, val pane: String? = null) : ClientMessage

/** Makes a pane its window's active pane, zoomed if the window has others; undone on disconnect. */
@Serializable
@SerialName("focus")
data class Focus(val pane: String) : ClientMessage

/** Opens a pane beside this one, in its directory, and focuses it. */
@Serializable
@SerialName("split")
data class Split(val pane: String) : ClientMessage

/** Opens a window, current in the client's session only. */
@Serializable
@SerialName("new")
data object New : ClientMessage

@Serializable
@SerialName("rename")
data class Rename(val window: String, val name: String) : ClientMessage

/** Closes a window or a pane; closing a window's last pane closes it. */
@Serializable
@SerialName("kill")
data class Kill(val window: String? = null, val pane: String? = null) : ClientMessage

/** Starts or stops [pane]'s mobile view arriving as [Entries], for a pane with one ([Pane.view]). */
@Serializable
@SerialName("read")
data class Read(val pane: String, val on: Boolean) : ClientMessage

/** Asks for a picture entry's bytes, which come in a picture frame. */
@Serializable
@SerialName("picture")
data class Picture(val pane: String, val id: String) : ClientMessage

/** Starts sending a file: [size] bytes follow in upload frames with [id]; [Uploaded] says where it was saved. */
@Serializable
@SerialName("upload")
data class Upload(val id: String, val name: String, val size: Long) : ClientMessage

@Serializable
@SerialName("ping")
data class Ping(val id: Int) : ClientMessage

/** The user has left the app for now ([on]), or is back: while away, it doesn't size the session's windows. */
@Serializable
@SerialName("away")
data class Away(val on: Boolean) : ClientMessage

@Serializable
sealed interface ServerMessage

@Serializable
@SerialName("hello")
data class ServerHello(
    val v: Int,
    val mux: Mux,
    /** Where uploads are saved: paths under it are uploads. */
    val uploads: String? = null,
) : ServerMessage

/** The multiplexer the session is in (tmux), its version, and what it [can] do beyond what every one does. */
@Serializable
data class Mux(val name: String, val version: String, val can: List<String> = emptyList()) {
    companion object {
        /** [Keys] can go through the multiplexer's own key bindings. */
        const val BINDINGS = "bindings"
    }
}

@Serializable
@SerialName("state")
data class State(val session: String, val name: String, val windows: List<Window>) : ServerMessage

@Serializable
data class Window(
    val id: String,
    val index: Int,
    val name: String,
    val active: Boolean,
    val panes: List<Pane>,
)

@Serializable
data class Pane(
    val id: String,
    val index: Int,
    val active: Boolean,
    val cols: Int,
    val rows: Int,
    /** A full-screen app owns the pane; no scrollback is kept for it. */
    val alt: Boolean = false,
    val title: String = "",
    val path: String = "",
    val command: String,
    /** The program the pane runs (claude, zsh, shell), if the gateway knows it: the app's half of it goes by the same name (Programs). */
    val program: String? = null,
    /** The program has a mobile view of the pane; see [Read]. */
    val view: Boolean = false,
    /** What a program with a mobile view is doing, if it says: [WORKING], [WAITING] or [IDLE]; [since] when, in Unix milliseconds. */
    val activity: String = "",
    val since: Long = 0,
) {
    companion object {
        const val WORKING = "working"
        const val WAITING = "waiting"
        const val IDLE = "idle"
    }
}

/** Recreate the pane's terminal at this size; the next data frame repaints it. */
@Serializable
@SerialName("reset")
data class Reset(val pane: String, val cols: Int, val rows: Int) : ServerMessage

/**
 * A pane's mobile view. With [reset], its latest entries replace any before
 * ([more]: earlier ones weren't sent); otherwise these entries are new, or
 * replace the ones with their ids. [error] says why there's nothing to read.
 */
@Serializable
@SerialName("entries")
data class Entries(
    val pane: String,
    val reset: Boolean = false,
    val more: Boolean = false,
    val entries: List<Entry> = emptyList(),
    val error: String? = null,
    /** Tokens the conversation takes of the program's context, when it says; 0 once it no longer knows (just compacted). */
    val context: Int? = null,
) : ServerMessage

/** One part of a mobile view: something said, or a step taken. */
@Serializable
data class Entry(
    val id: String,
    /** user or program */
    val from: String,
    /** said (markdown), step, thought, picture or note */
    val kind: String,
    /** What was said; for a step, what it was asked. */
    val text: String = "",
    val name: String = "",
    /** A step's: running, done or failed. */
    val status: String = "",
    val output: String = "",
    @SerialName("output_cut") val outputCut: Int = 0,
    /** A step's change to a file, as unified diff hunks. */
    val diff: String = "",
    @SerialName("diff_cut") val diffCut: Int = 0,
    val mime: String = "",
    /** A picture's bytes, which [Picture] asks for. */
    val size: Int = 0,
)

@Serializable
@SerialName("error")
data class ErrorMessage(val message: String) : ServerMessage

@Serializable
@SerialName("pong")
data class Pong(val id: Int) : ServerMessage

@Serializable
@SerialName("bye")
data class Bye(val reason: String = "") : ServerMessage

/** Where an upload was saved, once all of it arrived, or why it wasn't. */
@Serializable
@SerialName("uploaded")
data class Uploaded(val id: String, val path: String? = null, val error: String? = null) : ServerMessage

/** A desktop notification [pane]'s program sent through its terminal (OSC 9, 777 or 99), or its [bell], with no body. */
@Serializable
@SerialName("notification")
data class Notification(val pane: String, val title: String? = null, val body: String = "", val bell: Boolean = false) : ServerMessage
