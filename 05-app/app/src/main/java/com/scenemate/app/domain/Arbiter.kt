package com.scenemate.app.domain

/**
 * 建议仲裁器（ADR-009）。
 *
 * 硬约束淘汰 + 打分排序，机械保证「首屏任何时刻只有 1 条指令」——
 * 这条不能靠 UI 自觉，否则新增一类建议就会破。
 */
class Arbiter {

    data class Rejection(val suggestion: Suggestion, val reason: String)

    data class Result(
        val top: Suggestion?,
        val others: List<Suggestion>,
        val rejected: List<Rejection>,
    )

    /**
     * @param candidates 本帧所有生产者产出的建议
     * @param lockedParams 已被用户锁定的参数（AI 不得再碰）
     * @param nowMs 当前时刻
     */
    fun arbitrate(
        candidates: List<Suggestion>,
        lockedParams: Set<ParamKey>,
        nowMs: Long,
    ): Result {
        val rejected = mutableListOf<Rejection>()

        // 1) ttl 过期
        var alive = candidates.filter {
            if (it.isExpired(nowMs)) {
                rejected += Rejection(it, "ttl_expired")
                false
            } else true
        }

        // 2) 与 Authority 冲突：建议要写的参数全部已被用户锁定 → 淘汰
        //    只锁了一部分时仍放行，由 Actuator 在写入前逐参数二次拦截（§5.6 写入协议）
        alive = alive.filter {
            val conflict = it.affectedParams.isNotEmpty() && it.affectedParams.all { p -> p in lockedParams }
            if (conflict) {
                rejected += Rejection(it, "authority_locked")
                false
            } else true
        }

        // 3) tier==hint 且同域存在 tier>=weak → 淘汰
        val byDomain = alive.groupBy { it.domain }
        alive = alive.filter { s ->
            if (s.tier == Tier.HINT && byDomain[s.domain].orEmpty().any { it.tier != Tier.HINT }) {
                rejected += Rejection(s, "weaker_than_same_domain")
                false
            } else true
        }

        val scored = alive.map { it to score(it, nowMs) }.sortedByDescending { it.second }
        val top = scored.firstOrNull()?.first
        val others = scored.drop(1).map { it.first }

        return Result(top, others, rejected)
    }

    /** score = 0.35*confidence + 0.30*information_gain + 0.20*recency + 0.15*domain_weight */
    fun score(s: Suggestion, nowMs: Long): Double {
        val age = (nowMs - s.createdAtMs).coerceAtLeast(0L)
        val recency = (1.0 - age.toDouble() / s.ttlMs.coerceAtLeast(1L)).coerceIn(0.0, 1.0)
        return 0.35 * s.confidence +
            0.30 * s.informationGain.coerceIn(0.0, 1.0) +
            0.20 * recency +
            0.15 * s.domain.weight
    }
}
