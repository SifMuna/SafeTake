package app.safetake.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VaultCipherTest {

    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val chunk = 1024 * 1024

    private fun roundTrip(plaintext: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        VaultCipher.encrypt(key, plaintext, out)
        return VaultCipher.decrypt(key, ByteArrayInputStream(out.toByteArray()))
    }

    @Test
    fun roundTripSmall() {
        val data = "hello vault".toByteArray()
        assertArrayEquals(data, roundTrip(data))
    }

    @Test
    fun roundTripEmpty() {
        assertArrayEquals(ByteArray(0), roundTrip(ByteArray(0)))
    }

    @Test
    fun roundTripLargerThanChunk() {
        // spans several chunks and is not chunk-aligned
        val data = Random(42).nextBytes(5 * chunk + 12345)
        assertArrayEquals(data, roundTrip(data))
    }

    @Test
    fun roundTripExactChunkMultiple() {
        // boundary case: the last chunk is exactly full, with nothing trailing
        val data = Random(99).nextBytes(3 * chunk)
        assertArrayEquals(data, roundTrip(data))
    }

    @Test
    fun streamingRoundTrip() {
        val data = Random(7).nextBytes(2 * 1024 * 1024)
        val out = ByteArrayOutputStream()
        VaultCipher.encryptingStream(key, out).use { enc ->
            data.toList().chunked(100_000).forEach { enc.write(it.toByteArray()) }
        }
        val decrypted = VaultCipher.decryptingStream(key, ByteArrayInputStream(out.toByteArray()))
            .use { it.readBytes() }
        assertArrayEquals(data, decrypted)
    }

    @Test
    fun tamperedCiphertextFailsAuthentication() {
        val out = ByteArrayOutputStream()
        VaultCipher.encrypt(key, Random(1).nextBytes(100_000), out)
        val bytes = out.toByteArray()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        assertThrows(IOException::class.java) {
            VaultCipher.decrypt(key, ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun wrongKeyFailsAuthentication() {
        val out = ByteArrayOutputStream()
        VaultCipher.encrypt(key, "secret".toByteArray(), out)
        val otherKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        assertThrows(IOException::class.java) {
            VaultCipher.decrypt(otherKey, ByteArrayInputStream(out.toByteArray()))
        }
    }

    @Test
    fun rejectsNonVaultFile() {
        assertThrows(IOException::class.java) {
            VaultCipher.decrypt(key, ByteArrayInputStream("not a vault file at all".toByteArray()))
        }
    }

    @Test
    fun truncatedTailFailsAuthentication() {
        // a multi-chunk file whose tail has been lopped off must not decode silently
        val out = ByteArrayOutputStream()
        VaultCipher.encrypt(key, Random(5).nextBytes(2 * chunk + 500), out)
        val truncated = out.toByteArray().copyOf(out.size() - 40)
        assertThrows(IOException::class.java) {
            VaultCipher.decrypt(key, ByteArrayInputStream(truncated))
        }
    }

    @Test
    fun legacyV1FileStillDecrypts() {
        // files written by older builds (STVAULT1: magic + iv + one GCM body) must still open
        val data = Random(11).nextBytes(40_000)
        assertArrayEquals(data, VaultCipher.decrypt(key, ByteArrayInputStream(legacyV1Blob(data))))
    }

    /** Builds a legacy STVAULT1 blob the way pre-chunking builds wrote files. */
    private fun legacyV1Blob(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(12) { (it + 3).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return ByteArrayOutputStream().apply {
            write("STVAULT1".toByteArray(Charsets.US_ASCII))
            write(iv)
            write(cipher.doFinal(plaintext))
        }.toByteArray()
    }
}
