package com.nic.lgpower

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The touchpad scrim, revealed as a soft-edged disc growing out of the Touchpad button
 * while it is held. Progress 0..1 is the hold; at 1 the disc covers the whole screen and
 * the overlay is "locked". A small progress value leaves a shadow around the button, used
 * while dragging without locking.
 */
class LockRevealView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val blur = 28f * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
    }
    private val solid = Paint()
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density }

    private var cx = 0f
    private var cy = 0f
    private var progress = 0f

    var scrimColor: Int = 0xEE000000.toInt()
        set(value) {
            field = value; paint.color = value; solid.color = value
            // A thin rim that contrasts with the scrim so the edge of the disc reads clearly
            val light = (android.graphics.Color.red(value) + android.graphics.Color.green(value) + android.graphics.Color.blue(value)) > 384
            edge.color = if (light) 0x40000000 else 0x70FFFFFF
            invalidate()
        }

    init { setLayerType(LAYER_TYPE_SOFTWARE, null); paint.color = scrimColor; solid.color = scrimColor }

    fun setCenter(x: Float, y: Float) { cx = x; cy = y; invalidate() }

    fun setProgress(p: Float) { progress = p.coerceIn(0f, 1f); invalidate() }

    fun getProgress() = progress

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0f) return
        if (progress >= 1f) { canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), solid); return }
        // Farthest corner plus the blur, so the disc really covers everything at 1
        val maxR = max(max(hypot(cx, cy), hypot(width - cx, cy)), max(hypot(cx, height - cy), hypot(width - cx, height - cy))) + blur
        // Area-linear growth reads as a steady fill rather than a slow start
        val r = maxR * sqrt(progress)
        canvas.drawCircle(cx, cy, r, paint)
        canvas.drawCircle(cx, cy, r, edge)
    }
}
