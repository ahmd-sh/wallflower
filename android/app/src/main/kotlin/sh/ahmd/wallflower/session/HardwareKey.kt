package sh.ahmd.wallflower.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import com.jcraft.jsch.Identity
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * The phone's SSH key: ECDSA P-256 generated inside Android Keystore,
 * StrongBox-backed where the device has one. The private key can't leave the
 * secure hardware. Signing needs the device unlocked, and the user's
 * fingerprint or screen lock (unlocking the phone counts) within the time
 * it was made with ([unlockSeconds]); that can't change after.
 */
class HardwareKey(private val alias: String = "wallflower-ssh") {
    private val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    val exists: Boolean get() = keystore.containsAlias(alias)

    /** Set when signing failed because the user hadn't authenticated recently. */
    @Volatile var locked = false
        private set

    /** How long an unlock lets it sign, as it was made; null without one. */
    val unlockSeconds: Int?
        get() = if (!exists) null else KeyFactory.getInstance(privateKey.algorithm, "AndroidKeyStore")
            .getKeySpec(privateKey, KeyInfo::class.java).userAuthenticationValidityDurationSeconds

    /** Makes the key, in place of any before, usable for [seconds] after an unlock. Throws IllegalStateException if the device has no secure screen lock. */
    fun generate(seconds: Int = AUTH_SECONDS) {
        if (exists) keystore.deleteEntry(alias)
        try {
            generate(seconds, strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(seconds, strongBox = false)
        }
    }

    private fun generate(seconds: Int, strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(seconds, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
    }

    private val privateKey get() = keystore.getKey(alias, null) as PrivateKey
    private val publicKey get() = keystore.getCertificate(alias).publicKey as ECPublicKey

    /** SSH wire format: string "ecdsa-sha2-nistp256", string "nistp256", string Q. */
    val publicKeyBlob: ByteArray
        get() {
            val w = publicKey.w
            val q = byteArrayOf(4) + unsigned(w.affineX, 32) + unsigned(w.affineY, 32)
            return sshStrings(ALG.encodeToByteArray(), "nistp256".encodeToByteArray(), q)
        }

    fun authorizedKey(comment: String) = "$ALG ${Base64.getEncoder().encodeToString(publicKeyBlob)} $comment"

    val fingerprint: String get() = fingerprint(publicKeyBlob)

    /** True if the next signature needs the user to authenticate first. */
    fun needsAuth(): Boolean = try {
        Signature.getInstance("SHA256withECDSA").initSign(privateKey)
        false
    } catch (_: UserNotAuthenticatedException) {
        true
    }

    /** An SSH signature blob: string alg, string (mpint r, mpint s). */
    fun sign(data: ByteArray): ByteArray? = try {
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }
        locked = false
        val (r, s) = derToIntegers(der)
        sshStrings(ALG.encodeToByteArray(), sshStrings(r, s))
    } catch (_: UserNotAuthenticatedException) {
        locked = true
        null
    }

    fun identity(): Identity = object : Identity {
        override fun setPassphrase(passphrase: ByteArray?) = true
        override fun getPublicKeyBlob() = this@HardwareKey.publicKeyBlob
        override fun getSignature(data: ByteArray) = sign(data)
        override fun getSignature(data: ByteArray, alg: String?) = sign(data)
        @Deprecated("JSch API") override fun decrypt() = true
        override fun getAlgName() = ALG
        override fun getName() = alias
        override fun isEncrypted() = false
        override fun clear() = Unit
    }

    companion object {
        const val ALG = "ecdsa-sha2-nistp256"
        const val AUTH_SECONDS = 300

        /** OpenSSH's format, as ssh-keygen -l prints it. */
        fun fingerprint(blob: ByteArray): String =
            "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

        private fun unsigned(n: BigInteger, size: Int): ByteArray {
            val b = n.toByteArray()
            return when {
                b.size == size -> b
                b.size > size -> b.copyOfRange(b.size - size, b.size)
                else -> ByteArray(size - b.size) + b
            }
        }

        internal fun sshStrings(vararg parts: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            for (p in parts) {
                out.write(p.size ushr 24)
                out.write(p.size ushr 16)
                out.write(p.size ushr 8)
                out.write(p.size)
                out.write(p)
            }
            return out.toByteArray()
        }

        /**
         * DER `SEQUENCE { INTEGER r, INTEGER s }` to r and s. DER integers are
         * already minimal two's complement, which is exactly SSH's mpint body.
         */
        internal fun derToIntegers(der: ByteArray): Pair<ByteArray, ByteArray> {
            var i = 0
            fun length(): Int {
                val b = der[i++].toInt() and 0xff
                if (b < 0x80) return b
                var n = 0
                repeat(b and 0x7f) { n = (n shl 8) or (der[i++].toInt() and 0xff) }
                return n
            }
            fun integer(): ByteArray {
                check(der[i++] == 0x02.toByte()) { "expected DER INTEGER" }
                val n = length()
                return der.copyOfRange(i, i + n).also { i += n }
            }
            check(der[i++] == 0x30.toByte()) { "expected DER SEQUENCE" }
            length()
            return integer() to integer()
        }
    }
}
