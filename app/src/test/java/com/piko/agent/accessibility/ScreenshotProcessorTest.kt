package com.piko.agent.accessibility

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenshotProcessorTest {
    @Test
    fun `large screenshot is resized and JPEG encoded`() {
        val bitmap = Bitmap.createBitmap(1000, 2000, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(20, 30, 40))
        val capture = ScreenshotProcessor.process(bitmap, quality = 70, maxDimension = 500, source = "test")
        assertEquals(250, capture.width)
        assertEquals(500, capture.height)
        assertTrue(capture.bytes.size > 100)
        // JPEG SOI marker.
        assertEquals(0xFF.toByte(), capture.bytes[0])
        assertEquals(0xD8.toByte(), capture.bytes[1])
        assertEquals("test", capture.source)
        bitmap.recycle()
    }

    @Test
    fun `small screenshot is not enlarged`() {
        val bitmap = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888)
        val capture = ScreenshotProcessor.process(bitmap, quality = 50, maxDimension = 1024, source = "test")
        assertEquals(320, capture.width)
        assertEquals(200, capture.height)
        bitmap.recycle()
    }

    @Test
    fun `quality is clamped to a safe range`() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val low = ScreenshotProcessor.process(bitmap, quality = 1, maxDimension = 100, source = "test")
        assertTrue(low.bytes.isNotEmpty())
        val highBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val high = ScreenshotProcessor.process(highBitmap, quality = 100, maxDimension = 100, source = "test")
        assertTrue(high.bytes.isNotEmpty())
        bitmap.recycle()
        highBitmap.recycle()
    }
}
