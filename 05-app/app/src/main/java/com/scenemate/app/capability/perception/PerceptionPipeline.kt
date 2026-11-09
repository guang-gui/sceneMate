package com.scenemate.app.capability.perception

import android.graphics.PointF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.scenemate.app.capability.geometry.Landmark
import com.scenemate.app.capability.geometry.PoseObservation

/**
 * 端侧感知（ADR-011）。全部走 ML Kit **bundled**，不依赖 GMS。
 *
 * 姿态是测距主来源；人脸只用来数人数（多人态）与 L1 上传前模糊；
 * 分割不参与测距，v1 不引入——这是 Android 首发的关键取舍。
 */
class PerceptionPipeline {

    private val poseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build(),
    )

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.08f)
            .build(),
    )

    /** 同步阻塞，调用方必须在分析线程上。返回 null 表示没检出人。 */
    fun detectPose(image: InputImage): PoseObservation? {
        val pose: Pose = Tasks.await(poseDetector.process(image))
        val points = pose.allPoseLandmarks
        if (points.isEmpty()) return null

        fun lm(type: Int): Landmark? {
            val p = pose.getPoseLandmark(type) ?: return null
            return Landmark(p.position.x, p.position.y, p.inFrameLikelihood)
        }

        return PoseObservation(
            nose = lm(PoseLandmark.NOSE),
            leftEye = lm(PoseLandmark.LEFT_EYE),
            rightEye = lm(PoseLandmark.RIGHT_EYE),
            leftEar = lm(PoseLandmark.LEFT_EAR),
            rightEar = lm(PoseLandmark.RIGHT_EAR),
            leftShoulder = lm(PoseLandmark.LEFT_SHOULDER),
            rightShoulder = lm(PoseLandmark.RIGHT_SHOULDER),
            leftHip = lm(PoseLandmark.LEFT_HIP),
            rightHip = lm(PoseLandmark.RIGHT_HIP),
            leftKnee = lm(PoseLandmark.LEFT_KNEE),
            rightKnee = lm(PoseLandmark.RIGHT_KNEE),
            leftAnkle = lm(PoseLandmark.LEFT_ANKLE),
            rightAnkle = lm(PoseLandmark.RIGHT_ANKLE),
            allPoints = points.map { p: PoseLandmark ->
                Landmark(p.position.x, p.position.y, p.inFrameLikelihood)
            },
        )
    }

    /** 数人数。每 6 帧跑一次就够（多人态只需要知道「≥2」）。 */
    fun detectFaceCount(image: InputImage): Int =
        runCatching { Tasks.await(faceDetector.process(image)).size }.getOrDefault(0)

    fun close() {
        runCatching { poseDetector.close() }
        runCatching { faceDetector.close() }
    }

    private fun PoseLandmark.center(): PointF = position
}
