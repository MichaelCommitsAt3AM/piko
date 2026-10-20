package com.piko.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.piko.agent.core.CoordinateMapper
import com.piko.agent.core.PixelSize
import com.piko.agent.model.PikoSettings
import com.piko.agent.model.ScreenshotCapture
import com.piko.agent.service.ProjectionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

data class ScreenshotOutcome(
    val capture: ScreenshotCapture? = null,
    val secure: Boolean = false,
    val error: String? = null,
)

/**
 * Captures screenshots via AccessibilityService.takeScreenshot first. This
 * works without a MediaProjection consent dialog. MediaProjection is a fallback
 * when the accessibility API is unavailable or fails for a non-secure reason.
 */
class ScreenshotEngine(private val context: Context) {

    suspend fun capture(settings: PikoSettings): ScreenshotOutcome =
        com.piko.agent.overlay.OverlaySuppressor.suppressed {
            try {
                captureInternal(settings)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ScreenshotOutcome(error = "Screenshot failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }

    private suspend fun captureInternal(settings: PikoSettings): ScreenshotOutcome {
        if (!settings.sendScreenshots) {
            return ScreenshotOutcome(error = "Screenshots are disabled in Settings.")
        }
        val service = AccessibilityBridge.service
        if (service != null) {
            lastSettings = settings
            val result = captureAccessibility(service, settings)
            if (result.capture != null || result.secure) return result
            val projection = try {
                ProjectionService.capture(
                    quality = settings.screenshotQuality,
                    maxDimension = settings.screenshotMaxDimension,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (projection != null) {
                return ScreenshotOutcome(capture = projection)
            }
            return ScreenshotOutcome(
                error = result.error ?: "Screenshots are unavailable. Grant screen capture in Settings.",
            )
        }
        val projection = try {
            ProjectionService.capture(
                quality = settings.screenshotQuality,
                maxDimension = settings.screenshotMaxDimension,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return if (projection != null) {
            ScreenshotOutcome(capture = projection)
        } else {
            ScreenshotOutcome(error = "Screenshots are unavailable. Enable accessibility or grant screen capture.")
        }
    }

    private suspend fun captureAccessibility(
        service: AccessibilityService,
        settings: PikoSettings,
    ): ScreenshotOutcome {
        val bitmap = withTimeoutOrNull(6_000) {
            suspendCancellableCoroutine { continuation ->
                var resumed = false
                val callback = object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        if (!continuation.isActive || resumed) {
                            screenshot.hardwareBuffer.close()
                            return
                        }
                        resumed = true
                        val result = runCatching {
                            val hardwareBuffer = screenshot.hardwareBuffer
                            val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                                ?: error("The system returned an empty screenshot.")
                            val copy = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                            hardwareBuffer.close()
                            if (copy !== wrapped) wrapped.recycle()
                            copy
                        }
                        continuation.resume(result)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (!continuation.isActive || resumed) return
                        resumed = true
                        continuation.resume(Result.failure(ScreenshotAccessException(errorCode)))
                    }
                }
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        service.takeScreenshot(
                            Display.DEFAULT_DISPLAY,
                            context.mainExecutor,
                            callback,
                        )
                    }.onFailure { error ->
                        if (continuation.isActive && !resumed) {
                            resumed = true
                            continuation.resume(Result.failure(error))
                        }
                    }
                }
            }
        }
        if (bitmap == null) return ScreenshotOutcome(error = "The screenshot timed out.")
        return bitmap.fold(
            onSuccess = { value ->
                val compressed = compress(value, settings)
                if (compressed == null) {
                    ScreenshotOutcome(error = "Could not encode the screenshot.")
                } else {
                    ScreenshotOutcome(capture = compressed)
                }
            },
            onFailure = { error ->
                val secure = error is ScreenshotAccessException && error.isSecureWindow()
                ScreenshotOutcome(
                    secure = secure,
                    error = if (secure) {
                        "The foreground app protects its content from screenshots."
                    } else {
                        "Accessibility screenshot failed: ${error.message}"
                    },
                )
            },
        )
    }

    private fun compress(bitmap: Bitmap, settings: PikoSettings): ScreenshotCapture? {
        return try {
            ScreenshotProcessor.process(
                bitmap = bitmap,
                quality = settings.screenshotQuality,
                maxDimension = settings.screenshotMaxDimension,
                source = "accessibility",
            )
        } catch (_: Exception) {
            null
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    @Volatile
    var lastSettings: PikoSettings? = null

    private class ScreenshotAccessException(val code: Int) : Exception("error code $code") {
        fun isSecureWindow(): Boolean =
            Build.VERSION.SDK_INT >= 34 && code == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
    }
}
