package com.scenemate.app.platform.byok

import android.graphics.Bitmap
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.scenemate.app.capability.perception.SceneLabels
import com.scenemate.app.platform.config.ModelRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class ProviderSceneResult(
    val scene: String,
    val light: String,
    val subject: String,
    val confidence: Double,
    val latencyMs: Long,
    val rawJson: String,
)

/** 厂商适配层（ADR-013）：模型名、厂商、协议都可替换，UI 不变。 */
interface ModelProvider {
    val id: String
    suspend fun analyze(jpegBase64: String, prompt: String): Result<ProviderSceneResult>
}

class ProviderError(val code: Int, message: String) : Exception(message)

/**
 * OpenAI 兼容端点（DeepSeek / GLM 共用）。
 * 错误映射统一：401 密钥无效 / 402 余额不足 / 429 限流 / 超时 → 一律「增强能力不可用」。
 */
class OpenAiCompatibleProvider(
    override val id: String,
    private val route: ModelRoute,
    private val apiKey: String,
    private val client: OkHttpClient = defaultClient(),
) : ModelProvider {

    companion object {
        const val PROMPT_VERSION = "scene-v1.0.0"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .build()

        /** 版本化 prompt。禁止词与枚举在这里锁死，和端侧 schema 对齐。 */
        fun prompt(): String = """
            你是取景助手。看这张画面，只输出 JSON，不要解释。
            字段：scene ∈ [sky_water, indoor_warm, foliage, street, night_city, unknown]；
            light ∈ [backlit, low_light, normal, unknown]；
            subject ∈ [none, person, group, crowd, unknown]；
            confidence ∈ [0,1]。
            禁止输出任何方向、距离、米数、参数值——数值由端侧几何给。
            禁止出现这些词：构图、光线、氛围、感觉、注意、尝试、适当、一些。
        """.trimIndent()
    }

    override suspend fun analyze(jpegBase64: String, prompt: String): Result<ProviderSceneResult> =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            runCatching {
                val body = JSONObject().apply {
                    put("model", route.model)
                    put("temperature", 0) // 实测：默认温度下同一张图会给互相矛盾的方向
                    put("max_tokens", 400)
                    put("response_format", JSONObject().put("type", "json_object"))
                    put(
                        "messages",
                        org.json.JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("role", "system")
                                    put("content", prompt)
                                },
                            )
                            put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put(
                                        "content",
                                        org.json.JSONArray().apply {
                                            put(
                                                JSONObject().apply {
                                                    put("type", "text")
                                                    put("text", "分析这张取景画面，只输出 JSON。")
                                                },
                                            )
                                            put(
                                                JSONObject().apply {
                                                    put("type", "image_url")
                                                    put(
                                                        "image_url",
                                                        JSONObject().apply {
                                                            put("url", "data:image/jpeg;base64,$jpegBase64")
                                                            put("detail", "low")
                                                        },
                                                    )
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                }.toString()

                val request = Request.Builder()
                    .url(route.baseUrl.trimEnd('/') + "/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw ProviderError(response.code, "http ${response.code}")
                    }
                    val text = response.body?.string().orEmpty()
                    val content = JSONObject(text)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                    val parsed = SuggestionSanitizer.parse(content)
                        ?: throw ProviderError(-1, "schema 校验失败")
                    ProviderSceneResult(
                        scene = parsed.scene,
                        light = parsed.light,
                        subject = parsed.subject,
                        confidence = parsed.sceneConfidence,
                        latencyMs = System.currentTimeMillis() - started,
                        rawJson = content,
                    )
                }
            }
        }
}

/**
 * Schema 校验 + 范围 clamp + 禁止词过滤 + 置信度上限截断。
 * LLM 输出是不可信输入，进 Arbiter 之前必须过这一层。
 */
object SuggestionSanitizer {

    private val ALLOWED_SCENE = setOf(
        "sky_water", "indoor_warm", "foliage", "street", "night_city", "unknown",
    )
    private val ALLOWED_LIGHT = setOf("backlit", "low_light", "normal", "unknown")
    private val ALLOWED_SUBJECT = setOf("none", "person", "group", "crowd", "unknown")

    /** 厂商置信度上限：LLM 的 self-report 不可信，永远进不了 HIGH 档。 */
    const val MAX_VENDOR_CONFIDENCE = 0.80

    fun parse(raw: String): SceneLabels? {
        val json = runCatching { JSONObject(extractJson(raw)) }.getOrNull() ?: return null
        val scene = json.optString("scene", "unknown").lowercase().takeIf { it in ALLOWED_SCENE } ?: "unknown"
        val light = json.optString("light", "unknown").lowercase().takeIf { it in ALLOWED_LIGHT } ?: "unknown"
        val subject = json.optString("subject", "unknown").lowercase().takeIf { it in ALLOWED_SUBJECT } ?: "unknown"
        val confidence = json.optDouble("confidence", 0.6).coerceIn(0.0, MAX_VENDOR_CONFIDENCE)

        if (scene == "unknown" && light == "unknown" && subject == "unknown") return null

        return SceneLabels(
            scene = scene,
            sceneConfidence = confidence,
            light = light,
            lightConfidence = confidence,
            subject = subject,
            subjectConfidence = confidence,
            source = SceneLabels.SOURCE_VENDOR,
        )
    }

    /** 模型偶尔会包 ```json 代码块，剥掉再解析。 */
    private fun extractJson(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else raw
    }
}

/**
 * 云端增强（默认关闭，BYOK 开启后才可用）。
 *
 * 发送前一律 L1 处理：人脸检测 → 高斯模糊 → 640px JPEG q60。
 * 判断「海边日落/逆光/1 人」靠的是天空、色温、轮廓，不看五官。
 */
class CloudSceneEnhancer(
    private val keyStore: ByokKeyStore,
    private val route: ModelRoute,
) {
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build(),
    )

    suspend fun analyze(bitmap: Bitmap): Result<ProviderSceneResult> = withContext(Dispatchers.IO) {
        val key = keyStore.load(route.provider)
            ?: return@withContext Result.failure(ProviderError(401, "未配置密钥"))
        val prepared = prepareL1(bitmap)
        val provider = OpenAiCompatibleProvider(route.provider, route, key)
        provider.analyze(prepared, OpenAiCompatibleProvider.prompt())
    }

    /** L1：模糊人脸 + 缩到 640px 宽 + JPEG q60。 */
    private suspend fun prepareL1(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val faces = runCatching {
            Tasks.await(faceDetector.process(InputImage.fromBitmap(bitmap, 0)))
        }.getOrDefault(emptyList())

        val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(mutable)
        val paint = android.graphics.Paint().apply { isFilterBitmap = true }
        faces.forEach { face ->
            val box = face.boundingBox
            val pad = (box.width() * 0.15f).toInt()
            val src = android.graphics.Rect(
                (box.left - pad).coerceAtLeast(0),
                (box.top - pad).coerceAtLeast(0),
                (box.right + pad).coerceAtMost(mutable.width),
                (box.bottom + pad).coerceAtMost(mutable.height),
            )
            if (src.width() <= 0 || src.height() <= 0) return@forEach
            val w = src.width() / 8
            val h = src.height() / 8
            if (w <= 0 || h <= 0) return@forEach
            val tiny = Bitmap.createBitmap(mutable, src.left, src.top, src.width(), src.height())
            val blurred = Bitmap.createScaledBitmap(tiny, w, h, true)
            canvas.drawBitmap(blurred, null, src, paint)
        }

        val targetWidth = 640
        val ratio = targetWidth.toFloat() / mutable.width
        val scaled = Bitmap.createScaledBitmap(
            mutable,
            targetWidth,
            (mutable.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 60, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
