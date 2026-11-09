package com.scenemate.app.domain

/**
 * 建议域。domain_weight 决定首屏优先级（TAD ADR-009）：
 * 站位是不可逆决策（站错了整张废），参数可回退，所以站位权重最高。
 */
enum class SuggestionDomain(val weight: Double) {
    STANCE(1.0),
    PARAMS(0.8),
    COMPOSITION(0.6),
    POSE(0.4),
    WARNING(0.5),
}

/** 指令对象：拿手机的是摄影师（你），画面里的是被摄者（她）。TAD §6.4 */
enum class Actor { SUBJECT, PHOTOGRAPHER }

/** 表达强度。由 confidence_band + calibrated 推导，端侧不得自由设置（§8.1）。 */
enum class Tier { STRONG, WEAK, HINT }

enum class ConfidenceBand {
    HIGH, MID, LOW;

    companion object {
        const val HIGH_MIN = 0.85
        const val MID_MIN = 0.70

        fun of(confidence: Double): ConfidenceBand = when {
            confidence >= HIGH_MIN -> HIGH
            confidence >= MID_MIN -> MID
            else -> LOW
        }
    }
}

enum class SuggestionSource { ON_DEVICE, VENDOR_API, FALLBACK }

/** 精度档位。语义名，不用 A/B/C，避免与设备分级撞名（§6.2）。 */
enum class RangeTier { PRECISE, CALIBRATED, DEFAULT }

enum class Direction {
    FORWARD, BACK, LEFT, RIGHT, CROUCH, STAND_TALL, RAISE, LOWER
}

enum class ParamKey(val label: String) {
    ISO("ISO"),
    SHUTTER("快门"),
    EV("EV"),
    WB("白平衡"),
}

/** 相对位移。magnitude_m 永远是相对量，绝不当绝对距离用。 */
data class Delta(
    val direction: Direction,
    val magnitudeM: Double,
    val quantized: String,
)

data class NormRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /** 外扩 ratio 倍（R01：主体包围盒外扩 15% 内不得绘制文字）。 */
    fun inflated(ratio: Float): NormRect {
        val dx = width * ratio
        val dy = height * ratio
        return NormRect(left - dx, top - dy, right + dx, bottom + dy)
    }
}

data class StancePayload(
    /** range_tier=default 时必须为 null；sigma>0.3 时也必须为 null（§8.1）。 */
    val targetDistanceM: Double?,
    val rangeSigmaM: Double,
    val rangeMethod: String,
    val rangeTier: RangeTier,
    val delta: Delta?,
    val footprintNorm: NormRect?,
    val subjectBoxNorm: NormRect?,
    /** 控制量：主体像素占比（与身高无关）。 */
    val alpha: Double,
    val alphaTarget: Double,
    val worldX: Double,
    val worldZ: Double,
    val headingDeg: Double,
)

/** 参数解释四要素，type=PARAMS 时全部必填（R03）。 */
data class Explain(
    val before: String,
    val after: String,
    val mechanism: String,
    val benefit: String,
)

/**
 * 建议是一等数据对象，不是代码分支（A3）。
 * 字段语义约束见 TAD §8.1，由 [SuggestionSchema] 强制。
 */
data class Suggestion(
    val id: String,
    val sessionId: String,
    val version: Long,
    val domain: SuggestionDomain,
    val actor: Actor,
    val tier: Tier,
    val confidence: Double,
    val band: ConfidenceBand,
    val calibrated: Boolean,
    val source: SuggestionSource,
    val modelVersion: String,
    val createdAtMs: Long,
    val ttlMs: Long,
    val visibleText: String,
    val payload: StancePayload? = null,
    val explain: Explain? = null,
    val provider: String? = null,
    val model: String? = null,
    val actions: List<String> = listOf("apply", "dismiss"),
    /** 该建议会写哪些参数。Arbiter 用它判断是否与用户锁定冲突。 */
    val affectedParams: Set<ParamKey> = emptySet(),
    /** Arbiter 打分用：这条建议带来多少信息增量（0..1）。 */
    val informationGain: Double = 0.5,
) {
    fun isExpired(nowMs: Long): Boolean = nowMs - createdAtMs > ttlMs
}

/** tier 的唯一推导入口。 */
object TierPolicy {
    fun derive(band: ConfidenceBand, calibrated: Boolean, source: SuggestionSource): Tier = when {
        source == SuggestionSource.FALLBACK -> Tier.WEAK
        // 厂商大模型输出是不稳定信号（ADR-013 实测），永远不给强引导
        source == SuggestionSource.VENDOR_API -> Tier.HINT
        !calibrated -> Tier.HINT
        band == ConfidenceBand.HIGH -> Tier.STRONG
        band == ConfidenceBand.MID -> Tier.WEAK
        else -> Tier.HINT
    }
}
