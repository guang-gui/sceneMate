package com.scenemate.app.domain

/**
 * 渐进增强与单调性（TAD §5.3）——解决「框乱跳」。
 *
 * version 单调递增；只接受 version > current，且满足以下之一才替换：
 *   a) 置信度提升 >= 0.05
 *   b) 建议域变化（如 stance → params）
 *   c) 当前建议 ttl 到期
 * 最小展示时长 1.2s（防闪烁）。
 */
class SuggestionStore(private val clock: () -> Long = System::currentTimeMillis) {

    companion object {
        const val MIN_DISPLAY_MS = 1200L
        const val CONFIDENCE_GAIN_THRESHOLD = 0.05
    }

    private var current: Suggestion? = null
    private var shownAtMs: Long = 0L

    fun current(): Suggestion? = current

    /** @return true 表示已上屏替换。 */
    fun offer(incoming: Suggestion): Boolean {
        val now = clock()
        val cur = current

        if (cur == null) {
            current = incoming
            shownAtMs = now
            return true
        }
        if (incoming.version <= cur.version) return false

        // R14：兜底建议优先于后续低置信建议——防止「先给了能用的，又被更差的替换」
        if (cur.source == SuggestionSource.FALLBACK &&
            incoming.source != SuggestionSource.FALLBACK &&
            incoming.tier != Tier.STRONG
        ) {
            return false
        }

        val expired = cur.isExpired(now)
        val domainChanged = incoming.domain != cur.domain
        val confidenceUp = incoming.confidence - cur.confidence >= CONFIDENCE_GAIN_THRESHOLD
        // 最小展示时长不得长于建议自身的 ttl，否则短命建议永远换不掉
        val minDisplay = minOf(MIN_DISPLAY_MS, cur.ttlMs)
        val displayedLongEnough = now - shownAtMs >= minDisplay

        val ruleSatisfied = expired || domainChanged || confidenceUp
        if (!ruleSatisfied) return false
        if (!displayedLongEnough && !domainChanged) return false

        current = incoming
        shownAtMs = now
        return true
    }

    fun clear() {
        current = null
        shownAtMs = 0L
    }

    fun shownDurationMs(): Long = if (current == null) 0L else clock() - shownAtMs
}
