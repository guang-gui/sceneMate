package com.scenemate.app.capability.geometry

import com.scenemate.app.domain.NormRect
import com.scenemate.app.domain.RangeTier
import kotlin.math.abs

/** 构图模板。alphaTarget 是目标主体像素占比，与身高无关（§6.2.1）。 */
data class CompositionTemplate(
    val name: String,
    val alphaTarget: Double,
    val xTargetNorm: Double,
)

object Templates {
    val FULL_BODY = CompositionTemplate("全身", alphaTarget = 0.72, xTargetNorm = 0.5)
    val HALF_BODY = CompositionTemplate("半身", alphaTarget = 0.85, xTargetNorm = 0.5)
    val THIRD_LEFT = CompositionTemplate("三分左", alphaTarget = 0.66, xTargetNorm = 1.0 / 3.0)
    val THIRD_RIGHT = CompositionTemplate("三分右", alphaTarget = 0.66, xTargetNorm = 2.0 / 3.0)

    /** 2 秒兜底用的默认模板（R14）。 */
    val FALLBACK = FULL_BODY
}

/**
 * 站位求解（TAD §6.3）。
 *
 * 关键取舍：闭环控制量是 alpha（无量纲），不是距离。
 * d 与 d* 都正比于 H，所以 Δ = d* − d 的相对误差恒等于身高相对误差，不随距离放大——
 * 0.45m 的位移误差只有 2.3cm，完全够用。
 */
class StanceSolver {

    data class Solution(
        /** >0 表示需要后退（远离镜头）。 */
        val deltaBackM: Double,
        /** >0 表示需要向右移动（摄影师视角）。 */
        val deltaLateralM: Double,
        val alpha: Double,
        val alphaTarget: Double,
        val distanceM: Double,
        val sigmaM: Double,
        val rangeTier: RangeTier,
        val footprintNorm: NormRect,
        val subjectBoxNorm: NormRect,
        val worldX: Double,
        val worldZ: Double,
        val headingDeg: Double,
        /** 是否落在死区内（不需要给指令）。 */
        val insideDeadZone: Boolean,
    )

    fun solve(
        estimate: RangeEstimator.Estimate,
        frame: FrameGeometry,
        template: CompositionTemplate = Templates.FULL_BODY,
        headingDeg: Double = 0.0,
        deadZoneM: Double = 0.25,
    ): Solution {
        val alpha = estimate.alpha
        val heightM = BodyProportions.DEFAULT_HEIGHT_M
        val frameH = frame.heightPx.toDouble()

        // d = f·H/(alpha·frameH)；d* 同理。用差值而不是各自求值，减少重复误差。
        val d = estimate.distanceM
        val dTarget = frame.focalPx * heightM / (template.alphaTarget * frameH)
        val deltaBack = dTarget - d

        // 横向：主体中心与目标 x* 的世界系偏移
        val u = estimate.bbox.centerX.toDouble()
        val dxWorld = (template.xTargetNorm - u) * frame.widthPx / frame.focalPx * d

        val footprint = footprintFor(estimate.bbox, d, frame)
        val insideDeadZone = abs(deltaBack) < deadZoneM &&
            abs(dxWorld) < InstructionWriterSupport.MIN_LATERAL_M

        val rangeTier = when {
            estimate.sigmaM <= 0.10 -> RangeTier.PRECISE
            estimate.sigmaM <= 0.20 -> RangeTier.CALIBRATED
            else -> RangeTier.DEFAULT
        }

        return Solution(
            deltaBackM = deltaBack,
            deltaLateralM = dxWorld,
            alpha = alpha,
            alphaTarget = template.alphaTarget,
            distanceM = d,
            sigmaM = estimate.sigmaM,
            rangeTier = rangeTier,
            footprintNorm = footprint,
            subjectBoxNorm = estimate.bbox,
            worldX = dxWorld,
            worldZ = d,
            headingDeg = headingDeg,
            insideDeadZone = insideDeadZone,
        )
    }

    /** 脚印画在被摄者脚下：宽度按 0.45m 实际宽度投影，随距离收缩。 */
    private fun footprintFor(bbox: NormRect, distanceM: Double, frame: FrameGeometry): NormRect {
        val footWidthM = 0.42
        val footWidthPx = footWidthM * frame.focalPx / distanceM.coerceAtLeast(0.5)
        val w = (footWidthPx / frame.widthPx).toFloat().coerceIn(0.04f, 0.4f)
        val h = w * 0.55f
        val cx = bbox.centerX.coerceIn(0.05f, 0.95f)
        val bottom = bbox.bottom.coerceIn(0.05f, 1.0f)
        return NormRect(
            left = (cx - w / 2f).coerceIn(0f, 1f),
            top = (bottom - h).coerceIn(0f, 1f),
            right = (cx + w / 2f).coerceIn(0f, 1f),
            bottom = bottom,
        )
    }
}

/** 供 solver 复用、避免几何层反向依赖 domain 常量。 */
internal object InstructionWriterSupport {
    const val MIN_LATERAL_M = 0.30
}
