package sh.ahmd.wallflower.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class FramesTest {
    private fun golden(name: String) = File(System.getProperty("wallflower.testdata"), name).readBytes()

    @Test
    fun encodesClientFramesLikeTheGateway() {
        val out = ByteArrayOutputStream()
        val w = FrameWriter(out)
        listOf(
            ClientHello(cols = 56, rows = 20),
            ClientHello(cols = 56, rows = 20, session = "o"),
            Resize(70, 25),
            Open("%1"),
            Open("%1", history = 500),
            Close("%1"),
            Keys("%1", listOf("C-c", "Up", "M-Enter")),
            Select(window = "@2"),
            Select(pane = "%3"),
            Zoom("%1"),
            ScrollbackRequest(1, "%1", -50, -1),
            TmuxCommand(2, "display -p '#{session_name}'"),
            Ping(3),
            Paste("%1", "two\nlines ✓"),
            Keys("%1", listOf("prefix", "o"), bindings = true),
            Focus("%2"),
            Split("%2"),
            New,
            Rename("@2", "logs ✓"),
            Kill(pane = "%2"),
            Kill(window = "@2"),
            Transcript("%2", on = true),
            Transcript("%2", on = false),
            Upload("u1", "PXL 1 ✓.jpg", 3),
            Rewind("%2", "u1.0"),
            Rewind("%2", "u1.0", code = true),
        ).forEach(w::send)
        w.sendData("%1", "echo héllo ✓\r".encodeToByteArray())
        w.sendUpload("u1", byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0), length = 3)

        val want = golden("client.bin")
        assertEquals(want.decodeToString(), out.toByteArray().decodeToString())
        assertContentEquals(want, out.toByteArray())
    }

    @Test
    fun decodesServerFramesFromTheGateway() {
        val r = FrameReader(ByteArrayInputStream(golden("server.bin")))
        fun message() = assertIs<Frame.Message>(r.next()).message

        assertEquals(ServerHello(1, "3.8", uploads = "/Users/me/Library/Caches/wallflower/uploads"), message())
        val state = assertIs<State>(message())
        assertEquals("o", state.name)
        val (claude, floating) = state.windows.single().panes
        assertEquals(Pane("%2", 0, false, 0, 0, 28, 20, alt = true, agent = "waiting", title = "✳ Fix tests",
            path = "/Users/ahmedshaikh/dev/wallflower", command = "claude", transcript = "claude"), claude)
        assertEquals(true, floating.floating)
        assertEquals(Reset("%2", 28, 20), message())

        val data = assertIs<Frame.Data>(r.next())
        assertEquals("%2", data.pane)
        assertEquals("\u001b[0m\u001b[H\u001b[2J\u001b[42mgreen\u001b[49m ✓\r\n", data.bytes.decodeToString())

        assertEquals(ScrollbackReply(1, "%2", 120, listOf("one", "\u001b[31mtwo\u001b[39m")), message())
        assertEquals(Result(2, listOf("o")), message())
        assertEquals(Result(3, error = "tmux: no-such-command: unknown command"), message())
        assertEquals(ErrorMessage("bad id \"x\""), message())
        assertEquals(Pong(4), message())
        assertEquals(
            Entries(
                "%2", reset = true, more = true,
                entries = listOf(
                    Entry("u1", "user", "text", "fix the tests ✓"),
                    Entry("a1", "assistant", "tool", "go test ./...", name = "Bash", output = "ok", done = true),
                    Entry("a2", "assistant", "tool", "rm -rf /", name = "Bash", output = "denied", done = true, error = true),
                    Entry("u2", "user", "image", mime = "image/png", data = "iVBORw0KGgo="),
                    Entry("a3", "assistant", "text", "**Done.** All `3` pass."),
                ),
            ),
            message(),
        )
        assertEquals(Entries("%2", entries = listOf(Entry("n1", "user", "note", "[Request interrupted by user]"))), message())
        assertEquals(Entries("%2", state = Entries.WAITING, since = 1792209270006, context = 17967), message())
        assertEquals(Entries("%2", reset = true, error = "the conversation ended"), message())
        assertEquals(Uploaded("u1", path = "/Users/me/Library/Caches/wallflower/uploads/PXL-1.jpg"), message())
        assertEquals(Uploaded("u2", error = "too big"), message())
        assertEquals(Rewound("%2", "u1.0"), message())
        assertEquals(Rewound("%2", "u1.0", error = "Claude is busy"), message())
        assertEquals(Bye("tmux exited"), message())
        assertNull(r.next())
    }

    @Test
    fun unknownMessageTypesAreSkippable() {
        val r = FrameReader(ByteArrayInputStream(frame(FRAME_JSON, """{"t":"from-the-future","x":1}""".encodeToByteArray())))
        assertIs<Frame.Unknown>(r.next())
    }

    @Test
    fun rejectsBadFrames() {
        val cases = mapOf(
            "zero length" to byteArrayOf(0, 0, 0, 0),
            "unknown type" to frame(9, byteArrayOf(0)),
            "truncated pane" to frame(FRAME_DATA, byteArrayOf(5, 'x'.code.toByte())),
            "known type, bad fields" to frame(FRAME_JSON, """{"t":"reset","pane":1}""".encodeToByteArray()),
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
