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

    /** The display's own corner radius for [position], so the line hugs the glass on any phone. */
    private fun cornerRadius(position: Int): Float {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val r = rootWindowInsets?.getRoundedCorner(position)?.radius
            if (r != null && r > 0) return r.toFloat()
        }
        return 40f * density
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); requestLayout() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // 1dp inset so the full stroke width is visible
        val i  = 1f * density
        val cx = w / 2f
        val tl = cornerRadius(android.view.RoundedCorner.POSITION_TOP_LEFT)
        val tr = cornerRadius(android.view.RoundedCorner.POSITION_TOP_RIGHT)
        val bl = cornerRadius(android.view.RoundedCorner.POSITION_BOTTOM_LEFT)
        val br = cornerRadius(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT)

        leftPath.reset()
        leftPath.moveTo(cx, h - i)
        leftPath.lineTo(i + bl, h - i)
        leftPath.quadTo(i, h - i, i, h - i - bl)
        leftPath.lineTo(i, i + tl)
        leftPath.quadTo(i, i, i + tl, i)
        leftPath.lineTo(cx, i)
        lm = PathMeasure(leftPath, false)

        rightPath.reset()
        rightPath.moveTo(cx, h - i)
        rightPath.lineTo(w - i - br, h - i)
        rightPath.quadTo(w - i, h - i, w - i, h - i - br)
        rightPath.lineTo(w - i, i + tr)
        rightPath.quadTo(w - i, i, w - i - tr, i)
        rightPath.lineTo(cx, i)
        rm = PathMeasure(rightPath, false)
    }

    /** Line colour to suit the scrim it sits on. */
    fun setOnLightScrim(light: Boolean) {
        linePaint.color = if (light) 0xFF1E1B1B.toInt() else Color.WHITE
        haloPaint.color = if (light) Color.argb(40, 0, 0, 0) else Color.argb(60, 255, 255, 255)
        invalidate()
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
