package com.scenemate.app.presentation

import android.graphics.Bitmap
import com.scenemate.app.capability.guidance.CameraParamState
import com.scenemate.app.capability.perception.SceneLabels
import com.scenemate.app.domain.ConfidenceBand
import com.scenemate.app.domain.Explain
import com.scenemate.app.domain.ParamKey
import com.scenemate.app.domain.Suggestion
import com.scenemate.app.presentation.overlay.OverlayFrame

data class UiState(
    val sessionState: SessionState = SessionState.PREPARING,
    /** 首屏唯一一条指令（任何时刻只有 1 条，由 Arbiter 机械保证） */
    val instruction: String? = null,
    val chipText: String = "识别中",
    val bandLabel: String = "",
    val confidencePercent: Int? = null,
    val labels: SceneLabels = SceneLabels.UNKNOWN,
    /** 相机真实参数（读回值） */
    val cameraParams: CameraParamState = CameraParamState(),
    /** AI 建议值，用于 EXIF 双轨显示（R07） */
    val aiParams: CameraParamState? = null,
    val lockedParams: Set<ParamKey> = emptySet(),
    val overlay: OverlayFrame = OverlayFrame(),
    val paramSuggestion: Suggestion? = null,
    val paramExplain: Explain? = null,
    val manualSensorSupported: Boolean = false,
    val cloudEnabled: Boolean = false,
    val cloudBusy: Boolean = false,
    val cloudNote: String? = null,
    val cloudCallsThisSession: Int = 0,
    val lastPhotoPath: String? = null,
    val lastThumbnail: Bitmap? = null,
    val showParamPanel: Boolean = false,
    val showSettings: Boolean = false,
    val actorIsSubject: Boolean = true,
    val subjectPronoun: String = "她",
    val frameDropRate: Double = 0.0,
    val analyzeMsP50: Long = 0,
    val errorMessage: String? = null,
    val offline: Boolean = false,
) {
    val bandDisplay: String
        get() = when {
            confidencePercent != null -> "$confidencePercent%"
            bandLabel.isNotBlank() -> bandLabel
            else -> ""
        }

    val stateLabel: String
        get() = when (sessionState) {
            SessionState.PREPARING -> "准备中"
            SessionState.PERMISSION_DENIED -> "无相机权限"
            SessionState.RECOGNIZING -> "识别中"
            SessionState.HIGH_CONF -> "高置信"
            SessionState.MID_CONF -> "中置信"
            SessionState.LOW_CONF -> "低置信"
            SessionState.FALLBACK -> "保底引导"
            SessionState.MULTI_SUBJECT -> "多人"
            SessionState.OFFLINE -> "离线 · 端侧引导"
            SessionState.CAPTURING -> "拍摄中"
        }

    companion object {
        fun bandOf(band: ConfidenceBand): String = when (band) {
            ConfidenceBand.HIGH -> "高"
            ConfidenceBand.MID -> "中"
            ConfidenceBand.LOW -> "低"
        }
    }
}
