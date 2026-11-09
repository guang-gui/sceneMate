package com.scenemate.app.presentation.overlay

import com.scenemate.app.domain.NormRect
import com.scenemate.app.domain.Tier

/** 叠加层的一帧。图层树只认这个不可变快照，不认业务对象。 */
data class OverlayFrame(
    val subjectBox: NormRect? = null,
    val footprint: NormRect? = null,
    val instruction: String? = null,
    val distanceLabel: String? = null,
    val tier: Tier = Tier.HINT,
    val showSilhouette: Boolean = true,
    val frozen: Boolean = false,
    /** 仅用于调试：关键点连线 */
    val skeleton: List<Float> = emptyList(),
)
