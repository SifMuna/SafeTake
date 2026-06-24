package app.safetake.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.safetake.data.MediaRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun CameraScreen(repository: MediaRepository, onClose: () -> Unit) {
    val context = LocalContext.current

    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasCamera = it }

    LaunchedEffect(Unit) {
        if (!hasCamera) cameraPermission.launch(Manifest.permission.CAMERA)
    }

    if (!hasCamera) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("SafeTake needs the camera to take photos.")
                Button(onClick = { cameraPermission.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant camera access")
                }
                Button(onClick = onClose) { Text("Back") }
            }
        }
        return
    }

    CameraContent(repository, onClose)
}

@SuppressLint("MissingPermission") // withAudioEnabled is guarded by hasAudio
@Composable
private fun CameraContent(repository: MediaRepository, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var videoMode by remember { mutableStateOf(false) }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var torchOn by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recordSeconds by remember { mutableIntStateOf(0) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    var hasAudio by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val audioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasAudio = it }

    val previewView = remember { PreviewView(context) }
    val preview = remember { Preview.Builder().build() }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val videoCapture = remember {
        VideoCapture.withOutput(
            Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.FHD,
                        androidx.camera.video.FallbackStrategy.higherQualityOrLowerThan(Quality.FHD)
                    )
                )
                .build()
        )
    }

    LaunchedEffect(lensFacing) {
        val provider = withContext(Dispatchers.IO) {
            ProcessCameraProvider.getInstance(context).get()
        }
        provider.unbindAll()
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        camera = provider.bindToLifecycle(
            lifecycleOwner, selector, preview, imageCapture, videoCapture
        )
        preview.surfaceProvider = previewView.surfaceProvider
        camera?.cameraControl?.enableTorch(torchOn)
    }

    LaunchedEffect(flashMode) { imageCapture.flashMode = flashMode }
    LaunchedEffect(torchOn, camera) { camera?.cameraControl?.enableTorch(torchOn) }

    // ask for the mic when entering video mode so recordings have sound
    LaunchedEffect(videoMode) {
        if (videoMode && !hasAudio) audioPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(recording) {
        recordSeconds = 0
        while (recording != null) {
            delay(1000)
            recordSeconds++
        }
    }

    fun capturePhoto() {
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    val rotation = image.imageInfo.rotationDegrees
                    image.close()
                    repository.savePhotoAsync(bytes, rotation)
                    Toast.makeText(context, "Saved to vault", Toast.LENGTH_SHORT).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(context, "Capture failed", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    fun toggleRecording() {
        val active = recording
        if (active != null) {
            active.stop() // Finalize event handles the rest
            return
        }
        val temp = File(context.cacheDir, "rec-${UUID.randomUUID()}.mp4")
        val pending = videoCapture.output
            .prepareRecording(context, FileOutputOptions.Builder(temp).build())
            .let { if (hasAudio) it.withAudioEnabled() else it }
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                recording = null
                torchOn = false
                if (event.hasError()) {
                    temp.delete()
                    Toast.makeText(context, "Recording failed", Toast.LENGTH_SHORT).show()
                } else {
                    repository.saveVideoAsync(temp)
                    Toast.makeText(context, "Saved to vault", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // systemBarsPadding everywhere: edge-to-edge is enforced on Android 15+,
        // and controls under the status bar don't receive taps
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .systemBarsPadding()
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CameraIconButton(
                icon = Icons.Default.Close,
                description = "Close camera",
                enabled = recording == null,
                onClick = onClose,
            )
            if (recording != null || videoMode) {
                CameraIconButton(
                    icon = if (torchOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                    description = "Toggle flashlight",
                    onClick = { torchOn = !torchOn },
                )
            } else {
                CameraIconButton(
                    icon = when (flashMode) {
                        ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                        ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                        else -> Icons.Default.FlashOff
                    },
                    description = "Flash mode",
                    onClick = {
                        flashMode = when (flashMode) {
                            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                            else -> ImageCapture.FLASH_MODE_OFF
                        }
                    },
                )
            }
        }

        if (recording != null) {
            Text(
                formatDuration(recordSeconds * 1000L),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .systemBarsPadding()
                    .padding(top = 72.dp)
                    .background(Color.Red.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }

        // bottom controls: mode toggle, shutter, lens flip
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .systemBarsPadding()
                .fillMaxWidth()
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (recording == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip("Photo", selected = !videoMode) { videoMode = false }
                    ModeChip("Video", selected = videoMode) { videoMode = true }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(56.dp)) // spacer balancing the flip button
                ShutterButton(
                    videoMode = videoMode,
                    recording = recording != null,
                    onClick = { if (videoMode) toggleRecording() else capturePhoto() },
                )
                IconButton(
                    onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                    },
                    enabled = recording == null,
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        Icons.Default.Cameraswitch,
                        contentDescription = "Switch camera",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = description, tint = Color.White)
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.4f),
    ) {
        Text(
            label,
            color = if (selected) Color.Black else Color.White,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ShutterButton(videoMode: Boolean, recording: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = when {
            recording -> Color.Red
            videoMode -> Color.White
            else -> Color.White
        },
        modifier = Modifier
            .size(76.dp)
            .border(4.dp, Color.White.copy(alpha = 0.6f), CircleShape),
    ) {
        Box(contentAlignment = Alignment.Center) {
            when {
                recording -> Icon(Icons.Default.Stop, contentDescription = "Stop recording", tint = Color.White)
                videoMode -> Icon(Icons.Default.Videocam, contentDescription = "Record", tint = Color.Red)
                else -> Icon(Icons.Default.PhotoCamera, contentDescription = "Take photo", tint = Color.Black)
            }
        }
    }
}
