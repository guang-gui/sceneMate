package com.scenemate.app.presentation

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.scenemate.app.capability.capture.Camera2Controller
import com.scenemate.app.capability.capture.CameraCapabilities
import com.scenemate.app.capability.geometry.FrameGeometry
import com.scenemate.app.capability.geometry.RangeEstimator
import com.scenemate.app.capability.guidance.CameraParamState
import com.scenemate.app.capability.guidance.FallbackProducer
import com.scenemate.app.capability.guidance.ParamPlan
import com.scenemate.app.capability.guidance.ParamPlanner
import com.scenemate.app.capability.guidance.ParamSuggestionProducer
import com.scenemate.app.capability.guidance.StanceSuggestionProducer
import com.scenemate.app.capability.perception.FrameStatsReader
import com.scenemate.app.capability.perception.PerceptionPipeline
import com.scenemate.app.capability.perception.RuleSceneClassifier
import com.scenemate.app.capability.perception.SceneLabels
import com.scenemate.app.capability.telemetry.Event
import com.scenemate.app.capability.telemetry.EventName
import com.scenemate.app.capability.telemetry.EventQueue
import com.scenemate.app.domain.Actor
import com.scenemate.app.domain.Arbiter
import com.scenemate.app.domain.AuthorityStore
import com.scenemate.app.domain.ConfidenceBand
import com.scenemate.app.domain.InstructionWriter
import com.scenemate.app.domain.LockPersistence
import com.scenemate.app.domain.ParamKey
import com.scenemate.app.domain.Suggestion
import com.scenemate.app.domain.SuggestionSchema
import com.scenemate.app.domain.SuggestionSource
import com.scenemate.app.domain.SuggestionStore
import com.scenemate.app.domain.Tier
import com.scenemate.app.platform.byok.ByokKeyStore
import com.scenemate.app.platform.byok.CloudSceneEnhancer
import com.scenemate.app.platform.config.CapabilityManifest
import com.scenemate.app.presentation.overlay.OverlayFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class PrefsLockPersistence(context: Context) : LockPersistence {
    private val prefs = context.getSharedPreferences("scenemate_authority", Context.MODE_PRIVATE)

    override fun load(): Set<ParamKey> =
        prefs.getStringSet("locked", emptySet()).orEmpty()
            .mapNotNull { runCatching { ParamKey.valueOf(it) }.getOrNull() }
            .toSet()

    override fun save(locked: Set<ParamKey>) {
        prefs.edit().putStringSet("locked", locked.map { it.name }.toSet()).apply()
    }
}

/**
 * 编排层。四条管线的交汇点：
 *   采集 → 感知 → 几何 → 建议 → 仲裁 → 渲染
 *
 * 铁律（ADR-004）：这个类里**没有任何东西**可以阻塞快门路径。
 * 分析在 analysis 线程，状态更新走 StateFlow，快门只调 camera.capture()。
 */
class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val perception = PerceptionPipeline()
    private val rangeEstimator = RangeEstimator()
    private val stanceProducer = StanceSuggestionProducer()
    private val paramProducer = ParamSuggestionProducer()
    private val arbiter = Arbiter()
    private val store = SuggestionStore()
    private val authority = AuthorityStore(PrefsLockPersistence(app))
    private val keyStore = ByokKeyStore(app)
    private val manifest = CapabilityManifest.BUILTIN

    private val events = EventQueue(
        queueFile = File(app.filesDir, "events.jsonl"),
        endpoint = null,
        httpPost = { _, _ -> false },
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var camera: Camera2Controller? = null
    private var caps: CameraCapabilities? = null
    private var sessionStartMs = 0L
    private var version = AtomicLong(0)
    private var frameCount = 0L
    private var personCount = 0
    private var lastAnalysisMs = 0L
    private val analysisTimes = ArrayDeque<Long>()
    private var fallbackJob: Job? = null
    private var cloudCallsThisSession = 0
    private var pendingCloud = false
    private var lastShownId: String? = null
    private var shownAtMs = 0L
    private var currentLabels: SceneLabels = SceneLabels.UNKNOWN

    /**
     * 参数建议有独立的展示槽位，不参与首屏指令位的竞争。
     * 否则一旦兜底建议占了 store，参数面板就永远是空的——两个展示位不该互相挤占。
     */
    private var lastParamSuggestion: Suggestion? = null

    fun attachCamera(controller: Camera2Controller) {
        camera = controller
        sessionStartMs = System.currentTimeMillis()
        scheduleFallback()
        observeNetwork()
        refreshByokState()
    }

    fun detachCamera() {
        fallbackJob?.cancel()
        camera = null
        caps = null
        store.clear()
    }

    // ---------------------------------------------------------------- 感知主循环

    /** 在 analysis 线程上被调用。**必须同步完成并返回**，否则会拖垮帧率。 */
    fun onFrame(image: Image) {
        val capabilities = caps ?: return
        val started = SystemClock.elapsedRealtime()
        try {
            val stats = FrameStatsReader.read(
                image = image,
                iso = _state.value.cameraParams.iso,
                exposureNs = _state.value.cameraParams.exposureNs,
                personCount = personCount,
                rollDeg = 0.0,
            )

            val input = InputImage.fromMediaImage(image, capabilities.rotationDegrees)
            val pose = runCatching { perception.detectPose(input) }.getOrNull()

            frameCount++
            if (frameCount % 10 == 0L) {
                val faces = runCatching { perception.detectFaceCount(input) }.getOrDefault(0)
                personCount = faces
                camera?.setPersonCount(faces)
            }

            val labels = RuleSceneClassifier.classify(stats.copy(personCount = personCount))
            currentLabels = labels

            val frameGeo = FrameGeometry(
                widthPx = capabilities.analysisWidth,
                heightPx = capabilities.analysisHeight,
                focalPx = capabilities.focalPx,
            )

            val now = System.currentTimeMillis()
            val nextVersion = version.incrementAndGet()
            val actor = if (_state.value.actorIsSubject) Actor.SUBJECT else Actor.PHOTOGRAPHER
            val pronoun = _state.value.subjectPronoun

            val candidates = mutableListOf<Suggestion>()

            val estimate = pose?.let { runCatching { rangeEstimator.estimate(it, frameGeo) }.getOrNull() }
            if (estimate != null) {
                stanceProducer.produce(
                    sessionId = SESSION_ID,
                    version = nextVersion,
                    nowMs = now,
                    estimate = estimate,
                    frame = frameGeo,
                    actor = actor,
                    subjectPronoun = pronoun,
                    personCount = personCount,
                    headingDeg = 0.0,
                )?.let { if (SuggestionSchema.isValid(it)) candidates += it }
            }

            paramProducer.produce(
                sessionId = SESSION_ID,
                version = nextVersion,
                nowMs = now,
                labels = labels,
                current = _state.value.cameraParams,
                manualSensorSupported = capabilities.manualSensorSupported,
            )?.let {
                if (SuggestionSchema.isValid(it)) {
                    lastParamSuggestion = it
                    candidates += it
                }
            }

            val result = arbiter.arbitrate(candidates, authority.lockedParams(), now)
            val top = result.top

            var accepted: Suggestion? = null
            if (top != null && store.offer(top)) accepted = top

            val elapsed = SystemClock.elapsedRealtime() - started
            synchronized(analysisTimes) {
                analysisTimes.addLast(elapsed)
                while (analysisTimes.size > 30) analysisTimes.removeFirst()
            }

            if (pendingCloud) {
                pendingCloud = false
                val bitmap = runCatching { YuvBitmapConverter.toBitmap(image, 2) }.getOrNull()
                if (bitmap != null) requestCloudAnalysis(bitmap)
            }

            publish(accepted, labels, now, elapsed)
        } catch (t: Throwable) {
            // 单帧失败绝不能拖垮整条管线
            android.util.Log.w("CameraViewModel", "frame failed", t)
        }
    }

    private fun publish(accepted: Suggestion?, labels: SceneLabels, now: Long, elapsedMs: Long) {
        val shown = store.current() ?: accepted
        val stateBefore = _state.value

        // 参数建议不进首屏指令位（PRD 屏 1：任何时刻只显示 1 条**指令**）。
        // 参数走 EXIF 条与面板，这由 Arbiter 之后的展示层规则保证。
        val guidance = shown?.takeIf { it.domain != com.scenemate.app.domain.SuggestionDomain.PARAMS }

        val overlay = if (guidance != null) {
            val payload = guidance.payload
            val allowsFootprint = stateFor(guidance, personCount).allowsFootprint
            OverlayFrame(
                subjectBox = payload?.subjectBoxNorm,
                footprint = if (allowsFootprint) payload?.footprintNorm else null,
                instruction = guidance.visibleText,
                distanceLabel = if (allowsFootprint) relativeLabel(guidance) else null,
                tier = guidance.tier,
                showSilhouette = allowsFootprint,
                frozen = false,
            )
        } else {
            OverlayFrame(instruction = null, showSilhouette = false)
        }

        val sessionState = stateFor(guidance, personCount)

        if (accepted != null && accepted.id != lastShownId) {
            lastShownId = accepted.id
            shownAtMs = now
            events.record(
                Event(
                    name = EventName.SUGGESTION_SHOWN,
                    atMs = now,
                    sessionId = SESSION_ID,
                    props = mapOf(
                        "type" to accepted.domain.name.lowercase(),
                        "scene" to labels.scene,
                        "light" to labels.light,
                        "confidence" to accepted.confidence,
                        "band" to accepted.band.name,
                        "source" to accepted.source.name.lowercase(),
                        "gen_ms" to elapsedMs,
                        "first" to (shownAtMs == sessionStartMs),
                        "text" to accepted.visibleText,
                    ),
                ),
            )
        }

        val p50 = synchronized(analysisTimes) {
            analysisTimes.sorted().getOrNull(analysisTimes.size / 2) ?: 0L
        }

        _state.value = stateBefore.copy(
            sessionState = sessionState,
            instruction = guidance?.visibleText,
            chipText = chipText(labels, stateBefore.offline),
            bandLabel = guidance?.let { UiState.bandOf(it.band) } ?: "",
            confidencePercent = guidance?.takeIf { it.calibrated && it.source == SuggestionSource.ON_DEVICE }
                ?.let { (it.confidence * 100).toInt() },
            labels = labels,
            overlay = overlay,
            paramSuggestion = lastParamSuggestion,
            paramExplain = lastParamSuggestion?.explain,
            lockedParams = authority.lockedParams(),
            analyzeMsP50 = p50,
        )
    }

    private fun stateFor(shown: Suggestion?, persons: Int): SessionState {
        if (_state.value.offline) return SessionState.OFFLINE
        if (shown == null) return SessionState.RECOGNIZING
        if (shown.source == SuggestionSource.FALLBACK) return SessionState.FALLBACK
        if (persons >= 2) return SessionState.MULTI_SUBJECT
        return when (shown.band) {
            ConfidenceBand.HIGH -> SessionState.HIGH_CONF
            ConfidenceBand.MID -> SessionState.MID_CONF
            ConfidenceBand.LOW -> SessionState.LOW_CONF
        }
    }

    private fun chipText(labels: SceneLabels, offline: Boolean): String = when {
        offline -> "离线 · 端侧引导"
        labels.scene == "unknown" -> "识别中"
        else -> labels.chipText
    }

    /** 只给相对位移，绝不给绝对米数（§6.2.1）。 */
    private fun relativeLabel(s: Suggestion): String? {
        val delta = s.payload?.delta ?: return null
        val quantized = when (delta.quantized) {
            "half_step" -> "半步"
            "full_step" -> "一步"
            "two_step" -> "两步"
            else -> null
        } ?: return null
        val meters = "%.1fm".format(delta.magnitudeM)
        return "$quantized · 约 $meters"
    }

    // ---------------------------------------------------------------- 兜底与网络

    private fun scheduleFallback() {
        fallbackJob?.cancel()
        fallbackJob = viewModelScope.launch {
            delay(FallbackProducer.TRIGGER_AFTER_MS)
            // 「未收敛」指没有站位/构图类引导；参数建议不算，它不进首屏指令位
            val current = store.current()
            val hasGuidance = current != null &&
                current.domain != com.scenemate.app.domain.SuggestionDomain.PARAMS
            if (!hasGuidance) {
                val suggestion = FallbackProducer.produce(
                    sessionId = SESSION_ID,
                    version = version.incrementAndGet(),
                    nowMs = System.currentTimeMillis(),
                )
                if (store.offer(suggestion)) {
                    _state.value = _state.value.copy(
                        sessionState = SessionState.FALLBACK,
                        instruction = suggestion.visibleText,
                        overlay = OverlayFrame(
                            instruction = suggestion.visibleText,
                            showSilhouette = false,
                            tier = Tier.WEAK,
                        ),
                        bandLabel = "中",
                    )
                }
            }
        }
    }

    private fun observeNetwork() {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _state.value = _state.value.copy(offline = false)
            }

            override fun onLost(network: Network) {
                _state.value = _state.value.copy(offline = true)
            }
        }
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
    }

    // ---------------------------------------------------------------- 相机回调

    fun onCameraReady(capabilities: CameraCapabilities) {
        caps = capabilities
        _state.value = _state.value.copy(
            manualSensorSupported = capabilities.manualSensorSupported,
            sessionState = SessionState.RECOGNIZING,
        )
    }

    fun onCameraError(message: String) {
        _state.value = _state.value.copy(errorMessage = message)
    }

    fun onCaptureState(iso: Int, exposureNs: Long) {
        val params = _state.value.cameraParams.copy(iso = iso, exposureNs = exposureNs)
        _state.value = _state.value.copy(cameraParams = params)
    }

    fun onPhotoSaved(file: File, thumbnail: Bitmap?) {
        val shown = store.current()
        events.record(
            Event(
                name = EventName.PHOTO_SAVED,
                atMs = System.currentTimeMillis(),
                sessionId = SESSION_ID,
                props = mapOf(
                    "suggestion_id" to shown?.id,
                    "by_suggestion" to (shown != null),
                    "iso" to _state.value.cameraParams.iso,
                    "shutter_ns" to _state.value.cameraParams.exposureNs,
                    "scene" to currentLabels.scene,
                ),
            ),
        )
        if (shown != null) {
            events.record(
                Event(
                    name = EventName.SUGGESTION_ADOPTED,
                    atMs = System.currentTimeMillis(),
                    sessionId = SESSION_ID,
                    props = mapOf(
                        "suggestion_id" to shown.id,
                        "type" to shown.domain.name.lowercase(),
                        "adopt_mode" to "implicit",
                        "ms_since_shown" to (System.currentTimeMillis() - shownAtMs),
                    ),
                ),
            )
        }
        _state.value = _state.value.copy(
            lastPhotoPath = file.absolutePath,
            lastThumbnail = thumbnail,
            sessionState = SessionState.RECOGNIZING,
        )
        scheduleFallback()
    }

    // ---------------------------------------------------------------- 用户动作

    fun onShutter() {
        _state.value = _state.value.copy(sessionState = SessionState.CAPTURING)
        camera?.capture()
    }

    fun applyParamSuggestion() {
        val suggestion = _state.value.paramSuggestion ?: return
        val plan = ParamPlanner.plan(currentLabels, _state.value.cameraParams)
        val old = _state.value.cameraParams

        val accepted = ParamPlan(
            iso = plan.iso?.takeIf {
                authority.applyAI(ParamKey.ISO, old.iso.toString(), it.toString(), "scene_rule") ==
                    AuthorityStore.ApplyResult.APPLIED
            },
            exposureNs = plan.exposureNs?.takeIf {
                authority.applyAI(ParamKey.SHUTTER, old.exposureNs.toString(), it.toString(), "scene_rule") ==
                    AuthorityStore.ApplyResult.APPLIED
            },
            evSteps = plan.evSteps?.takeIf {
                authority.applyAI(ParamKey.EV, old.evSteps.toString(), it.toString(), "scene_rule") ==
                    AuthorityStore.ApplyResult.APPLIED
            },
            wbKelvin = plan.wbKelvin?.takeIf {
                authority.applyAI(ParamKey.WB, old.wbDisplay(), it.toString(), "scene_rule") ==
                    AuthorityStore.ApplyResult.APPLIED
            },
        )

        camera?.applyPlan(accepted)
        _state.value = _state.value.copy(
            aiParams = CameraParamState(
                iso = accepted.iso ?: old.iso,
                exposureNs = accepted.exposureNs ?: old.exposureNs,
                evSteps = accepted.evSteps ?: old.evSteps,
                wbKelvin = accepted.wbKelvin ?: old.wbKelvin,
            ),
            paramSuggestion = suggestion,
            lockedParams = authority.lockedParams(),
        )
    }

    /** 用户拖动滑轨：立即锁定该参数，AI 本会话内零次回写（R05）。 */
    fun onUserParamChange(key: ParamKey, value: Int) {
        authority.lock(key, "user_slider")
        val current = _state.value.cameraParams
        val plan = when (key) {
            ParamKey.ISO -> ParamPlan(iso = value.coerceIn(50, caps?.maxIso ?: 3200))
            ParamKey.SHUTTER -> ParamPlan(exposureNs = value.toLong().coerceIn(1_000_000L, caps?.maxExposureNs ?: 66_000_000L))
            ParamKey.EV -> ParamPlan(evSteps = value.coerceIn(-9, 9))
            ParamKey.WB -> ParamPlan(wbKelvin = value.coerceIn(2000, 8000))
        }
        val merged = ParamPlan(
            iso = plan.iso ?: camera?.currentPlan()?.iso,
            exposureNs = plan.exposureNs ?: camera?.currentPlan()?.exposureNs,
            evSteps = plan.evSteps ?: camera?.currentPlan()?.evSteps,
            wbKelvin = plan.wbKelvin ?: camera?.currentPlan()?.wbKelvin,
        )
        camera?.applyPlan(merged)
        _state.value = _state.value.copy(
            cameraParams = when (key) {
                ParamKey.ISO -> current.copy(iso = value)
                ParamKey.SHUTTER -> current.copy(exposureNs = value.toLong())
                ParamKey.EV -> current.copy(evSteps = value)
                ParamKey.WB -> current.copy(wbKelvin = value)
            },
            lockedParams = authority.lockedParams(),
            aiParams = null,
        )
    }

    fun unlockParam(key: ParamKey) {
        authority.unlock(key)
        _state.value = _state.value.copy(lockedParams = authority.lockedParams())
    }

    fun toggleParamPanel(show: Boolean) {
        _state.value = _state.value.copy(showParamPanel = show)
    }

    fun toggleSettings(show: Boolean) {
        refreshByokState()
        _state.value = _state.value.copy(showSettings = show)
    }

    fun setActorIsSubject(isSubject: Boolean) {
        _state.value = _state.value.copy(actorIsSubject = isSubject)
    }

    fun setSubjectPronoun(pronoun: String) {
        _state.value = _state.value.copy(subjectPronoun = pronoun)
    }

    fun toggleTorch(): Boolean = camera?.toggleTorch() ?: false

    fun flipCamera() = camera?.flip()

    // ---------------------------------------------------------------- BYOK

    fun refreshByokState() {
        val enabled = manifest.byok.enabled && keyStore.hasKey(keyStore.selectedProvider())
        _state.value = _state.value.copy(cloudEnabled = enabled, cloudCallsThisSession = cloudCallsThisSession)
    }

    fun saveByokKey(provider: String, key: String) {
        keyStore.save(provider, key)
        keyStore.selectProvider(provider)
        refreshByokState()
    }

    fun clearByokKey(provider: String) {
        keyStore.clear(provider)
        refreshByokState()
        _state.value = _state.value.copy(cloudNote = "密钥已清除")
    }

    /** 用户点「深度分析」。默认关闭，且单会话硬上限 4 次（ADR-001）。 */
    fun requestDeepAnalysis() {
        if (!_state.value.cloudEnabled) {
            _state.value = _state.value.copy(cloudNote = "未开启云端增强")
            return
        }
        if (cloudCallsThisSession >= manifest.byok.maxCallsPerSession) {
            _state.value = _state.value.copy(cloudNote = "本会话已达 ${manifest.byok.maxCallsPerSession} 次上限")
            return
        }
        pendingCloud = true
        _state.value = _state.value.copy(cloudBusy = true, cloudNote = "增强识别中")
    }

    private fun requestCloudAnalysis(bitmap: Bitmap) {
        if (cloudCallsThisSession >= manifest.byok.maxCallsPerSession) return
        val providerId = keyStore.selectedProvider()
        val route = manifest.models.firstOrNull { it.provider == providerId } ?: return
        cloudCallsThisSession++
        _state.value = _state.value.copy(cloudCallsThisSession = cloudCallsThisSession, cloudBusy = true)

        viewModelScope.launch {
            val enhancer = CloudSceneEnhancer(keyStore, route)
            val result = withContext(Dispatchers.IO) { enhancer.analyze(bitmap) }
            result.onSuccess { vendor ->
                val labels = SceneLabels(
                    scene = vendor.scene,
                    sceneConfidence = vendor.confidence,
                    light = vendor.light,
                    lightConfidence = vendor.confidence,
                    subject = vendor.subject,
                    subjectConfidence = vendor.confidence,
                    source = SceneLabels.SOURCE_VENDOR,
                )
                currentLabels = labels
                events.record(
                    Event(
                        name = EventName.CLOUD_SCENE_RESULT,
                        atMs = System.currentTimeMillis(),
                        sessionId = SESSION_ID,
                        props = mapOf(
                            "provider" to route.provider,
                            "model" to route.model,
                            "scene" to vendor.scene,
                            "light" to vendor.light,
                            "confidence" to vendor.confidence,
                            "latency_ms" to vendor.latencyMs,
                        ),
                    ),
                )
                _state.value = _state.value.copy(
                    labels = labels,
                    chipText = labels.chipText,
                    cloudBusy = false,
                    cloudNote = "增强完成 · ${vendor.latencyMs}ms",
                )
            }.onFailure {
                _state.value = _state.value.copy(
                    cloudBusy = false,
                    cloudNote = "增强不可用：${it.message ?: "未知错误"}",
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        perception.close()
        camera?.release()
    }

    companion object {
        val SESSION_ID: String = "ss_" + System.currentTimeMillis().toString(36)

        fun instructionFor(actor: Actor, backM: Double, lateralM: Double, pronoun: String): String? =
            InstructionWriter.forStance(actor, backM, lateralM, pronoun)

        fun magnitudeOf(s: Suggestion): String? =
            s.payload?.delta?.let { if (abs(it.magnitudeM) < 0.01) null else "%.1fm".format(it.magnitudeM) }
    }
}
