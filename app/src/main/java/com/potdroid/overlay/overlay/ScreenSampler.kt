package com.potdroid.overlay.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import kotlin.math.roundToInt

class ScreenSampler(private val context: Context) {
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())

    fun start(resultCode: Int, data: Intent, width: Int, height: Int) {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenSampler",
            width, height, 1,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    fun sampleAt(x: Int, y: Int, radius: Int = 5): Triple<Float, Float, Float>? {
        val image = imageReader?.acquireLatestImage() ?: return null
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride

            var totalR = 0f
            var totalG = 0f
            var totalB = 0f
            var count = 0

            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    val px = (x + dx).coerceIn(0, image.width - 1)
                    val py = (y + dy).coerceIn(0, image.height - 1)
                    val offset = py * rowStride + px * pixelStride

                    val r = (buffer[offset].toInt() and 0xFF) / 255f
                    val g = (buffer[offset + 1].toInt() and 0xFF) / 255f
                    val b = (buffer[offset + 2].toInt() and 0xFF) / 255f

                    totalR += r
                    totalG += g
                    totalB += b
                    count++
                }
            }

            return Triple(totalR / count, totalG / count, totalB / count)
        } finally {
            image.close()
        }
    }

    fun stop() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }
}
