package sh.ahmd.wallflower.session

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.Identity
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * A code `wallflower pair` shows, read from its link: where the computer is,
 * who to sign in as and what it's called, its host key, and the seed of a
 * key of the code's own, which the computer lets in for a few minutes to
 * claim with, once.
 */
class Pairing(
    val address: String,
    val port: Int,
    val user: String,
    val machine: String?,
    val hostKey: ByteArray,
    val seed: ByteArray,
) {
    /** What to call the computer: its name, or else its address. */
    val called: String get() = machine ?: address

    /** The computer to connect to, once paired. */
    val host: Host get() = Host(address = address, port = port, user = user, machine = machine)

    /**
     * Signs in with the code's key, to the computer the code came from only,
     * and gives `wallflower claim` this phone's [authorizedKey] line: it lets
     * the phone in, and the code's key out. Returns why it couldn't.
     */
    suspend fun claim(authorizedKey: String): String? = withContext(Dispatchers.IO) {
        val pin = Pin(hostKey)
        val jsch = JSch()
        jsch.addIdentity(identity(), null)
        jsch.hostKeyRepository = pin
        val session = jsch.getSession(user, address, port)
        session.setConfig("PreferredAuthentications", "publickey")
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("server_host_key", HostKeys.algorithms(HostKeys.typeOf(hostKey).orEmpty()))
        try {
            session.connect(10_000)
        } catch (e: JSchException) {
            return@withContext when {
                pin.other -> "What answered at $address isn't the computer the code came from."
                e.message?.contains("Auth fail") == true -> USED
                e.cause is IOException || e.message?.startsWith("timeout") == true ->
                    "Couldn't reach $called over Tailscale: check it's on, on this phone and the computer."
                else -> "Couldn't pair: ${e.message}"
            }
        }
        try {
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand("wallflower claim")
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            channel.setOutputStream(out)
            channel.setErrStream(err)
            channel.connect(10_000)
            channel.getOutputStream().use { it.write("$authorizedKey\n".encodeToByteArray()) }
            withTimeoutOrNull(15_000) { while (!channel.isClosed) delay(50) }
                ?: return@withContext "The computer stopped answering: try again."
            if (channel.exitStatus == 0) null else said(err.toString()) ?: "The computer didn't take this phone's key."
        } catch (_: JSchException) {
            "The connection dropped: try again."
        } catch (_: IOException) {
            "The connection dropped: try again."
        } finally {
            session.disconnect()
        }
    }

    /** The code's key, as JSch signs in with it. */
    internal fun identity(): Identity {
        val key = Ed25519PrivateKeyParameters(seed, 0)
        val blob = HardwareKey.sshStrings(ED25519.encodeToByteArray(), key.generatePublicKey().encoded)
        return object : Identity {
            override fun setPassphrase(passphrase: ByteArray?) = true
            override fun getPublicKeyBlob() = blob
            override fun getSignature(data: ByteArray): ByteArray {
                val signer = Ed25519Signer().apply {
                    init(true, key)
                    update(data, 0, data.size)
                }
                return HardwareKey.sshStrings(ED25519.encodeToByteArray(), signer.generateSignature())
            }
            override fun getSignature(data: ByteArray, alg: String?) = getSignature(data)
            @Deprecated("JSch API") override fun decrypt() = true
            override fun getAlgName() = ED25519
            override fun getName() = "wallflower pairing code"
            override fun isEncrypted() = false
            override fun clear() = Unit
        }
    }

    /** Trusts the one host key the code named; [other] is set when the computer showed another. */
    private class Pin(private val key: ByteArray) : HostKeyRepository {
        var other = false

        override fun check(host: String, key: ByteArray): Int {
            if (key.contentEquals(this.key)) return HostKeyRepository.OK
            other = true
            return HostKeyRepository.CHANGED
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID() = "wallflower pairing"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    companion object {
        private const val ED25519 = "ssh-ed25519"

        /** Why signing in with a code failed: it ran out, or a phone used it. */
        const val USED = "The code has run out or been used: run wallflower pair on the computer for a new one."

        /** The code [link] is (`wallflower://pair?h=…&u=…[&p=…][&n=…]&k=…&c=…`); null if it isn't a whole one. */
        fun parse(link: String): Pairing? {
            val uri = try {
                URI(link.trim())
            } catch (_: URISyntaxException) {
                return null
            }
            if (!uri.scheme.equals("wallflower", ignoreCase = true) || !uri.host.equals("pair", ignoreCase = true)) return null
            val values = try {
                uri.rawQuery?.split('&')?.associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8) }
            } catch (_: IllegalArgumentException) {
                null
            } ?: return null
            val address = values["h"]?.takeIf { h -> h.isNotEmpty() && h.none(Char::isWhitespace) } ?: return null
            val user = values["u"]?.takeIf { it.isNotBlank() } ?: return null
            val port = values["p"]?.let { p -> p.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null } ?: 22
            val hostKey = bytes(values["k"])?.takeIf { HostKeys.typeOf(it) != null } ?: return null
            val seed = bytes(values["c"])?.takeIf { it.size == 32 } ?: return null
            return Pairing(address, port, user, values["n"]?.takeIf { it.isNotBlank() }, hostKey, seed)
        }

        private fun bytes(base64url: String?): ByteArray? = try {
            base64url?.let { Base64.getUrlDecoder().decode(it) }
        } catch (_: IllegalArgumentException) {
            null
        }

        /** What `wallflower` said went wrong, on its last line, without its name. */
        private fun said(stderr: String): String? = stderr.lines().lastOrNull { it.isNotBlank() }?.trim()?.removePrefix("wallflower: ")
            ?.replaceFirstChar(Char::uppercaseChar)?.let { if (it.last() in ".!?") it else "$it." }
    }
}
