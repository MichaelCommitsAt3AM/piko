package com.piko.agent.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.piko.agent.MainActivity
import com.piko.agent.R
import com.piko.agent.accessibility.ScreenshotProcessor
import com.piko.agent.core.CoordinateMapper
import com.piko.agent.core.PixelSize
import com.piko.agent.model.ScreenshotCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Holds a user-granted MediaProjection and captures frames on demand. The
 * service is separate from AgentService because Android binds MediaProjection
 * lifetimes to a foreground service of type mediaProjection.
 */
class ProjectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val captureMutex = Mutex()
    private var projection: MediaProjection? = null
    private var imageThread: HandlerThread? = null
    private var handler: Handler? = null
    private var screenWidth = 0
    private var screenHeight = 0

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundWithNotification()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        runCatching { manager.getMediaProjection(resultCode, data) }
            .onSuccess { granted ->
                val mediaProjection = granted ?: run {
                    stopSelf()
                    return@onSuccess
                }
                releaseProjection()
                projection = mediaProjection
                mediaProjection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        projection = null
                        _active.value = false
                        stopSelf()
                    }
                }, mainHandler)
                val metrics = currentScreenSize()
                screenWidth = metrics.first
                screenHeight = metrics.second
                imageThread = HandlerThread("piko-projection").also { it.start() }
                handler = Handler(imageThread!!.looper)
                _active.value = true
            }
            .onFailure {
                _active.value = false
                stopSelf()
            }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseProjection()
        _active.value = false
        instance = null
        scope.cancel()
        imageThread?.quitSafely()
        imageThread = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun captureFrame(quality: Int, maxDimension: Int): ScreenshotCapture? =
        captureMutex.withLock {
            val mediaProjection = projection ?: return@withLock null
            val (width, height) = currentScreenSize()
            if (width <= 0 || height <= 0) return@withLock null

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val virtualDisplay = try {
                mediaProjection.createVirtualDisplay(
                    "piko-screen",
                    width,
                    height,
                    resources.displayMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    handler,
                ) ?: run {
                    reader.close()
                    return@withLock null
                }
            } catch (error: Exception) {
                reader.close()
                return@withLock null
            }

            try {
                val image = withTimeoutOrNull(6_000) { awaitImage(reader) } ?: return@withLock null
                val bitmap = try {
                    imageToBitmap(image)
                } finally {
                    image.close()
                }
                val corrected = correctOrientation(bitmap, width, height)
                compress(corrected, quality, maxDimension)
            } finally {
                virtualDisplay.release()
                reader.close()
            }
        }

    private suspend fun awaitImage(reader: ImageReader): Image? =
        suspendCancellableCoroutine { continuation ->
            val resumed = java.util.concurrent.atomic.AtomicBoolean(false)
            fun deliver(image: Image?) {
                if (image == null) return
                if (resumed.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(image)
                } else {
                    image.close()
                }
            }
            val listener = ImageReader.OnImageAvailableListener { source ->
                if (resumed.get()) return@OnImageAvailableListener
                deliver(runCatching { source.acquireLatestImage() }.getOrNull())
            }
            reader.setOnImageAvailableListener(listener, handler ?: Handler(Looper.getMainLooper()))
            // The first frame can already be queued before the listener is set.
            deliver(runCatching { reader.acquireLatestImage() }.getOrNull())
            continuation.invokeOnCancellation {
                runCatching { reader.setOnImageAvailableListener(null, null) }
            }
        }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        if (paddedWidth == image.width) return bitmap
        val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        bitmap.recycle()
        return cropped
    }

    private fun correctOrientation(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        if (bitmap.width == width && bitmap.height == height) return bitmap
        if (bitmap.width == height && bitmap.height == width) {
            val matrix = Matrix().apply { postRotate(90f) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            return rotated
        }
        return bitmap
    }

    private fun compress(bitmap: Bitmap, quality: Int, maxDimension: Int): ScreenshotCapture {
        return try {
            ScreenshotProcessor.process(
                bitmap = bitmap,
                quality = quality,
                maxDimension = maxDimension,
                source = "media_projection",
            )
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun currentScreenSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        val bounds = wm.currentWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }

    private fun releaseProjection() {
        projection?.let { runCatching { it.stop() } }
        projection = null
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_projection),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while Piko is allowed to capture the screen."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundWithNotification() {
        val openIntent = PendingIntent.getActivity(
            this,
            20,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_piko)
            .setContentTitle(getString(R.string.notification_projection_title))
            .setContentText(getString(R.string.notification_projection_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private val mainHandler get() = Handler(Looper.getMainLooper())

    companion object {
        private const val CHANNEL_ID = "piko_projection"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_START = "com.piko.agent.action.START_PROJECTION"
        private const val ACTION_STOP = "com.piko.agent.action.STOP_PROJECTION"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "projection_data"

        @Volatile
        private var instance: ProjectionService? = null

        private val _active = MutableStateFlow(false)
        val active: StateFlow<Boolean> = _active.asStateFlow()

        fun isActive(): Boolean = instance != null && _active.value

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ProjectionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ProjectionService::class.java).apply { action = ACTION_STOP },
            )
        }

        suspend fun capture(quality: Int, maxDimension: Int): ScreenshotCapture? = withContext(Dispatchers.Default) {
            instance?.captureFrame(quality, maxDimension)
        }
    }
}
