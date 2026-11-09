package com.scenemate.app.domain

/** 锁定状态的持久化端口（Android 侧用 SharedPreferences 实现）。 */
interface LockPersistence {
    fun load(): Set<ParamKey>
    fun save(locked: Set<ParamKey>)
}

/**
 * 参数权威状态机（ADR-002 / R05）。
 *
 * AI 写参数只有 [applyAI] 一个入口；用户锁定后该参数在本会话内 AI 写入成功次数必须为 0。
 * 这把 R05 从「靠自觉」变成「靠机器」——CI 里用属性测试断言。
 */
class AuthorityStore(
    private val persistence: LockPersistence? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    enum class State { AI_OWNED, USER_LOCKED }

    enum class AuditAction { APPLIED, REJECTED, USER_OVERRIDE, RESTORED }

    enum class ApplyResult { APPLIED, REJECTED_LOCKED }

    data class AuditEntry(
        val atMs: Long,
        val param: ParamKey,
        val action: AuditAction,
        val oldValue: String?,
        val newValue: String?,
        val reason: String,
    )

    private val locked: MutableSet<ParamKey> = persistence?.load()?.toMutableSet() ?: mutableSetOf()
    private val audit: MutableList<AuditEntry> = mutableListOf()

    /** R05 埋点断言用：被拒绝的 AI 写入次数。v1 上线后此值应恒为 0。 */
    var rejectedWriteCount: Int = 0
        private set

    var appliedWriteCount: Int = 0
        private set

    fun state(param: ParamKey): State = if (param in locked) State.USER_LOCKED else State.AI_OWNED

    fun lockedParams(): Set<ParamKey> = locked.toSet()

    /** 用户手势（拖动滑轨 / 点锁定）。 */
    fun lock(param: ParamKey, reason: String = "user_gesture") {
        if (locked.add(param)) {
            persist()
            audit += AuditEntry(clock(), param, AuditAction.USER_OVERRIDE, null, null, reason)
        }
    }

    /** 用户点「恢复默认」。 */
    fun unlock(param: ParamKey) {
        if (locked.remove(param)) {
            persist()
            audit += AuditEntry(clock(), param, AuditAction.RESTORED, null, null, "user_restore")
        }
    }

    fun unlockAll() {
        locked.clear()
        persist()
    }

    /**
     * AI 写参数的唯一入口。
     * 返回 APPLIED 时调用方才能真正下发到相机。
     */
    fun applyAI(param: ParamKey, oldValue: String?, newValue: String, reason: String): ApplyResult {
        if (state(param) == State.USER_LOCKED) {
            rejectedWriteCount++
            audit += AuditEntry(clock(), param, AuditAction.REJECTED, oldValue, newValue, reason)
            return ApplyResult.REJECTED_LOCKED
        }
        appliedWriteCount++
        audit += AuditEntry(clock(), param, AuditAction.APPLIED, oldValue, newValue, reason)
        return ApplyResult.APPLIED
    }

    fun auditLog(): List<AuditEntry> = audit.toList()

    private fun persist() {
        persistence?.save(locked.toSet())
    }
}
