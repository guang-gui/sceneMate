package com.scenemate.app.presentation.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.scenemate.app.domain.NormRect
import com.scenemate.app.domain.Tier

/**
 * 单一叠加层渲染管线（ADR-003）。
 *
 * 图层树 + 声明式描述，单 pass 合成：
 *   SubjectSilhouette → StanceFootprint → DistanceLabel → InstructionChip
 * v1.1 加构图线/海平面只是往 [drawLayers] 里插两行，不动渲染循环。
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        const val ACCENT = 0xFF4FE3C1.toInt()
        const val WARN = 0xFFFFC46B.toInt()
        const val TEXT = 0xFFF4F6F8.toInt()

        /** R01：主体包围盒外扩 15% 内不得绘制文字 */
        const val TEXT_AVOID_RATIO = 0.15f
    }

    private var frame: OverlayFrame = OverlayFrame()
    private var frozenFrame: OverlayFrame? = null

    private val footprintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = ACCENT
        pathEffect = DashPathEffect(floatArrayOf(14f, 12f), 0f)
    }
    private val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = ACCENT
    }
    private val silhouettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = ACCENT
        pathEffect = DashPathEffect(floatArrayOf(8f, 10f), 0f)
    }
    private val chipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xCC0A0B0F.toInt()
    }
    private val chipBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = ACCENT
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT
        textSize = 42f
        isFakeBoldText = true
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT
        textSize = 32f
    }
    private val skeletonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x664FE3C1
    }

    fun setFrame(next: OverlayFrame) {
        if (frame.frozen && !next.frozen) {
            // 冻结期间不接受新帧（US-4 倒计时 / R16 定时器）
            return
        }
        frame = next
        invalidate()
    }

    fun freeze() {
        frozenFrame = frame
        frame = frame.copy(frozen = true)
        invalidate()
    }

    fun unfreeze() {
        frame = frame.copy(frozen = false)
        frozenFrame = null
        invalidate()
    }

    fun clear() {
        frame = OverlayFrame()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val f = frame
        if (f.showSilhouette) drawSilhouette(canvas, f.subjectBox, w, h)
        if (f.footprint != null) drawFootprint(canvas, f.footprint, w, h)
        if (f.instruction != null) drawInstruction(canvas, f, w, h)
        if (f.distanceLabel != null) drawDistanceLabel(canvas, f, w, h)
    }

    private fun RectF.toCanvas(rect: NormRect, w: Float, h: Float): RectF {
        set(rect.left * w, rect.top * h, rect.right * w, rect.bottom * h)
        return this
    }

    /** Layer 1：人形轮廓。只画包围盒的虚线 + 肩线，避免遮挡表情（PRD 3.5）。 */
    private fun drawSilhouette(canvas: Canvas, box: NormRect?, w: Float, h: Float) {
        if (box == null) return
        val rect = RectF().toCanvas(box, w, h)
        if (frame.tier == Tier.STRONG) {
            canvas.drawRoundRect(rect, 24f, 24f, silhouettePaint)
            val shoulderY = rect.top + rect.height() * 0.18f
            canvas.drawLine(rect.left, shoulderY, rect.right, shoulderY, skeletonPaint)
        }
    }

    /** Layer 2：脚印 + 虚线站位框。这是整套产品最难抄的一层。 */
    private fun drawFootprint(canvas: Canvas, foot: NormRect, w: Float, h: Float) {
        val rect = RectF().toCanvas(foot, w, h)
        val stanceWidth = rect.width() * 1.6f
        val stanceLeft = rect.centerX() - stanceWidth / 2f
        val stanceRect = RectF(stanceLeft, rect.top - rect.height() * 0.4f, stanceLeft + stanceWidth, rect.bottom + rect.height() * 0.35f)

        canvas.drawRoundRect(stanceRect, 18f, 18f, footprintPaint)

        val footW = rect.width() * 0.30f
        val footH = rect.height() * 0.85f
        val gap = rect.width() * 0.22f
        val cy = rect.centerY()

        val leftFoot = RectF(rect.centerX() - gap - footW, cy - footH / 2f, rect.centerX() - gap, cy + footH / 2f)
        val rightFoot = RectF(rect.centerX() + gap, cy - footH / 2f, rect.centerX() + gap + footW, cy + footH / 2f)
        canvas.drawOval(leftFoot, solidPaint)
        canvas.drawOval(rightFoot, solidPaint)
    }

    /**
     * Layer 4：首屏唯一一条指令。
     * 避让规则：主体包围盒外扩 15% 是禁区，禁区内的锚点全部淘汰；
     * 全淘汰时退化为底部胶囊单行模式（不画引线）。
     */
    private fun drawInstruction(canvas: Canvas, f: OverlayFrame, w: Float, h: Float) {
        val text = f.instruction ?: return
        val forbidden = f.subjectBox?.let {
            val r = RectF().toCanvas(it, w, h)
            val dx = r.width() * TEXT_AVOID_RATIO
            val dy = r.height() * TEXT_AVOID_RATIO
            RectF(r.left - dx, r.top - dy, r.right + dx, r.bottom + dy)
        }

        val padding = 26f
        val textWidth = textPaint.measureText(text)
        val chipW = textWidth + padding * 2
        val chipH = textPaint.textSize + padding * 1.2f

        val anchor = pickAnchor(w, h, chipW, chipH, forbidden)
        val left = anchor.x.coerceIn(16f, w - chipW - 16f)
        val top = anchor.y.coerceIn(16f, h - chipH - 16f)
        val rect = RectF(left, top, left + chipW, top + chipH)

        canvas.drawRoundRect(rect, chipH / 2f, chipH / 2f, chipBgPaint)
        canvas.drawRoundRect(rect, chipH / 2f, chipH / 2f, chipBorderPaint)
        canvas.drawText(text, rect.left + padding, rect.centerY() + textPaint.textSize * 0.36f, textPaint)
    }

    private data class Anchor(val x: Float, val y: Float)

    /**
     * 锚点必须避开底部控件区（EXIF 条 + 快门行）与顶部胶囊。
     * 用比例而不是固定像素，避免不同分辨率下压到快门。
     */
    private fun pickAnchor(w: Float, h: Float, chipW: Float, chipH: Float, forbidden: RectF?): Anchor {
        val bottomY = h * 0.74f
        val topY = h * 0.16f
        val candidates = listOf(
            Anchor((w - chipW) / 2f, bottomY),
            Anchor((w - chipW) / 2f, topY),
            Anchor(24f, bottomY),
            Anchor(w - chipW - 24f, bottomY),
            Anchor(24f, topY),
            Anchor(w - chipW - 24f, topY),
        )
        if (forbidden == null) return candidates.first()
        return candidates.firstOrNull { c ->
            !RectF.intersects(RectF(c.x, c.y, c.x + chipW, c.y + chipH), forbidden)
        } ?: candidates.first()
    }

    /** Layer 3：相对位移。绝对米数在这里被彻底排除（range_tier=default）。 */
    private fun drawDistanceLabel(canvas: Canvas, f: OverlayFrame, w: Float, h: Float) {
        val label = f.distanceLabel ?: return
        val box = f.footprint ?: return
        val rect = RectF().toCanvas(box, w, h)
        val textWidth = labelPaint.measureText(label)
        val x = (rect.centerX() - textWidth / 2f).coerceIn(12f, w - textWidth - 12f)
        val y = (rect.top - 18f).coerceAtLeast(60f)
        canvas.drawText(label, x, y, labelPaint)
    }

    fun debugSkeleton(points: List<Float>) {
        frame = frame.copy(skeleton = points)
        invalidate()
    }

    private fun pathOf(points: List<Float>): Path = Path().apply {
        var i = 0
        while (i + 3 < points.size) {
            moveTo(points[i], points[i + 1])
            lineTo(points[i + 2], points[i + 3])
            i += 4
        }
    }

    init {
        setWillNotDraw(false)
        chipBgPaint.color = Color.argb(204, 10, 11, 15)
    }
}
