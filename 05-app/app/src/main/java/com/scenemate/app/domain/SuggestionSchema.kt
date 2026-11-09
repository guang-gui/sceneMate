package com.scenemate.app.domain

/**
 * TAD §8.1 的字段语义约束。schema 层强制，不靠 UI 自觉。
 * 命中任何一条即视为「不该上屏的建议」。
 */
object SuggestionSchema {

    const val MAX_TEXT_CHARS = 14

    /** actor=subject 时必须是第三人称，且面向摄影师（只有他看得到屏幕）。 */
    private val SUBJECT_ACTOR_PREFIX = Regex("^(让她|让他|让 ?TA|让ta)")

    /** TAD §6.4 禁止词表。命中即构建失败 / 校验失败。 */
    val BANNED_WORDS = listOf("构图", "光线", "氛围", "感觉", "注意", "尝试", "适当", "一些")

    /** delta.magnitude_m 的允许值域（量化后的相对位移）。 */
    private val ALLOWED_QUANTIZED = setOf("half_step", "full_step", "two_step", "none")

    fun validate(s: Suggestion): List<String> {
        val errors = mutableListOf<String>()

        if (s.confidence !in 0.0..1.0) errors += "confidence 越界: ${s.confidence}"
        if (s.visibleText.isBlank()) errors += "visible_text 为空"
        if (s.visibleText.length > MAX_TEXT_CHARS) {
            errors += "visible_text 超过 $MAX_TEXT_CHARS 字符: ${s.visibleText}"
        }
        if (s.actor == Actor.SUBJECT && !SUBJECT_ACTOR_PREFIX.containsMatchIn(s.visibleText)) {
            errors += "actor=subject 但文案不是第三人称: ${s.visibleText}"
        }
        val banned = BANNED_WORDS.filter { s.visibleText.contains(it) }
        if (banned.isNotEmpty()) errors += "visible_text 命中禁止词: $banned"

        // 推导字段一致性：tier 不得自由设置
        val derived = TierPolicy.derive(s.band, s.calibrated, s.source)
        if (derived != s.tier) errors += "tier 与 band/calibrated/source 不一致: ${s.tier} != $derived"

        if (s.source == SuggestionSource.VENDOR_API && (s.provider.isNullOrBlank() || s.model.isNullOrBlank())) {
            errors += "source=vendor_api 必须带 provider 与 model（否则无法归因）"
        }

        val p = s.payload
        if (s.domain == SuggestionDomain.STANCE && p == null) {
            errors += "domain=stance 必须有 payload"
        }
        if (p != null) {
            if (p.rangeTier == RangeTier.DEFAULT && p.targetDistanceM != null) {
                errors += "range_tier=default 时 target_distance_m 必须为 null"
            }
            if (p.rangeSigmaM > 0.3 && p.targetDistanceM != null) {
                errors += "range_sigma_m>0.3 时 target_distance_m 必须为 null"
            }
            val d = p.delta
            if (d != null && d.quantized !in ALLOWED_QUANTIZED) {
                errors += "delta.quantized 非法: ${d.quantized}"
            }
        }

        if (s.domain == SuggestionDomain.PARAMS) {
            val e = s.explain
            if (e == null) {
                errors += "domain=params 必须带 explain 四要素"
            } else {
                if (e.before.isBlank() || e.after.isBlank() || e.mechanism.isBlank() || e.benefit.isBlank()) {
                    errors += "explain 四要素不完整"
                }
            }
        }

        return errors
    }

    fun isValid(s: Suggestion): Boolean = validate(s).isEmpty()
}
