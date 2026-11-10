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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import sh.ahmd.wallflower.protocol.Attached
import sh.ahmd.wallflower.protocol.Detach
import sh.ahmd.wallflower.protocol.Detached
import sh.ahmd.wallflower.protocol.MuxSessions
import sh.ahmd.wallflower.protocol.NewSession
import sh.ahmd.wallflower.protocol.SessionInfo
import sh.ahmd.wallflower.protocol.SessionList
import sh.ahmd.wallflower.protocol.ServerMessage
import sh.ahmd.wallflower.protocol.State
import sh.ahmd.wallflower.protocol.Submit
import sh.ahmd.wallflower.protocol.Switch
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
    private val session = Session({ gateways.poll(5, TimeUnit.SECONDS)?.connection ?: error("no gateway") }, scope, clock = System::currentTimeMillis)

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
        listOf(Window("@1", 0, "zsh", active = true, panes = listOf(Pane("%1", 0, true, 56, 20, command = "claude", program = "claude", view = true)))),
    )

    private fun <T> eventually(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    @Test
    fun aConnectionSaysHelloOpensThePanesAndAsksForMobileViews() {
        val g = connect()
        session.start(56, 20)
        assertEquals(ClientHello(VERSION, 56, 20), g.message<ClientHello>())
        session.read(setOf("%1"))
        assertEquals(Read("%1", on = true), g.message<Read>())
        g.send(ServerHello(VERSION, uploads = "/up", machine = "vishingstar"))
        g.send(Attached(TMUX))
        g.send(state)
        assertEquals("%1", g.message<Open>().pane)
        eventually { session.status.first { it is Status.Connected } }
        assertEquals("/up", session.uploadDir.value)
        assertEquals("vishingstar", session.machine.value)
        assertEquals("@1", session.viewing.value)
    }

    @Test
    fun textTheGatewayConfirmedStaysSentAndWhatItDidntComesBack() {
        val g = connect()
        session.start(56, 20)
        g.send(ServerHello(VERSION))
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
        first.send(ServerHello(VERSION))
        first.send(Attached(TMUX))
        first.send(state)
        first.message<Open>()

        val second = connect()
        first.close()
        assertEquals(Read("%1", on = true), second.message<Read>())
        second.send(ServerHello(VERSION))
        second.send(Attached(TMUX))
        second.send(state)
        assertEquals("%1", second.message<Open>().pane)
    }

    /** Connected and in [state]'s session, as the gateway has it. */
    private fun inSession(): Gateway {
        val g = connect()
        session.start(56, 20)
        g.send(ServerHello(VERSION))
        g.send(Attached(TMUX))
        g.send(state)
        g.message<Open>()
        eventually { session.place.first { it == Place("tmux", "\$1") } }
        return g
    }

    /** The gateway done with what the phone asked: the pong for the ping after it. */
    private fun Gateway.caughtUp() = send(Pong(message<Ping>().id))

    @Test
    fun aSwitchIsAnotherSessionOverTheSameConnectionAndWhereTheNextOneGoes() {
        val first = inSession()
        session.switchTo("rex", "s:2")
        assertEquals(Place("rex", "s:2"), session.place.value) // there at once
        assertEquals(Switch("rex", "s:2"), first.message<Switch>())
        first.send(Attached(Mux("rex", "0.1")))
        first.send(State("s:2", "work", listOf(Window("w:1", 1, "Window", active = true, panes = listOf(Pane("b:1", 0, true, 56, 20, command = "zsh"))))))
        first.caughtUp()
        assertEquals("b:1", first.message<Open>().pane)
        assertEquals(Place("rex", "s:2"), session.place.value)
        assertEquals("w:1", session.viewing.value)

        // Connecting again, it goes back there.
        val second = connect()
        first.close()
        assertEquals(ClientHello(VERSION, 56, 20, mux = "rex", session = "s:2"), second.message<ClientHello>())
    }

    @Test
    fun withNowhereToGoItsOnTheMacsSessions() {
        val g = connect()
        session.start(56, 20)
        assertEquals(ClientHello(VERSION, 56, 20), g.message<ClientHello>())
        g.send(ServerHello(VERSION))
        g.send(Detached())
        g.send(SessionList(listOf(MuxSessions("tmux", running = true, sessions = listOf(SessionInfo("\$1", "o", 2))))))
        eventually { session.sessions.first { it != null } }
        assertNull(session.place.value)
        assertNull(session.left.value)
    }

    @Test
    fun leavingTheSessionIsTheUsersAndStaysLeftOnTheNextConnection() {
        val first = inSession()
        session.detach()
        assertNull(session.place.value)
        assertNull(session.state.value) // nothing of it stays
        assertEquals(Detach, first.message<Detach>())
        first.send(Detached())
        first.caughtUp()
        assertNull(session.back.value)

        val second = connect()
        first.close()
        assertEquals(ClientHello(VERSION, 56, 20), second.message<ClientHello>())
    }

    @Test
    fun aSessionThatGoesLeavesThePhoneInNoneSayingWhyButStillGoesBackIfItsThere() {
        val g = inSession()
        g.send(Detached("tmux exited"))
        eventually { session.place.first { it == null } }
        assertEquals("tmux exited", session.left.value)
        assertNull(session.state.value)
        // Not the user's choice: the next connection tries it again (a multiplexer restarting, say).
        assertEquals(Place("tmux", "\$1"), session.back.value)
        val second = connect()
        g.close()
        assertEquals(ClientHello(VERSION, 56, 20, mux = "tmux", session = "\$1"), second.message<ClientHello>())
    }

    @Test
    fun whatTheSessionGoingSwallowedComesBack() {
        val g = inSession()
        assertTrue(session.submit("%1", "lost with it"))
        g.message<Submit>()
        g.message<Ping>()
        val back = eventually {
            coroutineScope {
                val first = async(start = CoroutineStart.UNDISPATCHED) { session.undelivered.first() }
                g.send(Detached("tmux exited"))
                first.await()
            }
        }
        assertEquals(Undelivered("%1", "lost with it"), back)
    }

    @Test
    fun aNewSessionAskedForWhileDisconnectedIsntLeftStarting() {
        session.newSession("tmux", "work")
        assertNull(session.starting.value)
    }

    @Test
    fun theMacMovingThePhoneToAnotherSessionTakesItThere() {
        val g = inSession()
        g.send(state.copy(session = "\$2", name = "other"))
        eventually { session.place.first { it == Place("tmux", "\$2") } }
    }

    @Test
    fun aQuickTapThenBackEndsUpInNone() {
        val g = inSession()
        session.detach()
        g.message<Detach>()
        val left = g.message<Ping>()
        session.switchTo("tmux", "\$2")
        g.message<Switch>()
        val went = g.message<Ping>()
        session.detach()
        g.message<Detach>()
        val back = g.message<Ping>()
        // The gateway, behind: what it says on the way doesn't move the phone.
        g.send(Detached())
        g.send(Pong(left.id))
        g.send(Attached(TMUX))
        g.send(state.copy(session = "\$2"))
        g.send(Pong(went.id))
        assertNull(session.place.value)
        g.send(Detached())
        g.send(Pong(back.id))
        runBlocking { delay(200) }
        assertNull(session.place.value)
    }

    @Test
    fun aSwitchThatFailsLeavesThePhoneInNoneSayingWhy() {
        val g = inSession()
        session.detach()
        g.message<Detach>()
        g.send(Detached())
        g.caughtUp()
        session.switchTo("tmux", "\$9")
        assertEquals(Place("tmux", "\$9"), session.place.value)
        g.message<Switch>()
        g.send(Detached("no tmux session \"\$9\""))
        g.caughtUp()
        eventually { session.place.first { it == null } }
        assertEquals("no tmux session \"\$9\"", session.left.value)
    }

    @Test
    fun aNewSessionIsGoneIntoOnceItsThere() {
        val g = connect()
        session.start(56, 20)
        g.send(ServerHello(VERSION))
        g.send(Detached())
        g.send(SessionList(listOf(MuxSessions("tmux", running = true))))
        eventually { session.sessions.first { it != null } }
        session.newSession("tmux", " work ")
        assertEquals("tmux", session.starting.value)
        assertNull(session.place.value)
        assertEquals(NewSession("tmux", "work"), g.message<NewSession>())
        g.send(Attached(TMUX))
        g.send(state.copy(session = "\$3", name = "work"))
        g.caughtUp()
        eventually { session.place.first { it == Place("tmux", "\$3") } }
        assertNull(session.starting.value)
    }

    @Test
    fun aDroppedConnectionSaysWhyWhenItTriesNextAndSinceWhenItsDown() {
        val first = connect()
        session.start(56, 20)
        first.send(ServerHello(VERSION))
        eventually { session.status.first { it is Status.Connected } }
        first.send(Bye("tmux exited"))
        val down = eventually { session.status.first { it is Status.Disconnected && !it.trying } } as Status.Disconnected
        assertEquals("tmux exited", down.reason)
        assertTrue(down.retryAt > down.since)

        // Trying again (with no gateway yet), it's been down since the same moment.
        val trying = eventually { session.status.first { it is Status.Disconnected && it.trying } } as Status.Disconnected
        assertEquals(down.since, trying.since)
        connect().send(ServerHello(VERSION))
        eventually { session.status.first { it is Status.Connected } }
    }

    @Test
    fun aGatewayOfAnotherProtocolVersionAsksForTheOlderToBeUpdated() {
        val first = connect()
        session.start(56, 20)
        first.send(Bye("protocol version $VERSION not supported (gateway speaks ${VERSION - 1})"))
        val refused = eventually { session.status.first { it is Status.NeedsUser } } as Status.NeedsUser
        val older = refused.blocked as Blocked.Outdated
        assertEquals(VERSION - 1 to VERSION, older.gateway to older.app)

        connect().send(ServerHello(VERSION + 1))
        session.retry()
        val newer = eventually { session.status.first { (it as? Status.NeedsUser)?.blocked?.let { b -> b is Blocked.Outdated && b.gateway == VERSION + 1 } == true } }
        assertEquals(VERSION, ((newer as Status.NeedsUser).blocked as Blocked.Outdated).app)
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
