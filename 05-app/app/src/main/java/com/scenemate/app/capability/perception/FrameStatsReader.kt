package com.scenemate.app.capability.perception

import android.graphics.ImageFormat
import android.media.Image
import com.scenemate.app.capability.guidance.CameraParamState
import kotlin.math.roundToInt

/**
 * 从 YUV_420_888 直接算统计量。没有解码、没有 Bitmap、没有 GPU——这是「规则标签 ≤300ms」的前提。
 * 采样步长 8px，1080p 一帧约 3 万次读，<1ms。
 */
object FrameStatsReader {

    fun read(
        image: Image,
        iso: Int = 0,
        exposureNs: Long = 0L,
        personCount: Int = 0,
        rollDeg: Double = 0.0,
    ): FrameStats {
        if (image.format != ImageFormat.YUV_420_888 || image.planes.size < 3) {
            return FrameStats(iso = iso, exposureNs = exposureNs, personCount = personCount, rollDeg = rollDeg)
        }
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val width = image.width
        val height = image.height
        val step = 8

        val yBuf = y.buffer
        val rowStride = y.rowStride
        var sum = 0L
        var count = 0L
        var topSum = 0L
        var topCount = 0L
        var bottomSum = 0L
        var bottomCount = 0L

        var row = 0
        while (row < height) {
            val base = row * rowStride
            var col = 0
            while (col < width) {
                val idx = base + col
                if (idx < yBuf.limit()) {
                    val value = yBuf.get(idx).toInt() and 0xFF
                    sum += value
                    count++
                    when {
                        row < height / 4 -> { topSum += value; topCount++ }
                        row > height * 3 / 4 -> { bottomSum += value; bottomCount++ }
                    }
                }
                col += step
            }
            row += step
        }

        val uBuf = u.buffer
        val vBuf = v.buffer
        var uSum = 0L
        var vSum = 0L
        var uvCount = 0L
        val uvStep = step * 2
        row = 0
        while (row < height / 2) {
            var col = 0
            while (col < width / 2) {
                val idx = row * u.rowStride + col * u.pixelStride
                if (idx < uBuf.limit() && idx < vBuf.limit()) {
                    uSum += uBuf.get(idx).toInt() and 0xFF
                    vSum += vBuf.get(idx).toInt() and 0xFF
                    uvCount++
                }
                col += uvStep
            }
            row += uvStep
        }

        return FrameStats(
            meanLuma = if (count == 0L) 128.0 else sum.toDouble() / count,
            topLuma = if (topCount == 0L) 128.0 else topSum.toDouble() / topCount,
            bottomLuma = if (bottomCount == 0L) 128.0 else bottomSum.toDouble() / bottomCount,
            meanU = if (uvCount == 0L) 128.0 else uSum.toDouble() / uvCount,
            meanV = if (uvCount == 0L) 128.0 else vSum.toDouble() / uvCount,
            iso = iso,
            exposureNs = exposureNs,
            personCount = personCount,
            rollDeg = rollDeg,
        )
    }

    fun describe(params: CameraParamState): String =
        "ISO ${params.iso} · ${params.shutterDisplay()} · EV ${params.evDisplay()}"

    fun denomOf(exposureNs: Long): Int = (1_000_000_000.0 / exposureNs.coerceAtLeast(1L)).roundToInt()
}
