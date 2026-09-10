package com.nic.lgpower

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Locked-touchpad accent: a soft band of light along the left and right screen edges,
 * thickest at mid-height and fading out before the top and bottom. Side-only on purpose:
 * the overlay does not reach into the status bar, so a full frame never lines up with
 * the glass corners.
 */
class LockBorderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(110, 255, 255, 255)
        maskFilter = BlurMaskFilter(14f * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(230, 255, 255, 255)
    }
    private val band = Path()
    private var progress = 0f

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    fun setProgress(p: Float) { progress = p.coerceIn(0f, 1f); invalidate() }

    /** Band colour to suit the scrim it sits on. */
    fun setOnLightScrim(light: Boolean) {
        glow.color = if (light) Color.argb(70, 30, 27, 27) else Color.argb(110, 255, 255, 255)
        core.color = if (light) Color.argb(200, 30, 27, 27) else Color.argb(230, 255, 255, 255)
        invalidate()
    }

    // A lens against one edge: straight along the edge, bulging inward by `bulge` at mid-height
    private fun lens(x: Float, dir: Float, top: Float, bottom: Float, bulge: Float) {
        band.reset()
        band.moveTo(x, top)
        band.quadTo(x + dir * bulge * 2f, (top + bottom) / 2f, x, bottom)
        band.close()
    }

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0f) return
        val h = height.toFloat(); val w = width.toFloat()
        val top = h * 0.06f; val bottom = h * 0.94f
        val bulge = 9f * density * progress
        for ((x, dir) in listOf(0f to 1f, w to -1f)) {
            lens(x, dir, top, bottom, bulge); canvas.drawPath(band, glow)
            lens(x, dir, top, bottom, bulge * 0.45f); canvas.drawPath(band, core)
        }
    }
}
