package com.scenemate.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorityStoreTest {

    @Test
    fun `用户锁定后 AI 写入被拒绝并审计`() {
        val store = AuthorityStore()
        store.lock(ParamKey.ISO, "user_slider")

        val result = store.applyAI(ParamKey.ISO, "800", "100", "scene_rule")

        assertEquals(AuthorityStore.ApplyResult.REJECTED_LOCKED, result)
        assertEquals(1, store.rejectedWriteCount)
        assertEquals(0, store.appliedWriteCount)
        assertEquals(AuthorityStore.State.USER_LOCKED, store.state(ParamKey.ISO))
        assertTrue(store.auditLog().any { it.action == AuthorityStore.AuditAction.REJECTED })
    }

    @Test
    fun `未锁定参数可以写入`() {
        val store = AuthorityStore()
        assertEquals(
            AuthorityStore.ApplyResult.APPLIED,
            store.applyAI(ParamKey.SHUTTER, "1/60", "1/125", "scene_rule"),
        )
        assertEquals(1, store.appliedWriteCount)
    }

    @Test
    fun `恢复默认后 AI 重新获得控制权`() {
        val store = AuthorityStore()
        store.lock(ParamKey.WB)
        store.unlock(ParamKey.WB)
        assertEquals(AuthorityStore.State.AI_OWNED, store.state(ParamKey.WB))
        assertEquals(
            AuthorityStore.ApplyResult.APPLIED,
            store.applyAI(ParamKey.WB, "自动", "3200", "scene_rule"),
        )
    }

    @Test
    fun `锁定跨会话保持`() {
        val fake = object : LockPersistence {
            var saved: Set<ParamKey> = emptySet()
            override fun load(): Set<ParamKey> = saved
            override fun save(locked: Set<ParamKey>) {
                saved = locked
            }
        }
        val first = AuthorityStore(fake)
        first.lock(ParamKey.EV)

        val second = AuthorityStore(fake)
        assertEquals(AuthorityStore.State.USER_LOCKED, second.state(ParamKey.EV))
    }

    /**
     * 属性测试（CI 必跑）：随机交错「用户手势 / AI 写入」，断言
     * 「用户锁定后 AI 写入成功次数 == 0」。这把 R05 从靠自觉变成靠机器。
     */
    @Test
    fun `随机交错序列下锁定参数零次被写入`() {
        val random = java.util.Random(42)
        repeat(200) {
            val store = AuthorityStore()
            val lockedOnce = mutableSetOf<ParamKey>()
            val aiWritesAfterLock = mutableMapOf<ParamKey, Int>()

            repeat(200) {
                val key = ParamKey.entries[random.nextInt(ParamKey.entries.size)]
                when (random.nextInt(3)) {
                    0 -> {
                        store.lock(key, "gesture")
                        lockedOnce += key
                    }
                    1 -> {
                        val result = store.applyAI(key, "old", "new", "test")
                        if (key in lockedOnce && result == AuthorityStore.ApplyResult.APPLIED) {
                            aiWritesAfterLock[key] = (aiWritesAfterLock[key] ?: 0) + 1
                        }
                    }
                    else -> store.unlock(key).also { lockedOnce -= key }
                }
            }
            assertTrue("锁定参数被 AI 写入: $aiWritesAfterLock", aiWritesAfterLock.isEmpty())
        }
    }
}
