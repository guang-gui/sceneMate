package com.scenemate.app.capability.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.scenemate.app.capability.guidance.ParamPlan
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class CameraCapabilities(
    val manualSensorSupported: Boolean,
    val focalPx: Double,
    val analysisWidth: Int,
    val analysisHeight: Int,
    /** ML Kit 需要的旋转角 */
    val rotationDegrees: Int,
    val maxIso: Int,
    val maxExposureNs: Long,
    val minExposureNs: Long,
)

/**
 * Camera2 采集（ADR-014：直接用 Camera2，不经 CameraX）。
 *
 * 双管线隔离的第一层：预览/分析/静态拍照三条 Surface 挂在同一个 session 上，
 * 但分析帧在自己的 HandlerThread 上消费，快门路径不碰它（ADR-004）。
 */
class Camera2Controller(
    private val context: Context,
    private val textureView: TextureView,
    private val listener: Listener,
) {

    interface Listener {
        fun onCameraReady(capabilities: CameraCapabilities)
        /** 分析帧。**必须在回调内 close**，否则 ImageReader 会卡死。 */
        fun onFrame(image: Image)
        fun onCaptureState(iso: Int, exposureNs: Long)
        fun onPhotoSaved(file: File, thumbnail: Bitmap?)
        fun onCameraError(message: String)
    }

    companion object {
        private const val TAG = "Camera2Controller"
        private const val MAX_ANALYSIS_WIDTH = 1280
    }

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraThread = HandlerThread("scenemate-camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val analysisThread = HandlerThread("scenemate-analysis").apply { start() }
    private val analysisHandler = Handler(analysisThread.looper)
    private val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "scenemate-io") }

    @Volatile
    private var plan: ParamPlan = ParamPlan()

    @Volatile
    private var personCount: Int = 0

    private var cameraId: String? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSize: Size = Size(1080, 1920)
    private var analysisSize: Size = Size(1280, 720)
    private var jpegSize: Size = Size(1920, 1080)
    private var previewSurface: Surface? = null
    private var analysisReader: ImageReader? = null
    private var jpegReader: ImageReader? = null
    private var rotationDegrees: Int = 90
    private var sensorOrientation: Int = 90
    private var latestIso: Int = 0
    private var latestExposureNs: Long = 0L
    private var capabilities: CameraCapabilities? = null
    private val capturing = AtomicBoolean(false)
    private var facingBack = true
    private var torchOn = false

    fun start() {
        if (!textureView.isAvailable) return
        openCamera(facingBack)
    }

    fun stop() {
        runCatching { session?.close() }
        runCatching { cameraDevice?.close() }
        session = null
        cameraDevice = null
        analysisReader?.close()
        jpegReader?.close()
        analysisReader = null
        jpegReader = null
    }

    fun release() {
        stop()
        cameraThread.quitSafely()
        analysisThread.quitSafely()
        ioExecutor.shutdown()
    }

    fun setPersonCount(count: Int) {
        personCount = count
    }

    /** 参数计划通过不可变对象原子替换，避免快门路径读到一个撕裂的中间态（ADR-004）。 */
    fun applyPlan(newPlan: ParamPlan) {
        plan = newPlan
        runCatching { pushRepeating() }
    }

    fun currentPlan(): ParamPlan = plan

    fun toggleTorch(): Boolean {
        torchOn = !torchOn
        runCatching { pushRepeating() }
        return torchOn
    }

    fun flip() {
        facingBack = !facingBack
        stop()
        openCamera(facingBack)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(back: Boolean) {
        try {
            val desired = if (back) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
            val id = cameraManager.cameraIdList.firstOrNull { candidate ->
                cameraManager.getCameraCharacteristics(candidate)
                    .get(CameraCharacteristics.LENS_FACING) == desired
            } ?: return
            cameraId = id

            val chars = cameraManager.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val focalMm = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 4.3f
            val sensorWidthMm = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: 5.6f
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            val manual = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
            val isoRange: Range<Int>? = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exposureRange: Range<Long>? = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

            if (map != null) {
                previewSize = pickSize(map.getOutputSizes(SurfaceTexture::class.java), 1920)
                analysisSize = pickSize(map.getOutputSizes(ImageFormat.YUV_420_888), MAX_ANALYSIS_WIDTH)
                jpegSize = pickSize(map.getOutputSizes(ImageFormat.JPEG), 4032)
            }

            val displayRotation = runCatching {
                textureView.display?.rotation ?: Surface.ROTATION_0
            }.getOrDefault(Surface.ROTATION_0)
            rotationDegrees = ((sensorOrientation - displayRotation * 90) + 360) % 360

            val uprightWidth = if (rotationDegrees % 180 == 0) analysisSize.width else analysisSize.height
            val uprightHeight = if (rotationDegrees % 180 == 0) analysisSize.height else analysisSize.width
            val focalPx = focalMm / sensorWidthMm * analysisSize.width

            capabilities = CameraCapabilities(
                manualSensorSupported = manual,
                focalPx = focalPx.toDouble(),
                analysisWidth = uprightWidth,
                analysisHeight = uprightHeight,
                rotationDegrees = rotationDegrees,
                maxIso = isoRange?.upper ?: 3200,
                maxExposureNs = exposureRange?.upper ?: 66_000_000L,
                minExposureNs = exposureRange?.lower ?: 1_000_000L,
            )

            cameraManager.openCamera(id, deviceCallback, cameraHandler)
        } catch (e: CameraAccessException) {
            listener.onCameraError("相机打开失败: ${e.message}")
        } catch (e: SecurityException) {
            listener.onCameraError("缺少相机权限")
        }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            cameraDevice = device
            createSession(device)
        }

        override fun onDisconnected(device: CameraDevice) {
            device.close()
            cameraDevice = null
            listener.onCameraError("相机被占用")
        }

        override fun onError(device: CameraDevice, error: Int) {
            device.close()
            cameraDevice = null
            listener.onCameraError("相机错误码 $error")
        }
    }

    private fun createSession(device: CameraDevice) {
        try {
            val texture = textureView.surfaceTexture ?: return
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val preview = Surface(texture)
            previewSurface = preview

            val analysis = ImageReader.newInstance(
                analysisSize.width,
                analysisSize.height,
                ImageFormat.YUV_420_888,
                2,
            ).apply {
                setOnImageAvailableListener({ reader ->
                    val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                    try {
                        listener.onFrame(image)
                    } finally {
                        runCatching { image.close() }
                    }
                }, analysisHandler)
            }
            analysisReader = analysis

            val jpeg = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()
                    capturing.set(false)
                    ioExecutor.execute { saveJpeg(bytes) }
                }, cameraHandler)
            }
            jpegReader = jpeg

            val surfaces = listOf(preview, analysis.surface, jpeg.surface)
            @Suppress("DEPRECATION")
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    session = configured
                    pushRepeating()
                    capabilities?.let(listener::onCameraReady)
                }

                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    listener.onCameraError("预览会话创建失败")
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "createSession failed", e)
            listener.onCameraError("预览会话创建失败: ${e.message}")
        }
    }

    /** 参数下发。AE 与手动曝光互斥，这里显式处理，避免「设了 ISO 却不生效」。 */
    private fun buildRequest(template: Int, forCapture: Boolean = false): CaptureRequest.Builder? {
        val device = cameraDevice ?: return null
        val surface = if (forCapture) jpegReader?.surface else previewSurface
        val builder = device.createCaptureRequest(template)
        if (forCapture) {
            jpegReader?.surface?.let { builder.addTarget(it) }
            builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
        } else {
            previewSurface?.let { builder.addTarget(it) }
            analysisReader?.surface?.let { builder.addTarget(it) }
        }

        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        builder.set(CaptureRequest.FLASH_MODE, if (torchOn) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)

        val caps = capabilities
        val current = plan
        val manualExposure = caps?.manualSensorSupported == true &&
            current.iso != null && current.exposureNs != null

        if (manualExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, current.iso!!)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, current.exposureNs!!)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            current.evSteps?.let {
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it)
            }
        }

        current.wbKelvin?.let { kelvin ->
            val preset = when {
                kelvin <= 3200 -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
                kelvin <= 4500 -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
                kelvin <= 5600 -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
                else -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            }
            builder.set(CaptureRequest.CONTROL_AWB_MODE, preset)
        }

        return builder
    }

    private fun pushRepeating() {
        val builder = buildRequest(CameraDevice.TEMPLATE_PREVIEW) ?: return
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
        val request = builder.build()
        runCatching {
            session?.setRepeatingRequest(request, captureCallback, cameraHandler)
        }.onFailure { Log.w(TAG, "setRepeatingRequest failed", it) }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            if (iso != null && exposure != null &&
                (iso != latestIso || exposure != latestExposureNs)
            ) {
                latestIso = iso
                latestExposureNs = exposure
                listener.onCaptureState(iso, exposure)
            }
        }
    }

    /** 快门路径：只读 plan 快照，无锁、无 await、无 IO（R06）。 */
    fun capture() {
        if (!capturing.compareAndSet(false, true)) return
        val builder = buildRequest(CameraDevice.TEMPLATE_STILL_CAPTURE, forCapture = true)
        if (builder == null) {
            capturing.set(false)
            return
        }
        runCatching {
            session?.capture(builder.build(), null, cameraHandler)
        }.onFailure {
            capturing.set(false)
            Log.w(TAG, "capture failed", it)
        }
    }

    private fun jpegOrientation(): Int {
        val displayRotation = runCatching { textureView.display?.rotation ?: Surface.ROTATION_0 }.getOrDefault(0)
        return if (facingBack) {
            (sensorOrientation + displayRotation * 90 + 360) % 360
        } else {
            (sensorOrientation - displayRotation * 90 + 360) % 360
        }
    }

    private fun saveJpeg(bytes: ByteArray) {
        runCatching {
            val dir = File(context.getExternalFilesDir(null), "photos").apply { mkdirs() }
            val file = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { it.write(bytes) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sample = (bounds.outWidth / 320).coerceAtLeast(1)
            val thumb = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
            listener.onPhotoSaved(file, thumb)
        }.onFailure { Log.e(TAG, "saveJpeg failed", it) }
    }

    private fun pickSize(sizes: Array<Size>?, maxWidth: Int): Size {
        if (sizes.isNullOrEmpty()) return Size(1280, 720)
        return sizes
            .filter { it.width <= maxWidth && it.width >= 640 }
            .minByOrNull { kotlin.math.abs(it.width.toFloat() / it.height - 16f / 9f) }
            ?: sizes.minByOrNull { it.width }
            ?: Size(1280, 720)
    }
}
