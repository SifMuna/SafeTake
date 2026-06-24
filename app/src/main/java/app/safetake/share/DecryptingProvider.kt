package app.safetake.share

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import app.safetake.SafeTakeApp
import app.safetake.crypto.SessionVault
import app.safetake.crypto.VaultCipher
import app.safetake.data.MediaItem
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only provider backing share-out. URIs look like
 * content://app.safetake.share/<id>. Receivers need a *seekable* fd
 * (pipes break Files, video players, anything that mmaps), so openFile
 * decrypts to an app-private cache temp and unlinks it immediately — the
 * returned fd keeps the data alive with no directory entry. Fails closed when
 * the vault is locked. Not exported — access only via per-share URI grants.
 */
class DecryptingProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun app(): SafeTakeApp = context!!.applicationContext as SafeTakeApp

    private fun item(uri: Uri): MediaItem? =
        uri.lastPathSegment?.let { app().repository.find(it) }

    override fun getType(uri: Uri): String? = item(uri)?.mime

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val media = item(uri) ?: throw FileNotFoundException(uri.toString())
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols, 1)
        cursor.addRow(cols.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> media.name
                OpenableColumns.SIZE -> plaintextSize(media)
                else -> null
            }
        })
        return cursor
    }

    // encrypted vault file = 8-byte magic + 12-byte IV + ciphertext (plaintext + 16-byte GCM tag)
    private fun plaintextSize(media: MediaItem): Long {
        val length = app().repository.mediaFile(media).length()
        return if (media.encrypted) (length - 8 - 12 - 16).coerceAtLeast(0) else length
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if ("w" in mode) throw SecurityException("read-only provider")
        val media = item(uri) ?: throw FileNotFoundException(uri.toString())
        val key = SessionVault.keyOrNull() ?: throw FileNotFoundException("vault is locked")
        val file = app().repository.mediaFile(media)
        if (!media.encrypted) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val temp = File.createTempFile("share-", ".tmp", context!!.cacheDir)
        try {
            temp.outputStream().use { out ->
                file.inputStream().use { input ->
                    VaultCipher.decryptingStream(key, input).use { it.copyTo(out) }
                }
            }
            return ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally {
            temp.delete()
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?,
    ): Int = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException()
}
