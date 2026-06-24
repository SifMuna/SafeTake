package app.safetake.crypto

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Per-file vault format.
 *
 * **v2 (current, magic `STVAULT2`)** splits the plaintext into independent
 * GCM-encrypted chunks so files of any size encrypt/decrypt in bounded memory.
 * Conscrypt's single-shot AES-GCM buffers the entire message and hard-fails just
 * past ~64 MiB, which silently dropped longer videos — chunking sidesteps that.
 *
 * ```
 * magic[8]  then, repeated until EOF, frames of:
 *   ctLen[4 BE]  iv[12]  ciphertext+tag[ctLen]
 * ```
 *
 * Each chunk holds up to [CHUNK] plaintext bytes, encrypted with a fresh random
 * IV and AAD = `chunkIndex[8 BE] ++ isFinal[1]`. The index pins chunk order
 * (reordering fails the tag) and the final-flag pins the end (truncating the tail
 * makes the new last chunk authenticate as non-final → failure), so v2 keeps the
 * whole-file integrity the old single-GCM format had.
 *
 * **v1 (legacy, magic `STVAULT1`)**: `magic[8] iv[12]` then one GCM stream. Still
 * read for media written by older builds; never written anymore.
 */
object VaultCipher {

    private val MAGIC_V1 = "STVAULT1".toByteArray(Charsets.US_ASCII)
    private val MAGIC_V2 = "STVAULT2".toByteArray(Charsets.US_ASCII)
    private const val MAGIC_LEN = 8
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = 16
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    // Plaintext bytes per chunk. Far under Conscrypt's ~64 MiB single-op ceiling,
    // and small enough that peak memory stays a few MiB even for long videos.
    private const val CHUNK = 1024 * 1024

    /** Wraps [output] so everything written to the result is chunk-encrypted into it. */
    fun encryptingStream(key: SecretKey, output: OutputStream): OutputStream {
        output.write(MAGIC_V2)
        return ChunkedEncryptingStream(key, output)
    }

    /** Wraps [input] (positioned at the start of a vault file) for decryption, v1 or v2. */
    fun decryptingStream(key: SecretKey, input: InputStream): InputStream {
        val magic = readFully(input, MAGIC_LEN)
        return when {
            magic.contentEquals(MAGIC_V2) -> ChunkedDecryptingStream(key, input)
            magic.contentEquals(MAGIC_V1) -> legacyDecryptingStream(key, input)
            else -> throw IOException("not a SafeTake vault file")
        }
    }

    fun encrypt(key: SecretKey, plaintext: ByteArray, output: OutputStream) {
        encryptingStream(key, output).use { it.write(plaintext) }
    }

    fun decrypt(key: SecretKey, input: InputStream): ByteArray =
        decryptingStream(key, input).use { it.readBytes() }

    // ---- v2: chunked ----

    private fun aad(index: Long, isFinal: Boolean): ByteArray {
        val a = ByteArray(9)
        for (i in 0 until 8) a[i] = (index ushr (8 * (7 - i))).toByte()
        a[8] = if (isFinal) 1 else 0
        return a
    }

    /**
     * Buffers up to [CHUNK] plaintext bytes, emitting a frame whenever the buffer
     * fills with more data still to come; [close] flushes the remainder as the
     * final chunk. Holding a full buffer until more arrives is what lets the true
     * last chunk be marked final.
     */
    private class ChunkedEncryptingStream(
        private val key: SecretKey,
        private val output: OutputStream,
    ) : OutputStream() {

        private val cipher = Cipher.getInstance(TRANSFORMATION)
        private val random = SecureRandom()
        private val buf = ByteArray(CHUNK)
        private var count = 0
        private var index = 0L
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var remaining = len
            while (remaining > 0) {
                if (count == CHUNK) emit(isFinal = false) // buffer full and more is coming
                val n = minOf(CHUNK - count, remaining)
                System.arraycopy(b, o, buf, count, n)
                count += n
                o += n
                remaining -= n
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            emit(isFinal = true) // flush trailing bytes (a partial, full, or empty buffer)
            output.close()
        }

        private fun emit(isFinal: Boolean) {
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad(index, isFinal))
            val ct = cipher.doFinal(buf, 0, count)
            writeInt(output, ct.size)
            output.write(iv)
            output.write(ct)
            index++
            count = 0
        }
    }

    /**
     * Yields plaintext one chunk at a time. Reads one frame ahead so it can tell
     * whether the chunk it is about to decrypt is the final one (needed for the
     * AAD) — which is also what surfaces a truncated tail as an auth failure.
     */
    private class ChunkedDecryptingStream(
        private val key: SecretKey,
        private val input: InputStream,
    ) : InputStream() {

        private val cipher = Cipher.getInstance(TRANSFORMATION)
        private var outBuf = ByteArray(0)
        private var pos = 0
        private var index = 0L
        private var nextLen = UNSTARTED
        private var done = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos == outBuf.size) {
                if (done) return -1
                fill()
            }
            val n = minOf(len, outBuf.size - pos)
            System.arraycopy(outBuf, pos, b, off, n)
            pos += n
            return n
        }

        private fun fill() {
            pos = 0
            if (nextLen == UNSTARTED) {
                nextLen = readLen()
                if (nextLen < 0) { done = true; outBuf = ByteArray(0); return } // no frames
            }
            val ctLen = nextLen
            if (ctLen < TAG_BYTES) throw IOException("corrupt vault frame")
            val iv = readFrame(IV_BYTES)
            val ct = readFrame(ctLen)
            val peek = readLen()
            val isFinal = peek < 0
            outBuf = try {
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                cipher.updateAAD(aad(index, isFinal))
                cipher.doFinal(ct)
            } catch (e: GeneralSecurityException) {
                throw IOException("vault file failed authentication", e)
            }
            index++
            if (isFinal) done = true else nextLen = peek
        }

        /** Reads a 4-byte big-endian length, or -1 at a clean end of stream. */
        private fun readLen(): Int {
            val b0 = input.read()
            if (b0 < 0) return -1
            val b1 = input.read(); val b2 = input.read(); val b3 = input.read()
            if (b1 < 0 || b2 < 0 || b3 < 0) throw IOException("truncated vault frame")
            return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }

        private fun readFrame(n: Int): ByteArray {
            val out = readFully(input, n)
            if (out.size != n) throw IOException("truncated vault frame")
            return out
        }

        override fun close() = input.close()

        companion object { private const val UNSTARTED = -2 }
    }

    private fun writeInt(out: OutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    // ---- v1: legacy single-GCM (read-only) ----

    private fun legacyDecryptingStream(key: SecretKey, input: InputStream): InputStream {
        val iv = readFully(input, IV_BYTES)
        if (iv.size != IV_BYTES) throw IOException("truncated vault header")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return LegacyDecryptingInputStream(input, cipher)
    }

    /** Reads up to [n] bytes, returning fewer only at end of stream. */
    private fun readFully(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val read = input.read(buf, off, n - off)
            if (read < 0) return buf.copyOf(off)
            off += read
        }
        return buf
    }

    /**
     * Streams a legacy single-GCM body. Depending on the provider, plaintext may
     * arrive incrementally from update() or all at once from doFinal() (Conscrypt
     * AEAD); both are handled. Only used for files written by old builds.
     */
    private class LegacyDecryptingInputStream(
        private val input: InputStream,
        private val cipher: Cipher,
    ) : InputStream() {

        private val inBuf = ByteArray(512 * 1024)
        private var outBuf = ByteArray(0)
        private var pos = 0
        private var done = false

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos == outBuf.size) {
                if (done) return -1
                fill()
            }
            val n = minOf(len, outBuf.size - pos)
            System.arraycopy(outBuf, pos, b, off, n)
            pos += n
            return n
        }

        private fun fill() {
            pos = 0
            val n = input.read(inBuf)
            outBuf = try {
                if (n < 0) { done = true; cipher.doFinal() ?: ByteArray(0) }
                else cipher.update(inBuf, 0, n) ?: ByteArray(0)
            } catch (e: GeneralSecurityException) {
                throw IOException("vault file failed authentication", e)
            }
        }

        override fun close() = input.close()
    }
}
