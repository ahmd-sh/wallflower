package sh.ahmd.wallflower.session

import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {
    // RFC 8032's first Ed25519 test vector: the seed, and the public key it makes.
    private val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    private val public = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    private val hostKey = HardwareKey.sshStrings("ssh-ed25519".encodeToByteArray(), ByteArray(32) { it.toByte() })
    private val k = b64(hostKey)
    private val c = b64(seed)

    @Test
    fun readsACode() {
        val p = Pairing.parse("wallflower://pair?h=100.101.102.103&u=ahmed&p=2222&n=Ahmed%27s%20MacBook%20Pro&k=$k&c=$c")!!
        assertEquals("100.101.102.103", p.address)
        assertEquals(2222, p.port)
        assertEquals("ahmed", p.user)
        assertEquals("Ahmed's MacBook Pro", p.machine)
        assertArrayEquals(hostKey, p.hostKey)
        assertArrayEquals(seed, p.seed)
        assertEquals("Ahmed's MacBook Pro", p.called)
        assertEquals(Host(address = "100.101.102.103", port = 2222, user = "ahmed", machine = "Ahmed's MacBook Pro"), p.host)
    }

    @Test
    fun portAndNameAreOptional() {
        val p = Pairing.parse("wallflower://pair?h=mac.tail1234.ts.net&u=me&k=$k&c=$c")!!
        assertEquals(22, p.port)
        assertNull(p.machine)
        assertEquals("mac.tail1234.ts.net", p.called)
    }

    @Test
    fun turnsDownWhatIsntAWholeCode() {
        val good = "h=100.1.2.3&u=me&k=$k&c=$c"
        assertNotNull(Pairing.parse("wallflower://pair?$good"))
        for (link in listOf(
            "https://pair?$good",
            "wallflower://unpair?$good",
            "wallflower://pair",
            "wallflower://pair?u=me&k=$k&c=$c",
            "wallflower://pair?h=100.1.2.3&k=$k&c=$c",
            "wallflower://pair?h=100.1.2.3&u=me&c=$c",
            "wallflower://pair?h=100.1.2.3&u=me&k=$k",
            "wallflower://pair?h=&u=me&k=$k&c=$c",
            "wallflower://pair?$good&p=0",
            "wallflower://pair?$good&p=65536",
            "wallflower://pair?$good&p=ssh",
            "wallflower://pair?h=100.1.2.3&u=me&k=$k&c=${b64(seed.copyOf(31))}",
            "wallflower://pair?h=100.1.2.3&u=me&k=$k&c=${b64(seed + 0.toByte())}",
            "wallflower://pair?h=100.1.2.3&u=me&k=$k&c=not*base64",
            "wallflower://pair?h=100.1.2.3&u=me&k=${b64(byteArrayOf(0, 0, 0, 40, 1))}&c=$c",
            "wallflower://pair?h=100.1.2.3&u=%zz&k=$k&c=$c",
            "not a link at all",
        )) {
            assertNull(link, Pairing.parse(link))
        }
    }

    @Test
    fun theCodesKeySignsAsEd25519() {
        val identity = Pairing("100.1.2.3", 22, "me", null, hostKey, seed).identity()
        assertEquals("ssh-ed25519", identity.algName)
        assertArrayEquals(HardwareKey.sshStrings("ssh-ed25519".encodeToByteArray(), public), identity.publicKeyBlob)

        val data = "session id and request".encodeToByteArray()
        val signature = identity.getSignature(data, "ssh-ed25519")
        val alg = signature.copyOfRange(4, 4 + 11)
        assertEquals("ssh-ed25519", String(alg))
        val raw = signature.copyOfRange(4 + 11 + 4, signature.size)
        assertEquals(64, raw.size)
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(public, 0))
            update(data, 0, data.size)
        }
        assertTrue(verifier.verifySignature(raw))
    }

    @Test
    fun hostKeysByJSchsName() {
        assertEquals("100.1.2.3", HostKeys.name("100.1.2.3", 22))
        assertEquals("[100.1.2.3]:2222", HostKeys.name("100.1.2.3", 2222))
        assertEquals("ssh-ed25519", HostKeys.typeOf(hostKey))
        assertNull(HostKeys.typeOf(byteArrayOf(0, 0, 0)))
        assertNull(HostKeys.typeOf(byteArrayOf(0, 0, 0, 9, 'a'.code.toByte())))
        assertEquals("ssh-ed25519", HostKeys.algorithms("ssh-ed25519"))
        assertEquals("rsa-sha2-512,rsa-sha2-256", HostKeys.algorithms("ssh-rsa"))
    }

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
