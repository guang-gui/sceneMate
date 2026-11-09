package com.scenemate.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionStoreTest {

    private var now = 10_000L
    private val store = SuggestionStore { now }

    private fun suggestion(
        version: Long,
        confidence: Double,
        domain: SuggestionDomain = SuggestionDomain.STANCE,
        source: SuggestionSource = SuggestionSource.ON_DEVICE,
        ttl: Long = 1500,
    ): Suggestion = Suggestion(
        id = "sg_$version",
        sessionId = "ss",
        version = version,
        domain = domain,
        actor = Actor.SUBJECT,
        tier = TierPolicy.derive(ConfidenceBand.of(confidence), true, source),
        confidence = confidence,
        band = ConfidenceBand.of(confidence),
        calibrated = true,
        source = source,
        modelVersion = "test",
        createdAtMs = now,
        ttlMs = ttl,
        visibleText = "让她后退半步",
    )

    @Test
    fun `版本必须单调递增`() {
        assertTrue(store.offer(suggestion(5, 0.9)))
        assertFalse(store.offer(suggestion(5, 0.95)))
        assertFalse(store.offer(suggestion(4, 0.99)))
    }

    @Test
    fun `置信度提升不足 0_05 不替换`() {
        assertTrue(store.offer(suggestion(1, 0.86)))
        now += 1300 // 已过最小展示时长，但仍在 ttl 内
        assertFalse(store.offer(suggestion(2, 0.88)))
    }

    @Test
    fun `置信度提升足够则替换`() {
        assertTrue(store.offer(suggestion(1, 0.80)))
        now += 1300
        assertTrue(store.offer(suggestion(2, 0.90)))
    }

    @Test
    fun `最小展示 1_2s 防闪烁`() {
        assertTrue(store.offer(suggestion(1, 0.80)))
        now += 300
        // 置信度提升足够，但上屏不足 1.2s
        assertFalse(store.offer(suggestion(2, 0.95)))
        now += 1000
        assertTrue(store.offer(suggestion(3, 0.95)))
    }

    @Test
    fun `建议域变化可以立刻切换`() {
        assertTrue(store.offer(suggestion(1, 0.80, SuggestionDomain.STANCE)))
        now += 100
        assertTrue(store.offer(suggestion(2, 0.70, SuggestionDomain.PARAMS)))
    }

    @Test
    fun `兜底建议不被后续弱建议顶掉`() {
        assertTrue(store.offer(suggestion(1, 0.70, SuggestionDomain.COMPOSITION, SuggestionSource.FALLBACK)))
        now += 3000
        assertFalse(store.offer(suggestion(2, 0.72, SuggestionDomain.PARAMS)))
        assertEquals(1L, store.current()?.version)
    }

    @Test
    fun `强引导可以顶掉兜底`() {
        assertTrue(store.offer(suggestion(1, 0.70, SuggestionDomain.COMPOSITION, SuggestionSource.FALLBACK)))
        now += 3000
        assertTrue(store.offer(suggestion(2, 0.93, SuggestionDomain.STANCE)))
        assertEquals(2L, store.current()?.version)
    }

    @Test
    fun `ttl 到期后必定替换`() {
        assertTrue(store.offer(suggestion(1, 0.80, ttl = 500)))
        now += 900
        assertTrue(store.offer(suggestion(2, 0.80)))
    }
}
