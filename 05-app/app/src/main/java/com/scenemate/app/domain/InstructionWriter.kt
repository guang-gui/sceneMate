package com.scenemate.app.domain

/**
 * 指令生成（TAD §6.4）——「只给方向和距离，不给形容词」的机械保证。
 *
 * 模板 DSL：{actor}{direction}{magnitude}
 *   actor     ∈ {你, 让她, 让他, 让 TA}
 *   direction ∈ {向前, 后退, 向左, 向右, 蹲低, 站高, 举高, 放低}
 *   magnitude ∈ {半步, 一步, 两步, 0.6m, 1.0m, 20cm, 40cm}
 * 约束：1 个 actor + 1 个 direction + 1 个 magnitude，不允许叠加。
 */
object InstructionWriter {

    /** 半步 ≈ 0.35m，一步 ≈ 0.65m，两步 ≈ 1.3m（§6.3 量化表）。 */
    const val HALF_STEP_M = 0.35
    const val FULL_STEP_M = 0.65
    const val TWO_STEP_M = 1.30

    /** 死区：Android 首发放宽到 0.25m，否则框会在阈值附近来回跳。 */
    const val DEAD_ZONE_M = 0.25

    /** 横向位移低于 0.3m 不提示（DSL 明令禁止）。 */
    const val MIN_LATERAL_M = 0.30

    fun actorPrefix(actor: Actor, subjectPronoun: String = "她"): String = when (actor) {
        Actor.PHOTOGRAPHER -> "你"
        Actor.SUBJECT -> "让$subjectPronoun"
    }

    fun directionWord(direction: Direction): String = when (direction) {
        Direction.FORWARD -> "向前"
        Direction.BACK -> "后退"
        Direction.LEFT -> "向左"
        Direction.RIGHT -> "向右"
        Direction.CROUCH -> "蹲低"
        Direction.STAND_TALL -> "站高"
        Direction.RAISE -> "举高"
        Direction.LOWER -> "放低"
    }

    /** 量化：位移 → 半步/一步/两步。超过两步就不再量化，改用相对米数（仍是相对量）。 */
    fun quantizeMagnitude(magnitudeM: Double): String = when {
        magnitudeM < HALF_STEP_M * 1.4 -> "半步"
        magnitudeM < FULL_STEP_M * 1.4 -> "一步"
        magnitudeM < TWO_STEP_M * 1.2 -> "两步"
        else -> String.format("%.1fm", magnitudeM)
    }

    fun quantizedKey(magnitudeM: Double): String = when {
        magnitudeM < HALF_STEP_M * 1.4 -> "half_step"
        magnitudeM < FULL_STEP_M * 1.4 -> "full_step"
        magnitudeM < TWO_STEP_M * 1.2 -> "two_step"
        else -> "none"
    }

    fun write(actor: Actor, direction: Direction, magnitudeM: Double, subjectPronoun: String = "她"): String {
        val magnitude = quantizeMagnitude(magnitudeM)
        return actorPrefix(actor, subjectPronoun) + directionWord(direction) + magnitude
    }

    fun write(actor: Actor, delta: Delta, subjectPronoun: String = "她"): String =
        write(actor, delta.direction, delta.magnitudeM, subjectPronoun)

    /**
     * 首屏指令的唯一出口。位移落在死区内返回 null——宁可不说话，也不要说错一次。
     */
    fun forStance(
        actor: Actor,
        backM: Double,
        lateralM: Double,
        subjectPronoun: String = "她",
    ): String? {
        // 后退优先：距离是最不可逆的一维
        if (kotlin.math.abs(backM) >= DEAD_ZONE_M) {
            val dir = if (backM > 0) Direction.BACK else Direction.FORWARD
            return write(actor, dir, kotlin.math.abs(backM), subjectPronoun)
        }
        if (kotlin.math.abs(lateralM) >= MIN_LATERAL_M) {
            val dir = if (lateralM > 0) Direction.RIGHT else Direction.LEFT
            return write(actor, dir, kotlin.math.abs(lateralM), subjectPronoun)
        }
        return null
    }
}

/** 指令模板 lint：命中禁止词即视为不合格（CI 里当作构建失败）。 */
object InstructionLint {
    fun check(text: String): List<String> =
        SuggestionSchema.BANNED_WORDS.filter { text.contains(it) }.map { "禁止词: $it" }

    fun hasAdjectives(text: String): Boolean = check(text).isNotEmpty()
}
