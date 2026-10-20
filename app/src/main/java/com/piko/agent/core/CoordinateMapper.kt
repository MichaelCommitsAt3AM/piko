package com.piko.agent.core

import kotlin.math.roundToInt

data class PixelPoint(val x: Int, val y: Int)
data class PixelSize(val width: Int, val height: Int)

/**
 * Maps coordinates from a resized screenshot back to the Android display
 * coordinate space used by accessibility bounds and gesture dispatch.
 */
object CoordinateMapper {
    fun imageToScreen(
        point: PixelPoint,
        image: PixelSize,
        screen: PixelSize,
    ): PixelPoint {
        if (image.width <= 0 || image.height <= 0) return point
        val x = (point.x.toDouble() * screen.width / image.width).roundToInt()
        val y = (point.y.toDouble() * screen.height / image.height).roundToInt()
        return PixelPoint(
            x.coerceIn(0, (screen.width - 1).coerceAtLeast(0)),
            y.coerceIn(0, (screen.height - 1).coerceAtLeast(0)),
        )
    }

    fun screenToImage(
        point: PixelPoint,
        image: PixelSize,
        screen: PixelSize,
    ): PixelPoint {
        if (screen.width <= 0 || screen.height <= 0) return point
        val x = (point.x.toDouble() * image.width / screen.width).roundToInt()
        val y = (point.y.toDouble() * image.height / screen.height).roundToInt()
        return PixelPoint(
            x.coerceIn(0, (image.width - 1).coerceAtLeast(0)),
            y.coerceIn(0, (image.height - 1).coerceAtLeast(0)),
        )
    }

    /**
     * Screenshot buffers from MediaProjection can be expressed in the natural
     * orientation. Swap dimensions when the display is rotated 90/270 degrees.
     */
    fun orientedSize(size: PixelSize, rotationDegrees: Int): PixelSize {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        return if (normalized == 90 || normalized == 270) {
            PixelSize(size.height, size.width)
        } else {
            size
        }
    }

    fun fitInside(size: PixelSize, maxDimension: Int): PixelSize {
        if (size.width <= 0 || size.height <= 0) return size
        val largest = maxOf(size.width, size.height)
        if (largest <= maxDimension) return size
        val scale = maxDimension.toDouble() / largest
        return PixelSize(
            (size.width * scale).roundToInt().coerceAtLeast(1),
            (size.height * scale).roundToInt().coerceAtLeast(1),
        )
    }
}
