package sh.ahmd.wallflower.session

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** A byte stream to a gateway. */
class Connection(val input: InputStream, val output: OutputStream, private val onClose: () -> Unit) : Closeable {
    override fun close() = onClose()
}

fun interface Transport {
    /** Blocks until connected. */
    fun open(): Connection
}

/**
 * Plain TCP to `wallflower listen`, for development: the gateway only binds
 * loopback, reached through `adb reverse tcp:41777 tcp:41777`.
 */
class TcpTransport(private val host: String = "127.0.0.1", private val port: Int = 41777) : Transport {
    override fun open(): Connection {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), 5_000)
        return Connection(socket.getInputStream(), socket.getOutputStream()) { socket.close() }
    }
}
