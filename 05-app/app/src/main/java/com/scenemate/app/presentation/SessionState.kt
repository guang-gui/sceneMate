package com.scenemate.app.presentation

/** 会话状态机（TAD §5.5，与 PRD 7.2 的 7 种异常态对齐）。 */
enum class SessionState {
    PREPARING,
    PERMISSION_DENIED,
    RECOGNIZING,
    HIGH_CONF,
    MID_CONF,
    LOW_CONF,
    FALLBACK,
    MULTI_SUBJECT,
    OFFLINE,
    CAPTURING,
    ;

    /** 状态与能力矩阵：谁能显示什么，机械保证 R04。 */
    val allowsFootprint: Boolean
        get() = this == HIGH_CONF || this == MULTI_SUBJECT || this == OFFLINE || this == FALLBACK

    val allowsRelativeDelta: Boolean
        get() = this == HIGH_CONF || this == MID_CONF || this == MULTI_SUBJECT || this == OFFLINE

    val allowsParamApply: Boolean
        get() = this == HIGH_CONF || this == MULTI_SUBJECT || this == OFFLINE
}
