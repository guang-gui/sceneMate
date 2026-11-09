package com.scenemate.app.capability.perception

/**
 * 一帧的统计量。全部来自 YUV 平面与 CaptureResult，零模型、零延迟、断网可用。
 * 这是 v1 的**主实现**，不是兜底（ADR-001）。
 */
data class FrameStats(
    /** 0..255 */
    val meanLuma: Double = 128.0,
    /** 画面上 1/4 平均亮度，用来判逆光 */
    val topLuma: Double = 128.0,
    /** 画面下 1/4 平均亮度 */
    val bottomLuma: Double = 128.0,
    /** BT.601 色度，128 为中性 */
    val meanU: Double = 128.0,
    val meanV: Double = 128.0,
    val iso: Int = 0,
    val exposureNs: Long = 0L,
    val personCount: Int = 0,
    /** 设备横滚角（度），地平线倾斜用 */
    val rollDeg: Double = 0.0,
)

data class SceneLabels(
    val scene: String,
    val sceneConfidence: Double,
    val light: String,
    val lightConfidence: Double,
    val subject: String,
    val subjectConfidence: Double,
    val source: String = SOURCE_RULE,
) {
    companion object {
        const val SOURCE_RULE = "rule"
        const val SOURCE_VENDOR = "vendor_api"

        val UNKNOWN = SceneLabels(
            scene = "unknown", sceneConfidence = 0.0,
            light = "unknown", lightConfidence = 0.0,
            subject = "unknown", subjectConfidence = 0.0,
        )
    }

    /** 三标签取最小置信度作为整体置信度——木桶原则，不给虚假把握。 */
    val overallConfidence: Double
        get() = minOf(sceneConfidence, lightConfidence, subjectConfidence)

    val sceneDisplay: String
        get() = when (scene) {
            "sky_water" -> "海边/天空"
            "indoor_warm" -> "室内暖光"
            "foliage" -> "绿植/公园"
            "street" -> "街头"
            "night_city" -> "夜景街道"
            else -> "未识别"
        }

    val lightDisplay: String
        get() = when (light) {
            "backlit" -> "逆光"
            "low_light" -> "暗光"
            "normal" -> "顺光/平光"
            else -> "未知光线"
        }

    val subjectDisplay: String
        get() = when (subject) {
            "none" -> "无人物"
            "person" -> "1 人"
            "group" -> "2–3 人"
            "crowd" -> "多人"
            else -> "未知"
        }

    /** 胶囊文案：场景 + 光线，例「海边/天空 · 逆光」。 */
    val chipText: String
        get() = if (scene == "unknown") "识别中" else "$sceneDisplay · ${lightDisplay}"
}

/**
 * 端侧规则场景分类器（§6.5）。
 *
 * 三条自我约束：
 *  1. 置信度上限 0.82 —— 规则引擎没有资格宣称强引导，永远进不了 HIGH 档；
 *  2. 判不出就返回 unknown，不猜；
 *  3. 只输出标签，不输出任何数值（P7：大模型只给意图，规则同理）。
 */
object RuleSceneClassifier {

    private const val RULE_CONFIDENCE_CAP = 0.82
    private const val BACKLIT_DELTA = 55.0
    private const val LOW_LIGHT_LUMA = 45.0

    fun classify(stats: FrameStats): SceneLabels {
        val (scene, sceneConf) = classifyScene(stats)
        val (light, lightConf) = classifyLight(stats)
        val (subject, subjectConf) = classifySubject(stats.personCount)

        return SceneLabels(
            scene = scene,
            sceneConfidence = sceneConf.coerceAtMost(RULE_CONFIDENCE_CAP),
            light = light,
            lightConfidence = lightConf.coerceAtMost(RULE_CONFIDENCE_CAP),
            subject = subject,
            subjectConfidence = subjectConf.coerceAtMost(RULE_CONFIDENCE_CAP),
            source = SceneLabels.SOURCE_RULE,
        )
    }

    private fun classifyScene(stats: FrameStats): Pair<String, Double> {
        val luma = stats.meanLuma
        val u = stats.meanU - 128.0 // 正 = 偏蓝
        val v = stats.meanV - 128.0 // 正 = 偏红

        if (luma < LOW_LIGHT_LUMA) {
            return "night_city" to (0.60 + (LOW_LIGHT_LUMA - luma) / 200.0)
        }
        // 蓝主导 + 高亮度：天空/水面（海边日落、湖边、晴天户外）
        if (u > 6.0 && luma > 110.0) {
            return "sky_water" to (0.55 + (u / 40.0)).coerceAtMost(0.80)
        }
        // 绿主导：植被
        if (v < -4.0 && u < 4.0) {
            return "foliage" to (0.55 + (-v / 40.0)).coerceAtMost(0.78)
        }
        // 红/暖主导 + 中低亮度：室内暖光
        if (v > 8.0 && luma < 130.0) {
            return "indoor_warm" to (0.55 + (v / 45.0)).coerceAtMost(0.78)
        }
        if (v > 4.0) return "street" to 0.58
        return "street" to 0.52
    }

    private fun classifyLight(stats: FrameStats): Pair<String, Double> {
        if (stats.meanLuma < LOW_LIGHT_LUMA) {
            return "low_light" to (0.65 + (LOW_LIGHT_LUMA - stats.meanLuma) / 150.0)
        }
        val delta = stats.topLuma - stats.bottomLuma
        if (delta > BACKLIT_DELTA) {
            return "backlit" to (0.60 + (delta - BACKLIT_DELTA) / 120.0).coerceAtMost(0.80)
        }
        return "normal" to 0.62
    }

    private fun classifySubject(personCount: Int): Pair<String, Double> = when {
        personCount <= 0 -> "none" to 0.80
        personCount == 1 -> "person" to 0.80
        personCount <= 3 -> "group" to 0.72
        else -> "crowd" to 0.65
    }
}
