package com.nic.lgpower

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.util.AttributeSet
import android.view.View

class LockBorderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    // Both passes use the same path — halo blur spreads inward,
    // outer half gets clipped by the screen edge naturally
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(10f * density, BlurMaskFilter.Blur.NORMAL)
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val leftPath  = Path()
    private val rightPath = Path()
    private val seg       = Path()
    private var lm = PathMeasure()
    private var rm = PathMeasure()
    private var progress = 0f

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // 1dp inset so the full stroke width is visible, large corner to clear rounded screen glass
        val i  = 1f * density
        val r  = 40f * density
        val cx = w / 2f

        leftPath.reset()
        leftPath.moveTo(cx, h - i)
        leftPath.lineTo(i + r, h - i)
        leftPath.quadTo(i, h - i, i, h - i - r)
        leftPath.lineTo(i, i + r)
        leftPath.quadTo(i, i, i + r, i)
        leftPath.lineTo(cx, i)
        lm = PathMeasure(leftPath, false)

        rightPath.reset()
        rightPath.moveTo(cx, h - i)
        rightPath.lineTo(w - i - r, h - i)
        rightPath.quadTo(w - i, h - i, w - i, h - i - r)
        rightPath.lineTo(w - i, i + r)
        rightPath.quadTo(w - i, i, w - i - r, i)
        rightPath.lineTo(cx, i)
        rm = PathMeasure(rightPath, false)
    }

    fun setProgress(p: Float) { progress = p.coerceIn(0f, 1f); invalidate() }

    private fun drawSeg(canvas: Canvas, measure: PathMeasure, paint: Paint) {
        seg.reset()
        measure.getSegment(0f, measure.length * progress, seg, true)
        canvas.drawPath(seg, paint)
    }

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0f) return
        drawSeg(canvas, lm, haloPaint)
        drawSeg(canvas, rm, haloPaint)
        drawSeg(canvas, lm, linePaint)
        drawSeg(canvas, rm, linePaint)
    }
}
