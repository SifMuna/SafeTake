package app.safetake.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.safetake.data.MediaRepository
import app.safetake.share.ShareOut
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    repository: MediaRepository,
    itemId: String,
    onClose: () -> Unit,
    onDelete: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    val item = remember(itemId) { repository.find(itemId) }

    if (item == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(item.name, maxLines = 1) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.5f),
                ),
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { ShareOut.share(context, listOf(item)) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = {
                        // Hidden immediately; the Undo snackbar appears back on the gallery.
                        onDelete(setOf(item.id))
                        onClose()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            if (item.isVideo) {
                VideoPlayer(repository, item.id)
            } else {
                val bitmap by produceState<Bitmap?>(initialValue = null, item.id) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { repository.loadFullImage(item.id) }.getOrNull()
                    }
                }
                bitmap?.let {
                    ZoomableImage(bitmap = it, contentDescription = item.name)
                } ?: CircularProgressIndicator()
            }
        }
    }
}

private const val MAX_ZOOM = 5f

/**
 * A photo the user can pinch to zoom (1x–[MAX_ZOOM]) and drag to pan, with
 * double-tap to toggle between fit and 2x. Panning is clamped so the image can't
 * be dragged entirely off-screen, and releasing below 1x snaps back to fit.
 */
@Composable
private fun ZoomableImage(bitmap: Bitmap, contentDescription: String?) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    // Keeps pan within the overflow the current zoom produces (image is centered).
    fun clampOffset(raw: Offset, s: Float): Offset {
        val maxX = (boxSize.width * (s - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (boxSize.height * (s - 1f) / 2f).coerceAtLeast(0f)
        return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                    scale = newScale
                    offset = clampOffset(offset + pan, newScale)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2f
                        offset = clampOffset(offset, 2f)
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

/**
 * Plays the video with ExoPlayer. Plaintext items play straight from the vault
 * file; encrypted ones are first decrypted to an app-private cache temp (MP4
 * playback needs a seekable file) that is deleted when the screen goes away.
 */
@Composable
private fun VideoPlayer(repository: MediaRepository, itemId: String) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var tempFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(itemId) {
        val playback = withContext(Dispatchers.IO) {
            runCatching { repository.playbackFile(itemId) }.getOrNull()
        } ?: return@LaunchedEffect
        if (playback.isTemp) tempFile = playback.file
        player = ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.fromFile(playback.file)))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            tempFile?.delete()
        }
    }

    player?.let { exo ->
        AndroidView(
            factory = { PlayerView(it).apply { this.player = exo } },
            update = { it.player = exo },
            modifier = Modifier.fillMaxSize(),
        )
    } ?: CircularProgressIndicator()
}
