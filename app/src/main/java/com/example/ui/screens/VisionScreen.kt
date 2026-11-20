package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.ScreenshotMonitor
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.VisionUiState
import com.example.ui.components.HudPanel
import com.example.ui.theme.BumbleCrimson
import com.example.ui.theme.BumbleGold
import com.example.ui.theme.BumbleGoldBright
import com.example.ui.theme.BumbleGraphite
import com.example.ui.theme.BumbleHudBorder
import com.example.ui.theme.BumbleTextSecondary
import com.example.ui.theme.BumbleVoidBlack
import com.example.vision.ScreenCaptureBridge
import com.example.vision.ScreenCaptureService
import java.nio.ByteBuffer

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VisionScreen(
    visionState: VisionUiState,
    isLiveConnected: Boolean,
    onImageReady: (Bitmap?, String) -> Unit,
    onAnalyze: (String) -> Unit,
    onSendToLive: (Bitmap, String) -> Unit,
    onVisionError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var showCameraPreview by remember { mutableStateOf(hasCameraPerm) }
    var promptInput by remember { mutableStateOf("Identify the objects and explain what you see.") }
    var showScreenConsentDialog by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPerm = granted
        showCameraPreview = granted
        if (!granted) {
            onVisionError("Camera permission denied. You can still select photos with the Photo Picker.")
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }.onSuccess { bmp ->
                if (bmp != null) {
                    showCameraPreview = false
                    onImageReady(bmp, "Photo Picker Image")
                }
            }.onFailure { e ->
                onVisionError("Could not decode image: ${e.localizedMessage}")
            }
        }
    }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureBridge.onBitmapCaptured = { bmp ->
                if (bmp != null) {
                    showCameraPreview = false
                    onImageReady(bmp, "Consented Screen Capture")
                }
            }
            ScreenCaptureBridge.onError = { err ->
                onVisionError(err)
            }
            val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA_INTENT, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } else {
            onVisionError("Screen capture consent cancelled.")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            ScreenCaptureBridge.onBitmapCaptured = null
            ScreenCaptureBridge.onError = null
        }
    }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("AI Camera & Screen Vision", style = MaterialTheme.typography.headlineMedium, color = BumbleGoldBright)

            // Preview / Captured Frame Container
            Surface(
                shape = MaterialTheme.shapes.large,
                color = BumbleGraphite,
                border = BorderStroke(1.dp, BumbleHudBorder),
                modifier = Modifier.fillMaxWidth().height(270.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        visionState.previewBitmap != null && !showCameraPreview -> {
                            Image(
                                bitmap = visionState.previewBitmap.asImageBitmap(),
                                contentDescription = visionState.sourceLabel,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.large)
                            )
                        }
                        hasCameraPerm && showCameraPreview -> {
                            AndroidView(
                                factory = { ctx ->
                                    val previewView = PreviewView(ctx)
                                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                    cameraProviderFuture.addListener({
                                        val provider = cameraProviderFuture.get()
                                        val preview = Preview.Builder().build().also {
                                            it.surfaceProvider = previewView.surfaceProvider
                                        }
                                        val capture = ImageCapture.Builder()
                                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                            .build()
                                        imageCapture = capture
                                        runCatching {
                                            provider.unbindAll()
                                            provider.bindToLifecycle(
                                                lifecycleOwner,
                                                CameraSelector.DEFAULT_BACK_CAMERA,
                                                preview,
                                                capture
                                            )
                                        }
                                    }, ContextCompat.getMainExecutor(ctx))
                                    previewView
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        else -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, tint = BumbleGold)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Enable Camera or Pick a Photo", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Use live camera capture, zero-permission Photo Picker, or consented single-frame Screen Understanding.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = BumbleTextSecondary
                                )
                            }
                        }
                    }
                }
            }

            // Capture / Source Controls
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (!hasCameraPerm) {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        } else if (!showCameraPreview) {
                            showCameraPreview = true
                        } else {
                            val cap = imageCapture ?: return@Button
                            cap.takePicture(
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: ImageProxy) {
                                        val bmp = imageProxyToBitmap(image)
                                        image.close()
                                        showCameraPreview = false
                                        onImageReady(bmp, "CameraX Frame")
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        onVisionError("Capture failed: ${exception.localizedMessage}")
                                    }
                                }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BumbleGold, contentColor = BumbleVoidBlack),
                    modifier = Modifier.testTag("vision_capture_button")
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        when {
                            !hasCameraPerm -> "Grant Camera"
                            !showCameraPreview -> "Live Camera"
                            else -> "Capture Frame"
                        }
                    )
                }

                OutlinedButton(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    border = BorderStroke(1.dp, BumbleGold.copy(alpha = 0.6f)),
                    modifier = Modifier.testTag("vision_photo_picker_button")
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = BumbleGoldBright)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Pick Photo", color = BumbleGoldBright)
                }

                OutlinedButton(
                    onClick = { showScreenConsentDialog = true },
                    border = BorderStroke(1.dp, BumbleGold.copy(alpha = 0.6f)),
                    modifier = Modifier.testTag("vision_screen_capture_button")
                ) {
                    Icon(Icons.Default.ScreenshotMonitor, contentDescription = null, tint = BumbleGoldBright)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Screen Capture", color = BumbleGoldBright)
                }
            }

            // Question Prompt + Analyze
            OutlinedTextField(
                value = promptInput,
                onValueChange = { promptInput = it },
                label = { Text("Ask about this image or scene") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth().testTag("vision_prompt_input")
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onAnalyze(promptInput) },
                    enabled = visionState.previewBitmap != null && !visionState.isAnalyzing,
                    colors = ButtonDefaults.buttonColors(containerColor = BumbleGold, contentColor = BumbleVoidBlack),
                    modifier = Modifier.weight(1f).testTag("vision_analyze_button")
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Analyze with Gemini")
                }

                if (isLiveConnected && visionState.previewBitmap != null) {
                    OutlinedButton(
                        onClick = { onSendToLive(visionState.previewBitmap, promptInput) },
                        border = BorderStroke(1.dp, BumbleGold)
                    ) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = BumbleGoldBright)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Send to Live", color = BumbleGoldBright)
                    }
                }
            }

            if (visionState.isAnalyzing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(color = BumbleGold)
                    Text("Analyzing visual frame...", style = MaterialTheme.typography.bodyMedium)
                }
            }

            visionState.error?.let { err ->
                HudPanel(modifier = Modifier.fillMaxWidth()) {
                    Text(err, color = BumbleCrimson, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (visionState.analysisResult.isNotBlank()) {
                HudPanel(highlightBorder = true, modifier = Modifier.fillMaxWidth()) {
                    Text("GEMINI VISION ANALYSIS", style = MaterialTheme.typography.labelLarge, color = BumbleGoldBright)
                    Text(visionState.analysisResult, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }

    if (showScreenConsentDialog) {
        AlertDialog(
            onDismissRequest = { showScreenConsentDialog = false },
            title = { Text("Screen Understanding Consent") },
            text = {
                Text(
                    "Bumble AI will request Android's system screen-capture dialog to grab a single frame for visual Q&A, then immediately stop capturing."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showScreenConsentDialog = false
                        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BumbleGold, contentColor = BumbleVoidBlack)
                ) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { showScreenConsentDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun imageProxyToBitmap(proxy: ImageProxy): Bitmap {
    val buffer: ByteBuffer = proxy.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val rotation = proxy.imageInfo.rotationDegrees
    return if (rotation != 0) {
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    } else {
        raw
    }
}
