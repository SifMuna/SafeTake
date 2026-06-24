package app.safetake.crypto

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Key hierarchy: a random 256-bit DEK encrypts all media. The DEK is wrapped (AES-GCM)
 * by a KEK derived from the user's PIN via PBKDF2. Wrong-PIN detection is the GCM auth
 * tag failing on unwrap — no PIN hash is stored.
 */
class KeyManager(dir: File) {

    private val storeFile = File(dir, "keystore.properties")

    val isInitialized: Boolean
        get() = storeFile.exists()

    /** First-run setup: creates a fresh DEK wrapped with [pin] and returns it unlocked. */
    fun initialize(pin: CharArray): SecretKey {
        val dek = ByteArray(DEK_BYTES).also { SecureRandom().nextBytes(it) }
        writeStore(pin, dek)
        return SecretKeySpec(dek, "AES")
    }

    /** Returns the DEK, or null if [pin] is wrong. */
    fun unlock(pin: CharArray): SecretKey? {
        val props = Properties()
        storeFile.inputStream().use(props::load)
        val salt = b64(props.getProperty("salt"))
        val iterations = props.getProperty("iterations").toInt()
        val iv = b64(props.getProperty("dekIv"))
        val wrapped = b64(props.getProperty("wrappedDek"))
        val kek = deriveKek(pin, salt, iterations)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, iv))
            SecretKeySpec(cipher.doFinal(wrapped), "AES")
        } catch (_: AEADBadTagException) {
            null
        }
    }

    /** Rewraps the existing DEK under a new PIN; media files are untouched. */
    fun changePin(oldPin: CharArray, newPin: CharArray): Boolean {
        val dek = unlock(oldPin) ?: return false
        writeStore(newPin, dek.encoded)
        return true
    }

    private fun writeStore(pin: CharArray, dek: ByteArray) {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(GCM_IV_BYTES).also(random::nextBytes)
        val kek = deriveKek(pin, salt, ITERATIONS)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, iv))
        val wrapped = cipher.doFinal(dek)

        val props = Properties()
        props.setProperty("version", "1")
        props.setProperty("salt", b64(salt))
        props.setProperty("iterations", ITERATIONS.toString())
        props.setProperty("dekIv", b64(iv))
        props.setProperty("wrappedDek", b64(wrapped))
        val tmp = File(storeFile.parentFile, storeFile.name + ".tmp")
        tmp.outputStream().use { props.store(it, null) }
        check(tmp.renameTo(storeFile) || (storeFile.delete() && tmp.renameTo(storeFile))) {
            "failed to persist key store"
        }
    }

    private fun deriveKek(pin: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(pin, salt, iterations, DEK_BYTES * 8)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return try {
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)
    private fun b64(data: String): ByteArray = Base64.getDecoder().decode(data)

    companion object {
        const val ITERATIONS = 300_000
        private const val DEK_BYTES = 32
        private const val SALT_BYTES = 16
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
