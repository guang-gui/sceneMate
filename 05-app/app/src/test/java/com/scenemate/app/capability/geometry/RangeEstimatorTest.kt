package com.scenemate.app.capability.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeEstimatorTest {

    private val estimator = RangeEstimator()

    /** 构造一个「3m 处 1.70m 身高」的合成姿态。 */
    private fun poseAt3m(
        frameHeightPx: Int = 1920,
        focalPx: Double = 2000.0,
    ): Pair<PoseObservation, FrameGeometry> {
        val frame = FrameGeometry(widthPx = 1080, heightPx = frameHeightPx, focalPx = focalPx)
        // h_px = f*H/d = 2000*1.7/3 = 1133.3px；span = h_px*(1-k_head-k_foot)
        val hPx = focalPx * BodyProportions.DEFAULT_HEIGHT_M / 3.0
        val spanNorm = hPx * (1 - BodyProportions.K_HEAD - BodyProportions.K_FOOT) / frameHeightPx
        val noseY = 0.20f
        val ankleY = (noseY + spanNorm).toFloat()
        val pose = PoseObservation(
            nose = Landmark(0.5f, noseY, 0.95f),
            leftAnkle = Landmark(0.48f, ankleY, 0.9f),
            rightAnkle = Landmark(0.52f, ankleY, 0.9f),
            leftShoulder = Landmark(0.44f, noseY + 0.08f, 0.9f),
            rightShoulder = Landmark(0.56f, noseY + 0.08f, 0.9f),
            allPoints = listOf(
                Landmark(0.5f, noseY, 0.95f),
                Landmark(0.44f, noseY + 0.08f, 0.9f),
                Landmark(0.56f, noseY + 0.08f, 0.9f),
                Landmark(0.48f, ankleY, 0.9f),
                Landmark(0.52f, ankleY, 0.9f),
            ),
        )
        return pose to frame
    }

    @Test
    fun `3m 处距离估计误差小于 5cm`() {
        val (pose, frame) = poseAt3m()
        val estimate = estimator.estimate(pose, frame)
        assertNotNull(estimate)
        assertEquals(3.0, estimate!!.distanceM, 0.05)
    }

    @Test
    fun `sigma 落在百分之六点五档`() {
        val (pose, frame) = poseAt3m()
        val estimate = estimator.estimate(pose, frame)!!
        assertEquals(3.0 * 0.065, estimate.sigmaM, 0.02)
    }

    @Test
    fun `主体像素占比与身高无关`() {
        val (pose, frame) = poseAt3m()
        val estimate = estimator.estimate(pose, frame)!!
        // alpha = h_px / frameHeight = 1133.3/1920
        assertEquals(0.590, estimate.alpha, 0.01)
    }

    @Test
    fun `脚部不可见时走降级链并放大 sigma`() {
        val (pose, frame) = poseAt3m()
        val degraded = pose.copy(
            leftAnkle = null,
            rightAnkle = null,
            leftEye = Landmark(0.49f, 0.19f, 0.9f),
            rightEye = Landmark(0.51f, 0.19f, 0.9f),
            leftKnee = Landmark(0.48f, 0.60f, 0.8f),
            rightKnee = Landmark(0.52f, 0.60f, 0.8f),
        )
        val estimate = estimator.estimate(degraded, frame)
        assertNotNull(estimate)
        assertEquals(2, estimate!!.level)
        assertTrue(estimate.sigmaM > 3.0 * 0.065)
    }

    @Test
    fun `关键点不足时不出估计`() {
        val frame = FrameGeometry(1080, 1920, 2000.0)
        assertNull(estimator.estimate(PoseObservation(), frame))
    }

    @Test
    fun `求解器在 3m 处要求向前一步`() {
        val (pose, frame) = poseAt3m()
        val estimate = estimator.estimate(pose, frame)!!
        val solution = StanceSolver().solve(estimate, frame, Templates.FULL_BODY)
        // alpha 0.59 < 目标 0.72 → 需要靠近
        assertTrue("deltaBack=${solution.deltaBackM}", solution.deltaBackM < -0.4)
        assertEquals(0.0, solution.deltaLateralM, 0.05)
    }

    @Test
    fun `站位框死区在 0_25m 内不触发`() {
        val (pose, frame) = poseAt3m()
        val estimate = estimator.estimate(pose, frame)!!
        // 目标占比设成当前占比 → 位移为 0
        val solution = StanceSolver().solve(
            estimate, frame,
            CompositionTemplate("same", estimate.alpha, 0.5),
        )
        assertTrue(solution.insideDeadZone)
    }

    @Test
    fun `脚印宽度随距离收缩`() {
        val near = estimator.estimate(poseAt3m(focalPx = 4000.0).first, poseAt3m(focalPx = 4000.0).second)
        val far = estimator.estimate(poseAt3m(focalPx = 1000.0).first, poseAt3m(focalPx = 1000.0).second)
        assertNotNull(near)
        assertNotNull(far)
        val solver = StanceSolver()
        val nearSolution = solver.solve(near!!, poseAt3m(focalPx = 4000.0).second)
        val farSolution = solver.solve(far!!, poseAt3m(focalPx = 1000.0).second)
        assertTrue(nearSolution.footprintNorm.width > farSolution.footprintNorm.width)
    }
}
