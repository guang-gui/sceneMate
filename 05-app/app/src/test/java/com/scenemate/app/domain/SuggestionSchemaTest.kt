package com.scenemate.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionSchemaTest {

    private fun stance(
        text: String = "让她后退半步",
        actor: Actor = Actor.SUBJECT,
        band: ConfidenceBand = ConfidenceBand.HIGH,
        source: SuggestionSource = SuggestionSource.ON_DEVICE,
        targetDistance: Double? = null,
        sigma: Double = 0.19,
        rangeTier: RangeTier = RangeTier.DEFAULT,
    ): Suggestion = Suggestion(
        id = "sg_test",
        sessionId = "ss_test",
        version = 1,
        domain = SuggestionDomain.STANCE,
        actor = actor,
        tier = TierPolicy.derive(band, calibrated = true, source = source),
        confidence = 0.91,
        band = band,
        calibrated = true,
        source = source,
        modelVersion = "test",
        createdAtMs = 0,
        ttlMs = 1500,
        visibleText = text,
        payload = StancePayload(
            targetDistanceM = targetDistance,
            rangeSigmaM = sigma,
            rangeMethod = "pose_extent_nose_ankle",
            rangeTier = rangeTier,
            delta = Delta(Direction.BACK, 0.45, "half_step"),
            footprintNorm = NormRect(0.4f, 0.7f, 0.5f, 0.76f),
            subjectBoxNorm = NormRect(0.35f, 0.2f, 0.6f, 0.8f),
            alpha = 0.59,
            alphaTarget = 0.72,
            worldX = 0.0,
            worldZ = 3.0,
            headingDeg = 0.0,
        ),
    )

    @Test
    fun `合法站位建议通过校验`() {
        assertTrue(SuggestionSchema.validate(stance()).toString(), SuggestionSchema.isValid(stance()))
    }

    @Test
    fun `actor=subject 时文案必须是第三人称`() {
        val errors = SuggestionSchema.validate(stance(text = "后退半步"))
        assertTrue(errors.any { it.contains("第三人称") })
    }

    @Test
    fun `命中禁止词即不合格`() {
        val errors = SuggestionSchema.validate(stance(text = "让她注意构图"))
        assertTrue(errors.any { it.contains("禁止词") })
    }

    @Test
    fun `文案长度上限 14`() {
        val errors = SuggestionSchema.validate(stance(text = "让她向后走一点点然后再向左挪一小步"))
        assertTrue(errors.any { it.contains("14") })
    }

    @Test
    fun `range_tier=default 时不得带绝对距离`() {
        val errors = SuggestionSchema.validate(stance(targetDistance = 2.4))
        assertTrue(errors.any { it.contains("target_distance_m") })
    }

    @Test
    fun `sigma 大于 0_3 时不得带绝对距离`() {
        val errors = SuggestionSchema.validate(
            stance(targetDistance = 2.4, sigma = 0.35, rangeTier = RangeTier.CALIBRATED),
        )
        assertTrue(errors.any { it.contains("sigma") })
    }

    @Test
    fun `厂商来源必须带 provider 与 model`() {
        val s = stance(source = SuggestionSource.VENDOR_API, band = ConfidenceBand.MID)
        val errors = SuggestionSchema.validate(s)
        assertTrue(errors.any { it.contains("provider") })
    }

    @Test
    fun `tier 由 band 推导而非自由设置`() {
        val s = stance().copy(tier = Tier.HINT)
        val errors = SuggestionSchema.validate(s)
        assertTrue(errors.any { it.contains("tier") })
    }

    @Test
    fun `参数建议缺 explain 四要素即不合格`() {
        val s = Suggestion(
            id = "sg_param",
            sessionId = "ss",
            version = 1,
            domain = SuggestionDomain.PARAMS,
            actor = Actor.PHOTOGRAPHER,
            tier = Tier.WEAK,
            confidence = 0.75,
            band = ConfidenceBand.MID,
            calibrated = true,
            source = SuggestionSource.ON_DEVICE,
            modelVersion = "test",
            createdAtMs = 0,
            ttlMs = 4000,
            visibleText = "参数已就绪",
        )
        val errors = SuggestionSchema.validate(s)
        assertTrue(errors.any { it.contains("explain") })
    }

    @Test
    fun `厂商置信度永远拿不到强引导`() {
        assertEquals(Tier.HINT, TierPolicy.derive(ConfidenceBand.HIGH, true, SuggestionSource.VENDOR_API))
        assertEquals(Tier.STRONG, TierPolicy.derive(ConfidenceBand.HIGH, true, SuggestionSource.ON_DEVICE))
        assertEquals(Tier.HINT, TierPolicy.derive(ConfidenceBand.HIGH, false, SuggestionSource.ON_DEVICE))
    }

    @Test
    fun `兜底建议是弱引导`() {
        val fallback = com.scenemate.app.capability.guidance.FallbackProducer.produce("ss", 1, 2000)
        assertNotNull(fallback.visibleText)
        assertEquals(Tier.WEAK, fallback.tier)
        assertTrue(SuggestionSchema.isValid(fallback))
        assertFalse(fallback.visibleText.contains("注意"))
    }
}
