package sh.ahmd.wallflower.session

import android.content.SharedPreferences
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UserInfo
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

/** Why a connection can't go ahead without the user. */
sealed class Blocked(message: String) : Exception(message) {
    /** The key needs the user's fingerprint or screen lock. */
    class KeyLocked : Blocked("unlock your key to connect")

    /** First connection to this host: confirm its fingerprint. */
    class UnknownHost(val host: String, val type: String, val key: ByteArray) :
        Blocked("new host key ${HardwareKey.fingerprint(key)}") {
        val fingerprint get() = HardwareKey.fingerprint(key)
    }

    /** The host's key differs from the trusted one. */
    class HostKeyChanged(val host: String, val fingerprint: String) : Blocked("host key changed: $fingerprint")

    /** The Mac doesn't accept this phone's key yet. */
    class NotAuthorized : Blocked("this phone's key isn't authorized on the Mac")
}

/** Connects with the hardware key and runs the gateway in an exec channel. */
class SshTransport(
    private val host: Host,
    private val key: HardwareKey,
    private val hostKeys: HostKeys,
) : Transport {
    override fun open(): Connection {
        if (key.needsAuth()) throw Blocked.KeyLocked()
        val pins = hostKeys.repository()
        val jsch = JSch()
        jsch.addIdentity(key.identity(), null)
        jsch.hostKeyRepository = pins
        val session = jsch.getSession(host.user, host.address, host.port)
        session.setSocketFactory(NoDelay)
        session.setConfig("PreferredAuthentications", "publickey")
        session.setConfig("StrictHostKeyChecking", "yes")
        try {
            session.connect(10_000)
        } catch (e: JSchException) {
            pins.unknown?.let { throw it }
            pins.changed?.let { throw it }
            if (key.locked) throw Blocked.KeyLocked()
            if (e.message?.contains("Auth fail") == true) throw Blocked.NotAuthorized()
            throw e
        }
        val channel = session.openChannel("exec") as ChannelExec
        // The Mac's authorized_keys forces `wallflower serve` for this key;
        // the command matters only for keys without that restriction.
        channel.setCommand(host.command)
        val input = channel.inputStream
        val output = channel.outputStream
        channel.connect(10_000)
        return Connection(input, output) {
            channel.disconnect()
            session.disconnect()
        }
    }

    private object NoDelay : SocketFactory {
        override fun createSocket(host: String, port: Int): Socket = Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(host, port), 10_000)
        }

        override fun getInputStream(socket: Socket) = socket.getInputStream()
        override fun getOutputStream(socket: Socket) = socket.getOutputStream()
    }
}

/** Trusted host keys, one per host, pinned on first use after the user confirms. */
class HostKeys(private val prefs: SharedPreferences) {
    fun trust(blocked: Blocked.UnknownHost) {
        prefs.edit().putString(KEY_PREFIX + blocked.host, blocked.type + " " + Base64.getEncoder().encodeToString(blocked.key)).apply()
    }

    fun forget(host: String) = prefs.edit().remove(KEY_PREFIX + host).apply()

    internal fun repository() = Repository()

    inner class Repository : HostKeyRepository {
        var unknown: Blocked.UnknownHost? = null
        var changed: Blocked.HostKeyChanged? = null

        override fun check(host: String, key: ByteArray): Int {
            val pinned = prefs.getString(KEY_PREFIX + host, null)
            val type = String(key, 4, ((key[0].toInt() and 0xff) shl 24) or ((key[1].toInt() and 0xff) shl 16) or
                ((key[2].toInt() and 0xff) shl 8) or (key[3].toInt() and 0xff))
            return when {
                pinned == null -> {
                    unknown = Blocked.UnknownHost(host, type, key)
                    HostKeyRepository.NOT_INCLUDED
                }
                pinned == type + " " + Base64.getEncoder().encodeToString(key) -> HostKeyRepository.OK
                else -> {
                    changed = Blocked.HostKeyChanged(host, HardwareKey.fingerprint(key))
                    HostKeyRepository.CHANGED
                }
            }
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID() = "wallflower"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    private companion object {
        const val KEY_PREFIX = "hostkey:"
    }
}
