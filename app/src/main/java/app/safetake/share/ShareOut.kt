package app.safetake.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.safetake.data.MediaItem

object ShareOut {

    private const val AUTHORITY = "app.safetake.share"

    private fun uriFor(item: MediaItem): Uri =
        Uri.parse("content://$AUTHORITY/${item.id}")

    fun share(context: Context, items: List<MediaItem>) {
        if (items.isEmpty()) return
        val uris = items.map(::uriFor)
        val mime = when {
            items.all { it.mime.startsWith("image/") } -> "image/*"
            items.all { it.mime.startsWith("video/") } -> "video/*"
            else -> "*/*"
        }
        val intent = if (items.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = items[0].mime
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        // URI grants follow ClipData, not extras
        intent.clipData = ClipData.newUri(context.contentResolver, "SafeTake", uris[0]).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Share"))
    }
}
