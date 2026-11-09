package com.scenemate.app.capability.guidance

import com.scenemate.app.capability.geometry.FrameGeometry
import com.scenemate.app.capability.geometry.RangeEstimator
import com.scenemate.app.capability.geometry.StanceSolver
import com.scenemate.app.capability.geometry.Templates
import com.scenemate.app.domain.Actor
import com.scenemate.app.domain.ConfidenceBand
import com.scenemate.app.domain.Delta
import com.scenemate.app.domain.InstructionWriter
import com.scenemate.app.domain.RangeTier
import com.scenemate.app.domain.StancePayload
import com.scenemate.app.domain.Suggestion
import com.scenemate.app.domain.SuggestionDomain
import com.scenemate.app.domain.SuggestionSource
import com.scenemate.app.domain.TierPolicy
import kotlin.math.abs
import kotlin.math.min

/**
 * 站位建议生产者（R01）。
 *
 * 置信度模型是**按构造可解释**的，不是 softmax 猜的：
 *  - 降级链层级决定基准（鼻-踝 0.92 / 眼-膝 0.80 / 全点 0.66）；
 *  - sigma 越大越扣分；
 *  - 多人再扣一点。
 * 因此 calibrated 恒为 true —— 这是几何质量的确定映射，不存在过自信问题。
 */
class StanceSuggestionProducer(
    private val solver: StanceSolver = StanceSolver(),
) {

    fun produce(
        sessionId: String,
        version: Long,
        nowMs: Long,
        estimate: RangeEstimator.Estimate,
        frame: FrameGeometry,
        actor: Actor,
        subjectPronoun: String,
        personCount: Int,
        headingDeg: Double,
    ): Suggestion? {
        val solution = solver.solve(estimate, frame, Templates.FULL_BODY, headingDeg)
        if (solution.insideDeadZone) return null

        val text = InstructionWriter.forStance(
            actor = actor,
            backM = solution.deltaBackM,
            lateralM = solution.deltaLateralM,
            subjectPronoun = subjectPronoun,
        ) ?: return null

        val confidence = confidenceFor(estimate, personCount)
        val band = ConfidenceBand.of(confidence)
        val delta = pickDelta(solution.deltaBackM, solution.deltaLateralM) ?: return null

        return Suggestion(
            id = "sg_${nowMs}_$version",
            sessionId = sessionId,
            version = version,
            domain = SuggestionDomain.STANCE,
            actor = actor,
            tier = TierPolicy.derive(band, calibrated = true, source = SuggestionSource.ON_DEVICE),
            confidence = confidence,
            band = band,
            calibrated = true,
            source = SuggestionSource.ON_DEVICE,
            modelVersion = "stance-geo-1.0.0",
            createdAtMs = nowMs,
            ttlMs = 1500,
            visibleText = text,
            payload = StancePayload(
                targetDistanceM = if (solution.rangeTier == RangeTier.DEFAULT) null else solution.distanceM,
                rangeSigmaM = solution.sigmaM,
                rangeMethod = estimate.method,
                rangeTier = solution.rangeTier,
                delta = delta,
                footprintNorm = solution.footprintNorm,
                subjectBoxNorm = solution.subjectBoxNorm,
                alpha = solution.alpha,
                alphaTarget = solution.alphaTarget,
                worldX = solution.worldX,
                worldZ = solution.worldZ,
                headingDeg = solution.headingDeg,
            ),
            informationGain = min(1.0, abs(solution.deltaBackM) / InstructionWriter.TWO_STEP_M),
        )
    }

    fun confidenceFor(estimate: RangeEstimator.Estimate, personCount: Int): Double {
        val base = when (estimate.level) {
            1 -> 0.92
            2 -> 0.80
            else -> 0.66
        }
        val sigmaPenalty = ((estimate.sigmaM - 0.15).coerceAtLeast(0.0) * 0.30).coerceAtMost(0.15)
        val multiPenalty = if (personCount >= 2) 0.05 else 0.0
        return (base - sigmaPenalty - multiPenalty).coerceIn(0.0, 0.99)
    }

    private fun pickDelta(backM: Double, lateralM: Double): Delta? {
        if (abs(backM) >= InstructionWriter.DEAD_ZONE_M) {
            return Delta(
                direction = if (backM > 0) com.scenemate.app.domain.Direction.BACK
                else com.scenemate.app.domain.Direction.FORWARD,
                magnitudeM = abs(backM),
                quantized = InstructionWriter.quantizedKey(abs(backM)),
            )
        }
        if (abs(lateralM) >= InstructionWriter.MIN_LATERAL_M) {
            return Delta(
                direction = if (lateralM > 0) com.scenemate.app.domain.Direction.RIGHT
                else com.scenemate.app.domain.Direction.LEFT,
                magnitudeM = abs(lateralM),
                quantized = InstructionWriter.quantizedKey(abs(lateralM)),
            )
        }
        return null
    }
}
