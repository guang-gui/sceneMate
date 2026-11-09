package com.scenemate.app.capability.guidance

import com.scenemate.app.capability.perception.SceneLabels
import com.scenemate.app.domain.Actor
import com.scenemate.app.domain.ConfidenceBand
import com.scenemate.app.domain.Explain
import com.scenemate.app.domain.ParamKey
import com.scenemate.app.domain.Suggestion
import com.scenemate.app.domain.SuggestionDomain
import com.scenemate.app.domain.SuggestionSource
import com.scenemate.app.domain.TierPolicy

/** 当前相机参数（用户可见的那一套）。 */
data class CameraParamState(
    val iso: Int = 100,
    val exposureNs: Long = 8_000_000L, // 1/125s
    val evSteps: Int = 0,              // 1/3 档为单位
    val wbKelvin: Int? = null,         // null = 自动
) {
    fun shutterDisplay(): String {
        val denom = (1_000_000_000.0 / exposureNs.coerceAtLeast(1L)).toInt()
        return "1/${denom}s"
    }

    fun evDisplay(): String = ParamPlanner.formatEv(evSteps)

    fun wbDisplay(): String = wbKelvin?.let { "${it}K" } ?: "自动"
}

/** 一条要下发给相机的参数计划。null 字段表示「不动这一项」。 */
data class ParamPlan(
    val iso: Int? = null,
    val exposureNs: Long? = null,
    val evSteps: Int? = null,
    val wbKelvin: Int? = null,
) {
    val affected: Set<ParamKey>
        get() = buildSet {
            if (iso != null) add(ParamKey.ISO)
            if (exposureNs != null) add(ParamKey.SHUTTER)
            if (evSteps != null) add(ParamKey.EV)
            if (wbKelvin != null) add(ParamKey.WB)
        }

    val isEmpty: Boolean get() = affected.isEmpty()
}

/**
 * 参数推荐（R03）。全部是确定性规则，**没有一处来自大模型**——
 * 大模型只给意图，数值必须由确定性算法给（P7）。
 */
object ParamPlanner {

    const val EV_STEP_THIRD = 3

    fun plan(
        labels: SceneLabels,
        current: CameraParamState,
        maxIso: Int = 3200,
        maxExposureNs: Long = 66_000_000L, // 1/15s
    ): ParamPlan = when {
        labels.light == "backlit" -> ParamPlan(
            evSteps = EV_STEP_THIRD * 2, // +0.7EV，1/3 档制下取整到 +2/3
            iso = null,
            exposureNs = null,
        )

        labels.light == "low_light" -> ParamPlan(
            iso = (current.iso * 2).coerceIn(400, maxIso),
            exposureNs = (current.exposureNs * 2).coerceIn(1L, maxExposureNs),
            evSteps = 0,
        )

        labels.scene == "indoor_warm" -> ParamPlan(
            wbKelvin = 3200,
            iso = current.iso.coerceAtMost(400),
        )

        else -> ParamPlan(
            iso = current.iso.coerceIn(100, 200),
            exposureNs = 8_000_000L, // 1/125s
            evSteps = 0,
        )
    }

    fun explain(labels: SceneLabels, current: CameraParamState, plan: ParamPlan): Explain {
        val before = buildString {
            append("ISO ${current.iso} · ${current.shutterDisplay()} · EV ${current.evDisplay()}")
            if (plan.wbKelvin != null) append(" · ${current.wbDisplay()}")
        }
        val after = buildString {
            append("ISO ${plan.iso ?: current.iso}")
            append(" · ")
            append(plan.exposureNs?.let { ns -> "1/${(1_000_000_000.0 / ns).toInt()}s" } ?: current.shutterDisplay())
            append(" · EV ")
            append(plan.evSteps?.let { formatEv(it) } ?: current.evDisplay())
            if (plan.wbKelvin != null) append(" · ${plan.wbKelvin}K")
        }

        val mechanism = when {
            labels.light == "backlit" -> "提高曝光补偿，让测光权重向主体倾斜"
            labels.light == "low_light" -> "提高传感器增益并延长曝光，换取进光量"
            labels.scene == "indoor_warm" -> "把白平衡拉回中性，抵消暖光源偏色"
            else -> "降低增益、提高快门，用更短的曝光冻结动作"
        }

        val benefit = when {
            labels.light == "backlit" -> "人脸提亮约 2/3 档，背景高光仍可保留"
            labels.light == "low_light" -> "暗部噪点可控，画面亮度提升约 1 档"
            labels.scene == "indoor_warm" -> "肤色回到中性，不再发黄"
            else -> "噪点降低，发丝与手部不易糊"
        }

        return Explain(before = before, after = after, mechanism = mechanism, benefit = benefit)
    }

    /** EV 用 1/3 档为单位存，展示时统一成「0 / +0.7 / −0.3」。 */
    fun formatEv(steps: Int): String = when {
        steps == 0 -> "0"
        steps > 0 -> "+%.1f".format(steps / 3.0)
        else -> "−%.1f".format(-steps / 3.0)
    }
}

class ParamSuggestionProducer {

    fun produce(
        sessionId: String,
        version: Long,
        nowMs: Long,
        labels: SceneLabels,
        current: CameraParamState,
        manualSensorSupported: Boolean,
    ): Suggestion? {
        if (!manualSensorSupported) return null
        if (labels.light == "unknown" && labels.scene == "unknown") return null

        val plan = ParamPlanner.plan(labels, current)
        if (plan.isEmpty) return null

        val confidence = (labels.overallConfidence - 0.05).coerceIn(0.0, 0.84)
        val band = ConfidenceBand.of(confidence)

        return Suggestion(
            id = "sg_param_${nowMs}_$version",
            sessionId = sessionId,
            version = version,
            domain = SuggestionDomain.PARAMS,
            actor = Actor.PHOTOGRAPHER,
            tier = TierPolicy.derive(band, calibrated = true, source = SuggestionSource.ON_DEVICE),
            confidence = confidence,
            band = band,
            calibrated = true,
            source = SuggestionSource.ON_DEVICE,
            modelVersion = "param-rule-1.0.0",
            createdAtMs = nowMs,
            ttlMs = 4000,
            visibleText = "参数已就绪",
            explain = ParamPlanner.explain(labels, current, plan),
            affectedParams = plan.affected,
            informationGain = 0.4,
        )
    }
}
