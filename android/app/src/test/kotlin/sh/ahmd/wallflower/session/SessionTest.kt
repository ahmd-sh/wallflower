package sh.ahmd.wallflower.session

import java.io.DataInputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.ahmd.wallflower.protocol.Bye
import sh.ahmd.wallflower.protocol.ClientHello
import sh.ahmd.wallflower.protocol.ClientMessage
import sh.ahmd.wallflower.protocol.FRAME_DATA
import sh.ahmd.wallflower.protocol.FRAME_JSON
import sh.ahmd.wallflower.protocol.Mux
import sh.ahmd.wallflower.protocol.Open
import sh.ahmd.wallflower.protocol.Pane
import sh.ahmd.wallflower.protocol.Ping
import sh.ahmd.wallflower.protocol.Pong
import sh.ahmd.wallflower.protocol.Read
import sh.ahmd.wallflower.protocol.ServerHello
import sh.ahmd.wallflower.protocol.ServerMessage
import sh.ahmd.wallflower.protocol.State
import sh.ahmd.wallflower.protocol.Submit
import sh.ahmd.wallflower.protocol.VERSION
import sh.ahmd.wallflower.protocol.Window
import sh.ahmd.wallflower.protocol.WireJson

private val TMUX = Mux("tmux", "3.8")

/** A session against gateways the test plays, one per connection, over pipes. */
class SessionTest {
    /** What the phone sent: a message, or text typed into a pane. */
    private sealed interface Sent {
        data class Message(val m: ClientMessage) : Sent
        data class Typed(val pane: String, val text: String) : Sent
    }

    /** The far end of one connection. */
    private class Gateway {
        private val fromPhone = PipedInputStream(1 shl 16)
        private val toPhone = PipedOutputStream()
        val connection = Connection(PipedInputStream(toPhone, 1 shl 16), PipedOutputStream(fromPhone)) { close() }
        private val input = DataInputStream(fromPhone)

        fun next(): Sent {
            val length = input.readInt()
            val type = input.readUnsignedByte()
            val payload = ByteArray(length - 1).also(input::readFully)
            return when (type) {
                FRAME_JSON -> Sent.Message(WireJson.decodeFromString(ClientMessage.serializer(), payload.decodeToString()))
                FRAME_DATA -> {
                    val n = payload[0].toInt()
                    Sent.Typed(payload.decodeToString(1, 1 + n), payload.decodeToString(1 + n, payload.size))
                }
                else -> error("frame type $type")
            }
        }

        /** The next of what the phone sent that [match]es, skipping the rest. */
        fun <T> expect(match: (Sent) -> T?): T {
            while (true) match(next())?.let { return it }
        }

        inline fun <reified M : ClientMessage> message(crossinline match: (M) -> Boolean = { true }): M =
            expect { s -> ((s as? Sent.Message)?.m as? M)?.takeIf(match) }

        fun send(m: ServerMessage) = frame(toPhone, WireJson.encodeToString(ServerMessage.serializer(), m).encodeToByteArray())

        fun close() {
            runCatching { toPhone.close() }
            runCatching { fromPhone.close() }
        }

        private fun frame(out: OutputStream, body: ByteArray) {
            val n = body.size + 1
            out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte(), FRAME_JSON.toByte()))
            out.write(body)
            out.flush()
        }
    }

    private val gateways = LinkedBlockingQueue<Gateway>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = Session({ gateways.poll(5, TimeUnit.SECONDS)?.connection ?: error("no gateway") }, scope, System::currentTimeMillis)

    @After
    fun stop() {
        session.stop()
        scope.cancel()
    }

    private fun connect(): Gateway {
        val g = Gateway()
        gateways.put(g)
        return g
    }

    private val state = State(
        "\$1", "o",
        listOf(Window("@1", 0, "zsh", active = true, panes = listOf(Pane("%1", 0, true, 56, 20, command = "claude", reader = "claude")))),
    )

    private fun <T> eventually(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    @Test
    fun aConnectionSaysHelloOpensThePanesAndAsksForMobileViews() {
        val g = connect()
        session.start(56, 20)
        assertEquals(ClientHello(VERSION, 56, 20), g.message<ClientHello>())
        session.read(setOf("%1"))
        assertEquals(Read("%1", on = true), g.message<Read>())
        g.send(ServerHello(VERSION, TMUX, uploads = "/up"))
        g.send(state)
        assertEquals("%1", g.message<Open>().pane)
        eventually { session.status.first { it is Status.Connected } }
        assertEquals("/up", session.uploadDir.value)
        assertEquals("@1", session.viewing.value)
    }

    @Test
    fun textTheGatewayConfirmedStaysSentAndWhatItDidntComesBack() {
        val g = connect()
        session.start(56, 20)
        g.send(ServerHello(VERSION, TMUX))
        eventually { session.status.first { it is Status.Connected } }

        assertTrue(session.submit("%1", "say just: ok", clear = listOf("C-u")))
        assertEquals(Submit("%1", "say just: ok", enter = true, clear = listOf("C-u")), g.message<Submit>())
        val confirmed = g.message<Ping>()
        g.send(Pong(confirmed.id))
        assertTrue(session.submit("%1", "never confirmed"))
        g.message<Ping>()

        val back = eventually {
            coroutineScope {
                val first = async(start = CoroutineStart.UNDISPATCHED) { session.undelivered.first() } // listening before it's said
                g.close() // dropped before its pong
                first.await()
            }
        }
        assertEquals(Undelivered("%1", "never confirmed"), back)
        assertFalse(session.submit("%1", "while disconnected"))
    }

    @Test
    fun aNewConnectionAsksForItsMobileViewsAndOpensItsPanesAgain() {
        val first = connect()
        session.start(56, 20)
        session.read(setOf("%1"))
        first.send(ServerHello(VERSION, TMUX))
        first.send(state)
        first.message<Open>()

        val second = connect()
        first.close()
        assertEquals(Read("%1", on = true), second.message<Read>())
        second.send(ServerHello(VERSION, TMUX))
        second.send(state)
        assertEquals("%1", second.message<Open>().pane)
    }

    @Test
    fun aGatewayThatTurnsTheConnectionDownWaitsForTheUser() {
        val g = connect()
        session.start(56, 20)
        g.send(Bye("protocol version 2 not supported"))
        val needs = eventually { session.status.first { it is Status.NeedsUser } } as Status.NeedsUser
        assertEquals("protocol version 2 not supported", (needs.blocked as Blocked.Refused).reason)
    }
}
