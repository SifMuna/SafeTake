package app.safetake.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import app.safetake.crypto.SessionVault
import app.safetake.crypto.VaultCipher
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class MediaItem(
    val id: String,
    val name: String,
    val mime: String,
    val takenAt: Long,
    val durationMs: Long = 0L,
    val encrypted: Boolean = true,
) {
    val isVideo: Boolean get() = mime.startsWith("video/")
}

/** A file the viewer can play/show directly. Temps must be deleted by the caller. */
data class PlaybackFile(val file: File, val isTemp: Boolean)

/**
 * Media lives under filesDir/media with thumbnails in filesDir/thumbs and an
 * always-encrypted JSON index at filesDir/index.enc. New media is always
 * encrypted (.enc); legacy plaintext items (.raw) from older versions are still
 * read transparently via each item's [MediaItem.encrypted] flag. Every method
 * that touches content requires the vault to be unlocked. Call from Dispatchers.IO.
 */
class MediaRepository(private val context: Context) {

    private val mediaDir = File(context.filesDir, "media").apply { mkdirs() }
    private val thumbsDir = File(context.filesDir, "thumbs").apply { mkdirs() }
    private val indexFile = File(context.filesDir, "index.enc")

    // Process-lifetime scope for must-complete writes (capture/import). Tying these
    // to a screen's coroutine scope means navigating away mid-encrypt aborts the
    // save — which silently dropped longer videos. This scope outlives the UI.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items: StateFlow<List<MediaItem>> = _items

    // ~32 MB of decrypted thumbnails so grid scrolling doesn't re-decrypt; dropped on lock.
    private val thumbCache = object : android.util.LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun mediaFile(item: MediaItem) = mediaFile(item.id, item.encrypted)
    private fun mediaFile(id: String, encrypted: Boolean) =
        File(mediaDir, if (encrypted) "$id.enc" else "$id.raw")

    private fun thumbFile(id: String, encrypted: Boolean) =
        File(thumbsDir, if (encrypted) "$id.enc" else "$id.raw")

    // ---- index ----

    fun loadIndex() {
        if (!indexFile.exists()) {
            _items.value = emptyList()
            return
        }
        val json = indexFile.inputStream().use { VaultCipher.decrypt(SessionVault.key(), it) }
        val array = JSONArray(String(json, Charsets.UTF_8))
        _items.value = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            MediaItem(
                id = o.getString("id"),
                name = o.getString("name"),
                mime = o.getString("mime"),
                takenAt = o.getLong("takenAt"),
                durationMs = o.optLong("durationMs", 0L),
                encrypted = o.optBoolean("enc", true),
            )
        }.sortedByDescending { it.takenAt }
    }

    fun clearLoaded() {
        _items.value = emptyList()
        thumbCache.evictAll()
    }

    private fun saveIndex(items: List<MediaItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("mime", item.mime)
                    .put("takenAt", item.takenAt)
                    .put("durationMs", item.durationMs)
                    .put("enc", item.encrypted)
            )
        }
        val tmp = File(context.filesDir, "index.enc.tmp")
        tmp.outputStream().use {
            VaultCipher.encrypt(SessionVault.key(), array.toString().toByteArray(Charsets.UTF_8), it)
        }
        check(tmp.renameTo(indexFile) || (indexFile.delete() && tmp.renameTo(indexFile))) {
            "failed to persist index"
        }
        _items.value = items.sortedByDescending { it.takenAt }
    }

    // ---- adding media ----

    /**
     * Fire-and-forget capture saves. These run on [ioScope] so a long encrypt
     * (notably for multi-second videos) completes even if the user immediately
     * leaves the camera screen.
     */
    fun savePhotoAsync(jpeg: ByteArray, rotationDegrees: Int) {
        ioScope.launch { runCatching { addPhoto(jpeg, rotationDegrees) } }
    }

    fun saveVideoAsync(temp: File) {
        ioScope.launch { runCatching { addVideo(temp) } }
    }

    /** Encrypts captured JPEG bytes straight into the vault. */
    fun addPhoto(jpeg: ByteArray, rotationDegrees: Int): MediaItem {
        val upright = if (rotationDegrees != 0) rotateJpeg(jpeg, rotationDegrees) else jpeg
        val id = UUID.randomUUID().toString()
        mediaFile(id, encrypted = true).outputStream().use { out ->
            VaultCipher.encrypt(SessionVault.key(), upright, out)
        }
        writeThumb(id, decodeSampled(upright, THUMB_SIZE), encrypted = true)
        val item = MediaItem(
            id, "SafeTake_${timestampName()}.jpg", "image/jpeg",
            System.currentTimeMillis(), encrypted = true,
        )
        saveIndex(_items.value + item)
        return item
    }

    /** Encrypts a freshly recorded video temp file into the vault. */
    fun addVideo(temp: File): MediaItem {
        val id = UUID.randomUUID().toString()
        var durationMs = 0L
        var frame: Bitmap? = null
        MediaMetadataRetriever().use { r ->
            r.setDataSource(temp.path)
            durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            frame = r.getFrameAtTime(0)
        }
        writeThumb(id, frame?.let { scaleDown(it, THUMB_SIZE) }, encrypted = true)

        temp.inputStream().use { input ->
            mediaFile(id, encrypted = true).outputStream().use { output ->
                VaultCipher.encryptingStream(SessionVault.key(), output).use { input.copyTo(it) }
            }
        }
        temp.delete()

        val item = MediaItem(
            id, "SafeTake_${timestampName()}.mp4", "video/mp4",
            System.currentTimeMillis(), durationMs, encrypted = true,
        )
        saveIndex(_items.value + item)
        return item
    }

    /** Imports a content URI handed to us by the share sheet. */
    fun importShared(uri: Uri): MediaItem {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        if (!mime.startsWith("image/") && !mime.startsWith("video/")) {
            throw IOException("unsupported type $mime")
        }
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "shared_${timestampName()}"

        val id = UUID.randomUUID().toString()
        val encrypted = true
        resolver.openInputStream(uri)?.use { input ->
            mediaFile(id, encrypted).outputStream().use { output ->
                VaultCipher.encryptingStream(SessionVault.key(), output).use { input.copyTo(it) }
            }
        } ?: throw IOException("cannot open shared stream")

        var durationMs = 0L
        if (mime.startsWith("video/")) {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(context, uri)
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                writeThumb(id, r.getFrameAtTime(0)?.let { scaleDown(it, THUMB_SIZE) }, encrypted)
            }
        } else {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            writeThumb(id, bytes?.let { decodeSampled(it, THUMB_SIZE) }, encrypted)
        }
        val item = MediaItem(id, name, mime, System.currentTimeMillis(), durationMs, encrypted)
        saveIndex(_items.value + item)
        return item
    }

    // ---- reading media ----

    fun loadThumbnail(id: String): Bitmap? {
        thumbCache.get(id)?.let { return it }
        val item = find(id) ?: return null
        val file = thumbFile(id, item.encrypted)
        if (!file.exists()) return null
        val bytes = file.inputStream().use { readMaybeDecrypting(it, item.encrypted) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { thumbCache.put(id, it) }
    }

    fun loadFullImage(id: String, maxDim: Int = 2048): Bitmap? {
        val item = find(id) ?: return null
        val bytes = mediaFile(item).inputStream().use { readMaybeDecrypting(it, item.encrypted) }
        return decodeSampled(bytes, maxDim)
    }

    /**
     * Returns a seekable file for playback. Plaintext items play straight from
     * the vault; encrypted ones are decrypted to an app-private cache temp that
     * the caller must delete.
     */
    fun playbackFile(id: String): PlaybackFile {
        val item = find(id) ?: throw FileNotFoundException(id)
        if (!item.encrypted) return PlaybackFile(mediaFile(item), isTemp = false)
        val out = File(context.cacheDir, "play-$id.mp4")
        mediaFile(item).inputStream().use { input ->
            VaultCipher.decryptingStream(SessionVault.key(), input).use { plain ->
                out.outputStream().use { plain.copyTo(it) }
            }
        }
        return PlaybackFile(out, isTemp = true)
    }

    fun delete(ids: Collection<String>) {
        ids.forEach { id ->
            mediaFile(id, encrypted = true).delete()
            mediaFile(id, encrypted = false).delete()
            thumbFile(id, encrypted = true).delete()
            thumbFile(id, encrypted = false).delete()
        }
        saveIndex(_items.value.filterNot { it.id in ids })
    }

    fun find(id: String): MediaItem? = _items.value.find { it.id == id }

    // ---- helpers ----

    private fun readMaybeDecrypting(input: InputStream, encrypted: Boolean): ByteArray =
        if (encrypted) VaultCipher.decrypt(SessionVault.key(), input) else input.readBytes()

    private fun writeThumb(id: String, bitmap: Bitmap?, encrypted: Boolean) {
        if (bitmap == null) return
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        thumbFile(id, encrypted).outputStream().use { out ->
            if (encrypted) VaultCipher.encrypt(SessionVault.key(), baos.toByteArray(), out)
            else out.write(baos.toByteArray())
        }
    }

    private fun decodeSampled(jpeg: ByteArray, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
    }

    private fun rotateJpeg(jpeg: ByteArray, degrees: Int): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val baos = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, 95, baos)
        return baos.toByteArray()
    }

    private fun scaleDown(bitmap: Bitmap, maxDim: Int): Bitmap {
        val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true
        )
    }

    private fun timestampName(): String =
        java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())

    companion object {
        private const val THUMB_SIZE = 512
    }
}
