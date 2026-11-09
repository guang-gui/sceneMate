package com.scenemate.app.capability.geometry

import kotlin.math.abs
import kotlin.math.max

/** 归一化关键点（0..1，左上原点）。与平台无关，iOS 复用同一契约。 */
data class Landmark(val x: Float, val y: Float, val confidence: Float) {
    val valid: Boolean get() = confidence > 0.5f
}

/** 33 关键点里 R01 真正用得到的那几个。 */
data class PoseObservation(
    val nose: Landmark? = null,
    val leftEye: Landmark? = null,
    val rightEye: Landmark? = null,
    val leftEar: Landmark? = null,
    val rightEar: Landmark? = null,
    val leftShoulder: Landmark? = null,
    val rightShoulder: Landmark? = null,
    val leftHip: Landmark? = null,
    val rightHip: Landmark? = null,
    val leftKnee: Landmark? = null,
    val rightKnee: Landmark? = null,
    val leftAnkle: Landmark? = null,
    val rightAnkle: Landmark? = null,
    val allPoints: List<Landmark> = emptyList(),
)

/** 分析帧的内参与尺寸。 */
data class FrameGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val focalPx: Double,
)

/** 人体比例常数（ADR-011）。成年人比例稳定，个体差异 ~2%。 */
object BodyProportions {
    /** 鼻尖到头顶 / 身高 */
    const val K_HEAD = 0.07

    /** 外踝到脚底 / 身高 */
    const val K_FOOT = 0.04

    /** 眼到膝 / 身高 */
    const val K_EYE_TO_KNEE = 0.65

    /** 默认身高。v1 不采集，因此绝对距离只能展示不能控制（§6.2.1）。 */
    const val DEFAULT_HEIGHT_M = 1.70
}

/**
 * 测距（ADR-010）。
 *
 * 三件事必须同时成立才算「可用」：
 *  1. 输出带 sigma —— 没有置信区间就不是估计，是猜；
 *  2. sigma > 0.3m 时下游禁止显示米数；
 *  3. 降级链每一级明确增大 sigma，而不是静默给错数。
 */
class RangeEstimator(
    private val assumedHeightM: Double = BodyProportions.DEFAULT_HEIGHT_M,
) {

    data class Estimate(
        /** 绝对距离。range_tier=default 时禁止上屏。 */
        val distanceM: Double,
        val sigmaM: Double,
        val method: String,
        /** 主体像素占比 —— 真正的控制量，与身高无关。 */
        val alpha: Double,
        /** 外接框（归一化）。 */
        val bbox: com.scenemate.app.domain.NormRect,
        /** 0=不可用，1..3=降级链层级。 */
        val level: Int,
        val ankleVisible: Boolean,
    )

    fun estimate(pose: PoseObservation, frame: FrameGeometry): Estimate? {
        val hPx: Double
        val level: Int
        val ankleVisible: Boolean

        val nose = pose.nose?.takeIf { it.valid }
        val ankle = pickAnkle(pose)

        if (nose != null && ankle != null) {
            // 级 1：鼻-踝，最准
            val spanNorm = (ankle.y - nose.y).toDouble()
            if (spanNorm <= 0.02) return null
            hPx = spanNorm * frame.heightPx / (1.0 - BodyProportions.K_HEAD - BodyProportions.K_FOOT)
            level = 1
            ankleVisible = true
        } else {
            val eye = (pose.leftEye?.takeIf { it.valid } ?: pose.rightEye?.takeIf { it.valid })
            val knee = pickKnee(pose)
            if (eye != null && knee != null) {
                // 级 2：眼-膝，按小腿比例外推，额外 +3% 误差
                val spanNorm = (knee.y - eye.y).toDouble()
                if (spanNorm <= 0.02) return null
                hPx = spanNorm * frame.heightPx / BodyProportions.K_EYE_TO_KNEE
                level = 2
                ankleVisible = false
            } else {
                // 级 3：全部可见关键点的外接框，额外 +4% 误差
                val ys = pose.allPoints.filter { it.confidence > 0.3f }.map { it.y.toDouble() }
                if (ys.size < 4) return null
                val spanNorm = (ys.max() - ys.min())
                if (spanNorm <= 0.05) return null
                hPx = spanNorm * frame.heightPx / 0.82
                level = 3
                ankleVisible = false
            }
        }

        val alpha = hPx / frame.heightPx
        if (alpha <= 0.01 || alpha > 1.6) return null

        val distance = frame.focalPx * assumedHeightM / hPx
        if (distance <= 0.3 || distance > 25.0) return null

        // 相对误差：焦距 0.3% + 身高 5.1% + 姿态框 2.5% + 比例常数 2.0% + 俯仰 2.3% → RSS 6.5%
        val relative = when (level) {
            1 -> 0.065
            2 -> 0.065 + 0.030
            else -> 0.065 + 0.040
        }

        val bbox = bboxOf(pose, level, nose, ankle)

        return Estimate(
            distanceM = distance,
            sigmaM = distance * relative,
            method = when (level) {
                1 -> "pose_extent_nose_ankle"
                2 -> "pose_extent_eye_knee"
                else -> "pose_extent_all_points"
            },
            alpha = alpha,
            bbox = bbox,
            level = level,
            ankleVisible = ankleVisible,
        )
    }

    private fun pickAnkle(pose: PoseObservation): Landmark? {
        val l = pose.leftAnkle?.takeIf { it.valid }
        val r = pose.rightAnkle?.takeIf { it.valid }
        return when {
            l != null && r != null -> if (l.y > r.y) l else r
            else -> l ?: r
        }
    }

    private fun pickKnee(pose: PoseObservation): Landmark? {
        val l = pose.leftKnee?.takeIf { it.valid }
        val r = pose.rightKnee?.takeIf { it.valid }
        return when {
            l != null && r != null -> if (l.y > r.y) l else r
            else -> l ?: r
        }
    }

    private fun bboxOf(
        pose: PoseObservation,
        level: Int,
        nose: Landmark?,
        ankle: Landmark?,
    ): com.scenemate.app.domain.NormRect {
        val xs = mutableListOf<Float>()
        val ys = mutableListOf<Float>()
        pose.allPoints.filter { it.confidence > 0.3f }.forEach {
            xs += it.x; ys += it.y
        }
        if (xs.isEmpty()) {
            val cx = nose?.x ?: 0.5f
            val top = nose?.y ?: 0.2f
            val bottom = ankle?.y ?: 0.9f
            return com.scenemate.app.domain.NormRect(cx - 0.1f, top, cx + 0.1f, bottom)
        }
        val left = xs.min()
        val right = xs.max()
        val top = ys.min()
        val bottom = ys.max()
        // 级 3 的框不含头顶与脚底，补 3%
        val padY = if (level == 3) (bottom - top) * 0.03f else 0f
        return com.scenemate.app.domain.NormRect(
            left = left.coerceIn(0f, 1f),
            top = (top - padY).coerceIn(0f, 1f),
            right = right.coerceIn(0f, 1f),
            bottom = (bottom + padY).coerceIn(0f, 1f),
        )
    }

    /** 帧间抖动抑制：同一主体连续帧的 alpha 变化小于阈值时视为稳定。 */
    fun isStable(previousAlpha: Double, currentAlpha: Double, threshold: Double = 0.02): Boolean =
        abs(currentAlpha - previousAlpha) < threshold

    fun maxOf(a: Double, b: Double): Double = max(a, b)
}
