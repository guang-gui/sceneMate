package com.scenemate.app.platform.config

/**
 * 能力矩阵（ADR-007）。服务端按 (platform, device_model, os_version, app_version, ram_class) 下发，
 * 端侧内置一份兜底 manifest，断网时用兜底。
 */
data class ModelRoute(
    val provider: String,
    val baseUrl: String,
    val model: String,
    val unitCostCny: Double,
)

data class ByokConfig(
    val enabled: Boolean = true,
    val maxCallsPerSession: Int = 4,
    val maxCallsPerDay: Int = 20,
    /** L1 = 人脸模糊 + 640px；L2 = 720p 原图，仅 Wi-Fi */
    val uploadMode: String = "L1",
)

data class CapabilityManifest(
    val deviceClass: String = "android_mid",
    val manualSensor: Boolean = false,
    val lidar: Boolean = false,
    val maxAnalysisFps: Int = 24,
    val rangeTier: String = "default",
    val models: List<ModelRoute> = listOf(
        ModelRoute("deepseek", "https://api.deepseek.com/v1", "deepseek-chat", 0.004),
        ModelRoute("glm", "https://open.bigmodel.cn/api/paas/v4", "glm-4v", 0.006),
    ),
    val byok: ByokConfig = ByokConfig(),
    val overlayLayers: List<String> = listOf(
        "silhouette", "footprint", "distance_label", "instruction_chip",
    ),
    val features: Map<String, Boolean> = mapOf(
        "composition_grid" to false,
        "horizon_align" to false,
        "pose_hints" to false,
        "review_score" to false,
    ),
    /** 配置版本，埋点用 */
    val etag: String = "builtin-1",
) {
    companion object {
        /** 断网兜底。v1 没有 config 服务，这份就是全部。 */
        val BUILTIN = CapabilityManifest()
    }
}
