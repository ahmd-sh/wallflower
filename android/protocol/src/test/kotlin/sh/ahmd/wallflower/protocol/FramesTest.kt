package sh.ahmd.wallflower.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FramesTest {
    private fun golden(name: String) = File(System.getProperty("wallflower.testdata"), name).readBytes()

    @Test
    fun encodesClientFramesLikeTheGateway() {
        val out = ByteArrayOutputStream()
        val w = FrameWriter(out)
        listOf(
            ClientHello(cols = 56, rows = 20),
            ClientHello(cols = 56, rows = 20, session = "o"),
            ClientHello(cols = 56, rows = 20, away = true),
            ClientHello(cols = 56, rows = 20, mux = "rex", session = "work"),
            Resize(70, 25),
            Open("%1"),
            Open("%1", history = 500),
            Close("%1"),
            Keys("%1", listOf("C-c", "Up", "M-Enter")),
            Keys("%1", listOf("prefix", "o"), bindings = true),
            Submit("%1", "fix the tests ✓", enter = true, clear = listOf("A", "C-u", "C-u", "C-u")),
            Submit("%1", "two\nlines"),
            Select(window = "@2"),
            Select(pane = "%3"),
            Focus("%2"),
            Split("%2"),
            New,
            Rename("@2", "logs ✓"),
            Kill(pane = "%2"),
            Kill(window = "@2"),
            Read("%2", on = true),
            Read("%2", on = false),
            Picture("%2", "u2.0"),
            Upload("u1", "PXL 1 ✓.jpg", 3),
            Ping(3),
            Away(true),
            Away(false),
            Sessions,
            Switch("rex", "session:1"),
            NewSession("tmux", "work ✓"),
            NewSession("tmux"),
            RenameSession("tmux", "$2", "play"),
            KillSession("tmux", "$2"),
            Detach,
            ListDir("l1", "~/dev ✓"),
            ListDir("l2", ""),
            Fetch("f1", "/Users/me/shot.png"),
            Fetch("f2", "~/log.txt", limit = 65536),
            Got("f1", 65536),
            Cancel("f1"),
        ).forEach(w::send)
        w.sendData("%1", "\u001b[<64;10;5M".encodeToByteArray())
        w.sendUpload("u1", byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0), length = 3)
        w.flush()

        val want = golden("client.bin")
        assertEquals(want.decodeToString(), out.toByteArray().decodeToString())
        assertContentEquals(want, out.toByteArray())
    }

    @Test
    fun decodesServerFramesFromTheGateway() {
        val r = FrameReader(ByteArrayInputStream(golden("server.bin")))
        fun message() = assertIs<Frame.Message>(r.next()).message

        assertEquals(
            ServerHello(
                VERSION, uploads = "/Users/me/Library/Caches/wallflower/uploads", machine = "vishingstar", can = listOf(ServerHello.FILES),
                programs = listOf(
                    ProgramData(
                        "aider",
                        keys = KeyBarData("aider", listOf("Escape", "ctrl", "/undo Enter"), stop = listOf("C-c")),
                        commands = listOf(CommandData("/undo", "undo the last change ✓"), CommandData("/diff")),
                        newline = listOf("M-Enter"),
                        clear = listOf("C-a", "C-k"),
                        shortcuts = listOf(ShortcutData(listOf("/undo", "Enter")), ShortcutData(listOf("C-c", "C-c"), label = "quit")),
                    ),
                ),
            ),
            message(),
        )
        assertEquals(Attached(Mux("tmux", "3.8", listOf(Mux.BINDINGS))), message())
        val state = assertIs<State>(message())
        assertEquals("o", state.name)
        val (claude, shell, other) = state.windows.single().panes
        assertEquals(
            Pane(
                "%2", 0, false, 28, 20, alt = true, title = "✳ Fix tests", path = "/Users/ahmedshaikh/dev/wallflower",
                command = "claude", program = "claude", view = true, activity = Pane.WAITING, since = 1792209270006,
            ),
            claude,
        )
        assertEquals(Pane("%3", 1, true, 28, 20, command = "zsh", program = "zsh"), shell)
        assertEquals(Pane("%4", 2, false, 28, 20, command = "htop"), other)
        assertEquals(Reset("%2", 28, 20), message())

        val data = assertIs<Frame.Data>(r.next())
        assertEquals("%2", data.pane)
        assertEquals("\u001b[0m\u001b[H\u001b[2J\u001b[42mgreen\u001b[49m ✓\r\n", data.bytes.decodeToString(data.offset, data.offset + data.length))

        assertEquals(ErrorMessage("tmux: bad id \"x\""), message())
        assertEquals(Pong(4), message())
        assertEquals(
            Entries(
                "%2", reset = true, more = true, context = 17967, title = "Fix the tests ✓", queued = listOf("and the docs ✓"),
                entries = listOf(
                    Entry("u1", "user", "said", "fix the tests ✓"),
                    Entry("a0", "program", "thought", "the tests"),
                    Entry("a1", "program", "step", "go test ./...", name = "Bash", status = "done", output = "ok", outputCut = 1200),
                    Entry("a2", "program", "step", "rm -rf /", name = "Bash", status = "failed", output = "denied"),
                    Entry("a4", "program", "step", "main.go", name = "Edit", status = "done", output = "updated", diff = "@@ -3,2 +3,2 @@\n x\n-old\n+new ✓", diffCut = 12),
                    Entry("a5", "program", "step", "ls", name = "Bash", status = "running"),
                    Entry("u2.0", "user", "picture", mime = "image/png", size = 3),
                    Entry("a3", "program", "said", "**Done.** All `3` pass."),
                ),
            ),
            message(),
        )
        assertEquals(Entries("%2", entries = listOf(Entry("n1", "user", "note", "[Request interrupted by user]"))), message())
        assertEquals(Entries("%2", context = 0, queued = emptyList()), message())
        assertEquals(Entries("%2", reset = true, error = "the conversation ended"), message())
        assertEquals(Notification("%2", "Claude Code", "Claude needs your permission to use Bash ✓"), message())
        assertEquals(Notification("%3", body = "build done"), message())
        assertEquals(Notification("%4", bell = true), message())
        val picture = assertIs<Frame.Picture>(r.next())
        assertEquals("u2.0", picture.id)
        assertEquals(listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte()), picture.bytes.copyOfRange(picture.offset, picture.offset + picture.length).toList())
        val gone = assertIs<Frame.Picture>(r.next())
        assertEquals("gone" to 0, gone.id to gone.length)
        assertEquals(Uploaded("u1", path = "/Users/me/Library/Caches/wallflower/uploads/PXL-1.jpg"), message())
        assertEquals(Uploaded("u2", error = "too big"), message())
        assertEquals(Detached(), message())
        assertEquals(Detached("tmux exited"), message())
        assertEquals(
            SessionList(
                listOf(
                    MuxSessions("rex"),
                    MuxSessions(
                        "tmux", running = true, sessions = listOf(SessionInfo("$1", "o", 3), SessionInfo("$2", "work ✓", 1)),
                        can = listOf(MuxSessions.CAN_NEW, MuxSessions.CAN_RENAME, MuxSessions.CAN_KILL),
                    ),
                    MuxSessions("zellij", can = listOf(MuxSessions.CAN_NEW), error = "zellij: permission denied"),
                ),
            ),
            message(),
        )
        assertEquals(
            Listing(
                "l1", "/Users/me/dev ✓", more = true,
                entries = listOf(
                    FileEntry(".git", FileEntry.DIR, mtime = 1792209270006, hidden = true),
                    FileEntry("latest", FileEntry.DIR, link = true, mtime = 1792209270007),
                    FileEntry("notes ✓.md", FileEntry.FILE, size = 1234, mtime = 1792209270008),
                    FileEntry("wf.sock", FileEntry.OTHER),
                ),
            ),
            message(),
        )
        assertEquals(Listing("l2", "/Users/me/Library/Mail", error = "operation not permitted"), message())
        assertEquals(FetchedFile("f1", "/Users/me/shot.png", 3), message())
        val file = assertIs<Frame.File>(r.next())
        assertEquals("f1" to listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte()), file.id to file.bytes.copyOfRange(file.offset, file.offset + file.length).toList())
        assertEquals(FetchedFile("f2", "/Users/me/log.txt", error = "permission denied"), message())
        assertEquals(Bye("tmux exited"), message())
        assertNull(r.next())
    }

    /**
     * Every field the gateway sends reaches the app: taking any one out of a
     * golden message (one that isn't empty) changes what it decodes to, or
     * stops it decoding, so a field dropped or named differently here fails.
     */
    @Test
    fun everyFieldOfTheGatewaysReachesTheApp() {
        val frames = golden("server.bin")
        var at = 0
        var checked = 0
        while (at < frames.size) {
            val n = ByteBuffer.wrap(frames, at, 4).int
            val type = frames[at + 4].toInt()
            val payload = frames.copyOfRange(at + 5, at + 4 + n)
            at += 4 + n
            if (type != FRAME_JSON) continue
            val json = WireJson.parseToJsonElement(payload.decodeToString()).jsonObject
            val whole = WireJson.decodeFromJsonElement(ServerMessage.serializer(), json)
            for ((path, without) in withoutEach(json, "", top = true)) {
                val decoded = runCatching { WireJson.decodeFromJsonElement(ServerMessage.serializer(), without) }.getOrNull()
                assertNotEquals(whole, decoded, "${json["t"]}: $path doesn't reach the app")
                checked++
            }
        }
        assertTrue(checked > 100, "only $checked fields checked")
    }

    /** [e] with each of its fields that isn't empty taken out in turn, at any depth, by path; not a message's type. */
    private fun withoutEach(e: JsonElement, path: String, top: Boolean = false): List<Pair<String, JsonElement>> = when (e) {
        is JsonObject -> e.entries.filter { !top || it.key != "t" }.flatMap { (k, v) ->
            val here = if (empty(v)) emptyList() else listOf("$path.$k" to JsonObject(e - k))
            here + withoutEach(v, "$path.$k").map { (p, inner) -> p to JsonObject(e + (k to inner)) }
        }
        is JsonArray -> e.flatMapIndexed { i, v ->
            withoutEach(v, "$path[$i]").map { (p, inner) -> p to JsonArray(e.toMutableList().also { it[i] = inner }) }
        }
        else -> emptyList()
    }

    private fun empty(e: JsonElement) = when (e) {
        is JsonNull -> true
        is JsonObject -> e.isEmpty()
        is JsonArray -> e.isEmpty()
        is JsonPrimitive -> if (e.isString) e.content == "" else e.content == "false" || e.content == "0"
    }

    @Test
    fun unknownMessageTypesAreSkippable() {
        val r = FrameReader(ByteArrayInputStream(frame(FRAME_JSON, """{"t":"from-the-future","x":1}""".encodeToByteArray())))
        assertIs<Frame.Unknown>(r.next())
    }

    @Test
    fun framesOfANewerTypeArePassedOver() {
        val r = FrameReader(ByteArrayInputStream(frame(9, byteArrayOf(0)) + frame(FRAME_DATA, byteArrayOf(2, '%'.code.toByte(), '1'.code.toByte(), 'x'.code.toByte()))))
        assertEquals("%1", assertIs<Frame.Data>(r.next()).pane)
    }

    @Test
    fun aMessageItCantReadIsPassedOverAndSaidSo() {
        val r = FrameReader(ByteArrayInputStream(frame(FRAME_JSON, """{"t":"reset","pane":1}""".encodeToByteArray())))
        assertNotNull(assertIs<Frame.Unknown>(r.next()).problem)
    }

    @Test
    fun rejectsBadFrames() {
        val cases = mapOf(
            "zero length" to byteArrayOf(0, 0, 0, 0),
            "truncated pane" to frame(FRAME_DATA, byteArrayOf(5, 'x'.code.toByte())),
        )
        for ((name, bytes) in cases) {
            assertFailsWith<ProtocolException>(name) { FrameReader(ByteArrayInputStream(bytes)).next() }
        }
    }

    private fun frame(type: Int, payload: ByteArray): ByteArray {
        val n = payload.size + 1
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte(), type.toByte()) + payload
    }
}
