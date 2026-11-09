package com.scenemate.app.presentation

import android.graphics.Bitmap
import android.media.Image

/**
 * YUV_420_888 → Bitmap。只在用户点「深度分析」时调用一次，不参与实时链路。
 * 采样率 2 表示输出为原图一半宽高（640×360 级），够 LLM 看场景。
 */
object YuvBitmapConverter {

    fun toBitmap(image: Image, sample: Int = 2): Bitmap? {
        if (image.planes.size < 3) return null
        val width = image.width
        val height = image.height
        val outW = (width / sample).coerceAtLeast(1)
        val outH = (height / sample).coerceAtLeast(1)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val pixels = IntArray(outW * outH)
        for (row in 0 until outH) {
            val srcY = (row * sample).coerceAtMost(height - 1)
            for (col in 0 until outW) {
                val srcX = (col * sample).coerceAtMost(width - 1)
                val yIdx = srcY * yPlane.rowStride + srcX * yPlane.pixelStride
                val uvRow = srcY / 2
                val uvCol = srcX / 2
                val uIdx = uvRow * uPlane.rowStride + uvCol * uPlane.pixelStride
                val vIdx = uvRow * vPlane.rowStride + uvCol * vPlane.pixelStride
                if (yIdx >= yBuf.limit() || uIdx >= uBuf.limit() || vIdx >= vBuf.limit()) continue

                val y = (yBuf.get(yIdx).toInt() and 0xFF) - 16
                val u = (uBuf.get(uIdx).toInt() and 0xFF) - 128
                val v = (vBuf.get(vIdx).toInt() and 0xFF) - 128

                val r = (1.164f * y + 1.596f * v).toInt().coerceIn(0, 255)
                val g = (1.164f * y - 0.392f * u - 0.813f * v).toInt().coerceIn(0, 255)
                val b = (1.164f * y + 2.017f * u).toInt().coerceIn(0, 255)
                pixels[row * outW + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
    }
}
