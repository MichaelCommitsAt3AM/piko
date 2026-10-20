package com.piko.agent.accessibility

import android.graphics.Bitmap
import com.piko.agent.core.CoordinateMapper
import com.piko.agent.core.PixelSize
import com.piko.agent.model.ScreenshotCapture
import java.io.ByteArrayOutputStream

/**
 * Resizes and JPEG-compresses a screenshot before it is sent to a model.
 * Keeping this separate makes the image pipeline testable without a device.
 */
object ScreenshotProcessor {
    fun process(
        bitmap: Bitmap,
        quality: Int,
        maxDimension: Int,
        source: String,
    ): ScreenshotCapture {
        val target = CoordinateMapper.fitInside(
            PixelSize(bitmap.width, bitmap.height),
            maxDimension,
        )
        val scaled = if (target.width != bitmap.width || target.height != bitmap.height) {
            Bitmap.createScaledBitmap(bitmap, target.width, target.height, true)
        } else {
            bitmap
        }
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 95), output)
        if (scaled !== bitmap) scaled.recycle()
        return ScreenshotCapture(
            bytes = output.toByteArray(),
            width = target.width,
            height = target.height,
            source = source,
            capturedAtMs = System.currentTimeMillis(),
        )
    }
}
