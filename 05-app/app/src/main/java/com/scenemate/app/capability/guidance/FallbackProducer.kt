package com.scenemate.app.capability.guidance

import com.scenemate.app.domain.Actor
import com.scenemate.app.domain.ConfidenceBand
import com.scenemate.app.domain.Suggestion
import com.scenemate.app.domain.SuggestionDomain
import com.scenemate.app.domain.SuggestionSource
import com.scenemate.app.domain.Tier

/**
 * 2 秒兜底（R14）。
 *
 * 在 session_start + 2000ms 无条件产出一条 source=fallback 的构图建议。
 * 它优先于后续低置信建议——防止「先给了能用的，又被更差的替换」。
 * 文案必须面向摄影师（只有他看得到屏幕），且不含任何形容词。
 */
object FallbackProducer {

    const val TRIGGER_AFTER_MS = 2000L

    fun produce(sessionId: String, version: Long, nowMs: Long, levelDeg: Double = 0.0): Suggestion {
        val text = if (levelDeg > 1.5) "你把手机端平" else "让她站到画面中间"
        return Suggestion(
            id = "sg_fallback_${nowMs}_$version",
            sessionId = sessionId,
            version = version,
            domain = SuggestionDomain.COMPOSITION,
            actor = if (levelDeg > 1.5) Actor.PHOTOGRAPHER else Actor.SUBJECT,
            tier = Tier.WEAK,
            confidence = 0.70,
            band = ConfidenceBand.MID,
            calibrated = true,
            source = SuggestionSource.FALLBACK,
            modelVersion = "fallback-1.0.0",
            createdAtMs = nowMs,
            ttlMs = 6000,
            visibleText = text,
            informationGain = 0.35,
        )
    }
}
