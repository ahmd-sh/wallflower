package sh.ahmd.wallflower.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// The wire format is defined in docs/protocol.md. Field order matches the Go
// structs so both sides encode identical bytes (checked by the golden tests).

const val VERSION = 1
const val DEFAULT_HISTORY = 1000

val WireJson = Json {
    classDiscriminator = "t"
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
sealed interface ClientMessage

@Serializable
@SerialName("hello")
data class ClientHello(val v: Int = VERSION, val cols: Int, val rows: Int, val session: String? = null) : ClientMessage

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
 * tmux key names (Enter, Escape, Up, C-c, M-Enter, BTab, ...); text goes in
 * data frames. With [bindings], as if typed on a keyboard attached to tmux,
 * so its key bindings apply; "prefix" stands for the prefix key.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("keys")
data class Keys(
    val pane: String,
    val keys: List<String>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val bindings: Boolean = false,
) : ClientMessage

/** Pasted as a terminal would: bracketed if the app asked for it, so newlines don't run anything. */
@Serializable
@SerialName("paste")
data class Paste(val pane: String, val text: String) : ClientMessage

@Serializable
@SerialName("select")
data class Select(val window: String? = null, val pane: String? = null) : ClientMessage

@Serializable
@SerialName("zoom")
data class Zoom(val pane: String) : ClientMessage

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

/** Lines in tmux numbering: 0 is the top visible line, negative is history. */
@Serializable
@SerialName("scrollback")
data class ScrollbackRequest(val id: Int, val pane: String, val start: Int, val end: Int) : ClientMessage

@Serializable
@SerialName("tmux")
data class TmuxCommand(val id: Int, val command: String) : ClientMessage

@Serializable
@SerialName("ping")
data class Ping(val id: Int) : ClientMessage

@Serializable
sealed interface ServerMessage

@Serializable
@SerialName("hello")
data class ServerHello(val v: Int, val tmux: String) : ServerMessage

@Serializable
@SerialName("state")
data class State(val session: String, val name: String, val windows: List<Window>) : ServerMessage

@Serializable
data class Window(
    val id: String,
    val index: Int,
    val name: String,
    val active: Boolean,
    val zoomed: Boolean,
    val cols: Int,
    val rows: Int,
    val panes: List<Pane>,
)

@Serializable
data class Pane(
    val id: String,
    val index: Int,
    val active: Boolean,
    val x: Int,
    val y: Int,
    val cols: Int,
    val rows: Int,
    val floating: Boolean = false,
    /** Floating stacking order; 0 is the front. */
    val z: Int = 0,
    /** A full-screen app owns the pane; tmux keeps no scrollback for it. */
    val alt: Boolean = false,
    /** The pane's @agent_state option, set by hooks. */
    val agent: String = "",
    val title: String = "",
    val path: String = "",
    val command: String,
)

/** Recreate the pane's terminal at this size; the next data frame repaints it. */
@Serializable
@SerialName("reset")
data class Reset(val pane: String, val cols: Int, val rows: Int) : ServerMessage

@Serializable
@SerialName("scrollback")
data class ScrollbackReply(
    val id: Int,
    val pane: String,
    @SerialName("history_size") val historySize: Int,
    val lines: List<String>,
) : ServerMessage

@Serializable
@SerialName("result")
data class Result(val id: Int, val lines: List<String> = emptyList(), val error: String? = null) : ServerMessage

@Serializable
@SerialName("error")
data class ErrorMessage(val message: String) : ServerMessage

@Serializable
@SerialName("pong")
data class Pong(val id: Int) : ServerMessage

@Serializable
@SerialName("bye")
data class Bye(val reason: String = "") : ServerMessage
