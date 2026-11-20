package com.example.vision

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.R

object ScreenCaptureBridge {
    var onBitmapCaptured: ((Bitmap?) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
}

/**
 * Ephemeral MediaProjection foreground service strictly used for a single user-consented
 * screen capture frame, immediately tearing down VirtualDisplay and MediaProjection afterward.
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var hasCaptured = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val dataIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA_INTENT)
        }

        if (resultCode != Activity.RESULT_OK || dataIntent == null) {
            ScreenCaptureBridge.onError?.invoke("Screen capture consent was not granted.")
            cleanupAndStop()
            return START_NOT_STICKY
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, dataIntent)
        if (projection == null) {
            ScreenCaptureBridge.onError?.invoke("Unable to initialize MediaProjection.")
            cleanupAndStop()
            return START_NOT_STICKY
        }

        mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                cleanupAndStop()
            }
        }, handler)

        val metrics = resources.displayMetrics
        val width = (metrics.widthPixels / 2).coerceAtLeast(360)
        val height = (metrics.heightPixels / 2).coerceAtLeast(640)
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ r ->
            if (hasCaptured) return@setOnImageAvailableListener
            val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            hasCaptured = true
            try {
                val imgWidth = image.width.coerceAtLeast(1)
                val imgHeight = image.height.coerceAtLeast(1)
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride.coerceAtLeast(1)
                val rowStride = plane.rowStride
                val rowPadding = (rowStride - pixelStride * imgWidth).coerceAtLeast(0)
                val fullBitmap = Bitmap.createBitmap(
                    imgWidth + rowPadding / pixelStride,
                    imgHeight,
                    Bitmap.Config.ARGB_8888
                )
                fullBitmap.copyPixelsFromBuffer(buffer)
                val cropped = Bitmap.createBitmap(fullBitmap, 0, 0, imgWidth, imgHeight)
                if (cropped != fullBitmap) fullBitmap.recycle()

                handler.post {
                    ScreenCaptureBridge.onBitmapCaptured?.invoke(cropped)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing screen frame", e)
                handler.post {
                    ScreenCaptureBridge.onError?.invoke("Failed to capture screen frame: ${e.localizedMessage}")
                }
            } finally {
                image.close()
                cleanupAndStop()
            }
        }, handler)

        virtualDisplay = projection.createVirtualDisplay(
            "BumbleScreenVision",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )

        // Safety timeout to guarantee ephemeral lifecycle
        handler.postDelayed({
            if (!hasCaptured) {
                ScreenCaptureBridge.onError?.invoke("Screen capture timed out.")
                cleanupAndStop()
            }
        }, 4000L)

        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "bumble_screen_vision"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                channelId,
                "Bumble Screen Vision",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.screen_capture_notification_title))
            .setContentText(getString(R.string.screen_capture_notification_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun cleanupAndStop() {
        handler.removeCallbacksAndMessages(null)
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupAndStop()
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 4409
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA_INTENT = "data_intent"
    }
}
