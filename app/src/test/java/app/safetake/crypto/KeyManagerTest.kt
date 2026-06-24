package app.safetake.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KeyManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `initialize then unlock with correct pin returns same dek`() {
        val km = KeyManager(tmp.root)
        assertFalse(km.isInitialized)
        val dek = km.initialize("123456".toCharArray())
        assertTrue(km.isInitialized)
        val unlocked = km.unlock("123456".toCharArray())
        assertNotNull(unlocked)
        assertArrayEquals(dek.encoded, unlocked!!.encoded)
    }

    @Test
    fun `wrong pin returns null`() {
        val km = KeyManager(tmp.root)
        km.initialize("123456".toCharArray())
        assertNull(km.unlock("654321".toCharArray()))
    }

    @Test
    fun `pin change preserves dek and old pin stops working`() {
        val km = KeyManager(tmp.root)
        val dek = km.initialize("123456".toCharArray())
        assertTrue(km.changePin("123456".toCharArray(), "999999".toCharArray()))
        assertNull(km.unlock("123456".toCharArray()))
        assertArrayEquals(dek.encoded, km.unlock("999999".toCharArray())!!.encoded)
    }

    @Test
    fun `pin change with wrong old pin fails`() {
        val km = KeyManager(tmp.root)
        km.initialize("123456".toCharArray())
        assertFalse(km.changePin("000000".toCharArray(), "999999".toCharArray()))
        assertNotNull(km.unlock("123456".toCharArray()))
    }

    @Test
    fun `vault cipher round trips bytes`() {
        val key = KeyManager(tmp.root).initialize("123456".toCharArray())
        val plain = ByteArray(1 shl 20) { (it * 31).toByte() }
        val out = ByteArrayOutputStream()
        VaultCipher.encrypt(key, plain, out)
        val cipherBytes = out.toByteArray()
        assertEquals('S'.code.toByte(), cipherBytes[0])
        // ciphertext must not contain the plaintext
        assertFalse(cipherBytes.copyOfRange(20, 120).contentEquals(plain.copyOfRange(0, 100)))
        val back = VaultCipher.decrypt(key, ByteArrayInputStream(cipherBytes))
        assertArrayEquals(plain, back)
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val key = KeyManager(tmp.root).initialize("123456".toCharArray())
        val out = ByteArrayOutputStream()
        VaultCipher.encrypt(key, "secret photo".toByteArray(), out)
        val tampered = out.toByteArray()
        tampered[tampered.size - 5] = (tampered[tampered.size - 5] + 1).toByte()
        assertThrows(IOException::class.java) {
            VaultCipher.decrypt(key, ByteArrayInputStream(tampered))
        }
    }

    @Test
    fun `decrypt with wrong key fails`() {
        val key1 = KeyManager(tmp.newFolder()).initialize("123456".toCharArray())
        val key2 = KeyManager(tmp.newFolder()).initialize("123456".toCharArray())
        val out = ByteArrayOutputStream()
        VaultCipher.encrypt(key1, "secret photo".toByteArray(), out)
        assertThrows(IOException::class.java) {
            VaultCipher.decrypt(key2, ByteArrayInputStream(out.toByteArray()))
        }
    }

    @Test
    fun `non vault file is rejected`() {
        val key = KeyManager(tmp.root).initialize("123456".toCharArray())
        assertThrows(IOException::class.java) {
            VaultCipher.decrypt(key, ByteArrayInputStream("plain old jpeg".toByteArray()))
        }
    }
}
