package com.scenemate.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArbiterTest {

    private val arbiter = Arbiter()

    private fun suggestion(
        id: String,
        domain: SuggestionDomain,
        band: ConfidenceBand,
        confidence: Double,
        version: Long = 1,
        createdAt: Long = 1000,
        ttl: Long = 1500,
        source: SuggestionSource = SuggestionSource.ON_DEVICE,
        text: String = "让她后退半步",
        affected: Set<ParamKey> = emptySet(),
        gain: Double = 0.5,
    ) = Suggestion(
        id = id,
        sessionId = "ss",
        version = version,
        domain = domain,
        actor = Actor.SUBJECT,
        tier = TierPolicy.derive(band, calibrated = true, source = source),
        confidence = confidence,
        band = band,
        calibrated = true,
        source = source,
        modelVersion = "test",
        createdAtMs = createdAt,
        ttlMs = ttl,
        visibleText = text,
        affectedParams = affected,
        informationGain = gain,
    )

    @Test
    fun `同域弱于强时淘汰 hint`() {
        val strong = suggestion("a", SuggestionDomain.STANCE, ConfidenceBand.HIGH, 0.92)
        val hint = suggestion("b", SuggestionDomain.STANCE, ConfidenceBand.LOW, 0.5)
        val result = arbiter.arbitrate(listOf(strong, hint), emptySet(), nowMs = 1200)
        assertEquals("a", result.top?.id)
        assertTrue(result.rejected.any { it.reason == "weaker_than_same_domain" })
    }

    @Test
    fun `站位优先于参数`() {
        val stance = suggestion("stance", SuggestionDomain.STANCE, ConfidenceBand.HIGH, 0.88)
        val params = suggestion(
            "params", SuggestionDomain.PARAMS, ConfidenceBand.HIGH, 0.88,
            text = "参数已就绪",
        )
        val result = arbiter.arbitrate(listOf(params, stance), emptySet(), nowMs = 1200)
        assertEquals("stance", result.top?.id)
    }

    @Test
    fun `过期建议被淘汰`() {
        val expired = suggestion("old", SuggestionDomain.STANCE, ConfidenceBand.HIGH, 0.95, ttl = 1000)
        val result = arbiter.arbitrate(listOf(expired), emptySet(), nowMs = 5000)
        assertNull(result.top)
        assertTrue(result.rejected.any { it.reason == "ttl_expired" })
    }

    @Test
    fun `参数被全部锁定后该建议被淘汰`() {
        val params = suggestion(
            "params", SuggestionDomain.PARAMS, ConfidenceBand.MID, 0.75,
            text = "参数已就绪", affected = setOf(ParamKey.ISO, ParamKey.SHUTTER),
        )
        val result = arbiter.arbitrate(
            listOf(params),
            lockedParams = setOf(ParamKey.ISO, ParamKey.SHUTTER),
            nowMs = 1200,
        )
        assertNull(result.top)
        assertTrue(result.rejected.any { it.reason == "authority_locked" })
    }

    @Test
    fun `只锁一个参数时建议仍放行`() {
        val params = suggestion(
            "params", SuggestionDomain.PARAMS, ConfidenceBand.MID, 0.75,
            text = "参数已就绪", affected = setOf(ParamKey.ISO, ParamKey.SHUTTER),
        )
        val result = arbiter.arbitrate(listOf(params), setOf(ParamKey.ISO), nowMs = 1200)
        assertEquals("params", result.top?.id)
    }

    @Test
    fun `信息增益高的建议胜出`() {
        val low = suggestion("low", SuggestionDomain.STANCE, ConfidenceBand.HIGH, 0.86, gain = 0.1)
        val high = suggestion("high", SuggestionDomain.STANCE, ConfidenceBand.HIGH, 0.86, gain = 0.95)
        val result = arbiter.arbitrate(listOf(low, high), emptySet(), nowMs = 1100)
        assertEquals("high", result.top?.id)
    }

    @Test
    fun `首屏永远只有一条`() {
        val many = (1..8).map {
            suggestion("s$it", SuggestionDomain.STANCE, ConfidenceBand.MID, 0.75, version = it.toLong())
        }
        val result = arbiter.arbitrate(many, emptySet(), nowMs = 1100)
        assertEquals(1, if (result.top != null) 1 else 0)
        assertEquals(7, result.others.size)
    }
}
