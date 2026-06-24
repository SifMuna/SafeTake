package app.safetake.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Optional biometric unlock. Stores a *second* copy of the DEK, wrapped (AES-GCM)
 * by an AndroidKeyStore key that can only be used after a successful Class-3
 * (strong) biometric authentication ([KeyGenParameterSpec.Builder.setUserAuthenticationRequired]).
 *
 * The PIN/password path in [KeyManager] is unchanged and remains the source of
 * truth — this is purely a convenience second door to the same DEK. Because it
 * wraps the DEK (not the PIN), changing the PIN does not affect it.
 *
 * Security properties:
 * - The wrapped DEK is useless without the hardware-backed Keystore key, which
 *   is gated behind biometrics and never leaves the TEE/StrongBox.
 * - Enrolling a new fingerprint/face invalidates the Keystore key
 *   ([setInvalidatedByBiometricEnrollment]); the copy then fails to decrypt and
 *   we fall back to the PIN. [decryptCipher] detects this and self-disables.
 */
class BiometricVault(dir: File) {

    private val blobFile = File(dir, "biometric.properties")

    /** True once the user has enabled biometric unlock and a wrapped DEK exists. */
    val isEnabled: Boolean
        get() = blobFile.exists()

    /**
     * Cipher to hand to a biometric prompt in order to ENABLE biometric unlock.
     * (Re)creates the Keystore key. The actual wrap happens in [storeWrappedDek]
     * with the cipher returned by the prompt, so the key's authorization gate is
     * satisfied for that one operation.
     */
    fun encryptCipher(): Cipher {
        val key = generateKey()
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /** Persists the DEK wrapped by the just-authorized [cipher]. Call from the prompt's success callback. */
    fun storeWrappedDek(cipher: Cipher, dek: SecretKey) {
        val wrapped = cipher.doFinal(dek.encoded)
        val props = Properties().apply {
            setProperty("iv", b64(cipher.iv))
            setProperty("wrappedDek", b64(wrapped))
        }
        val tmp = File(blobFile.parentFile, blobFile.name + ".tmp")
        tmp.outputStream().use { props.store(it, null) }
        check(tmp.renameTo(blobFile) || (blobFile.delete() && tmp.renameTo(blobFile))) {
            "failed to persist biometric blob"
        }
    }

    /**
     * Cipher to hand to a biometric prompt in order to UNLOCK, or null if biometric
     * unlock is unavailable (never enabled, or the Keystore key was invalidated by a
     * biometric enrollment change — in which case this self-disables so the UI stops
     * offering it).
     */
    fun decryptCipher(): Cipher? {
        val props = loadProps() ?: return null
        val key = loadKey() ?: run { disable(); return null }
        val iv = b64(props.getProperty("iv") ?: return null)
        return try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            disable()
            null
        }
    }

    /** Recovers the DEK using the just-authorized [cipher]. Call from the prompt's success callback. */
    fun recoverDek(cipher: Cipher): SecretKey {
        val wrapped = b64(loadProps()!!.getProperty("wrappedDek"))
        return SecretKeySpec(cipher.doFinal(wrapped), "AES")
    }

    /** Turns biometric unlock off: drops the wrapped DEK and the Keystore key. */
    fun disable() {
        blobFile.delete()
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private fun generateKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(DEK_BYTES * 8)
            .setUserAuthenticationRequired(true)
            // Require a fresh strong-biometric auth for every use (timeout 0 = per-op).
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(spec)
        }.generateKey()
    }

    private fun loadKey(): SecretKey? =
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.getKey(KEY_ALIAS, null) as? SecretKey

    private fun loadProps(): Properties? {
        if (!blobFile.exists()) return null
        return Properties().apply { blobFile.inputStream().use(::load) }
    }

    private fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)
    private fun b64(data: String): ByteArray = Base64.getDecoder().decode(data)

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "safetake_biometric_dek"
        private const val DEK_BYTES = 32
        private const val GCM_TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
