package com.nic.lgpower

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Walkthrough that dims the whole remote except the control being explained, with a
 * card next to it. Runs on top of the activity's window so nothing underneath moves.
 */
object Tour {
    data class Step(val targetId: Int, val title: String, val body: String)

    val steps = listOf(
        Step(R.id.shortcuts_row, "App shortcuts",
            "These launch apps on the TV. Pick up to eight under Settings › Load apps from TV, long-press one there to reorder."),
        Step(R.id.btn_touchpad, "Touchpad",
            "Tap and drag straight from this button to move the pointer. Hold it for a moment to lock the touchpad open; Back closes it."),
        Step(R.id.btn_numpad, "Numpad and more keys",
            "Channel numbers, Guide, Info, subtitles, an OK key and the media keys live here. Settings can move the media keys onto the remote."),
        Step(R.id.tv_main_title, "Your TVs",
            "This is the current TV. Tap it to switch to another saved TV or to add one. Settings › TVs is where you rename or remove them."),
    )
}

fun Activity.showSpotlightTour(onDone: (() -> Unit)? = null) {
    val decor = window.decorView as ViewGroup
    val view = SpotlightView(this, Tour.steps) {
        decor.removeView(it)
        onDone?.invoke()
    }
    decor.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    view.requestFocus()
}

private class SpotlightView(
    context: Context,
    private val steps: List<Tour.Step>,
    private val onFinished: (SpotlightView) -> Unit
) : FrameLayout(context) {

    private val theme = ThemeManager.getActiveTheme(context)
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val scrimPaint = Paint().apply { color = 0xC4000000.toInt() }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0x66FFFFFF
    }
    private val hole = RectF()
    private var index = -1
    private var animator: ValueAnimator? = null

    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(14))
        background = GradientDrawable().apply { cornerRadius = 16 * d; setColor(theme.surfaceBg) }
        elevation = 8 * d
        isClickable = true
    }
    private val title = TextView(context).apply {
        textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(theme.primaryText)
    }
    private val body = TextView(context).apply {
        textSize = 13f; setTextColor(theme.secondaryText); setPadding(0, dp(6), 0, dp(14))
    }
    private val dots = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val dotViews = steps.map {
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(5) }
            dots.addView(this)
        }
    }
    private val skip = TextView(context).apply {
        text = "Skip"; textSize = 13f; setTextColor(theme.secondaryText)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { finish() }
    }
    private val next = TextView(context).apply {
        textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(theme.btnAccentText)
        background = GradientDrawable().apply { cornerRadius = 99 * d; setColor(theme.btnAccentBg) }
        setPadding(dp(18), dp(8), dp(18), dp(8))
        setOnClickListener { advance() }
    }

    init {
        setWillNotDraw(false)
        // CLEAR only punches through on a layer, otherwise it erases the window itself
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isFocusableInTouchMode = true
        card.addView(title)
        card.addView(body)
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(dots, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(skip)
            addView(next, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        }
        card.addView(footer)
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(20); rightMargin = dp(20)
        })
        alpha = 0f
        animate().alpha(1f).setDuration(220).start()
        post { advance() }
    }

    private fun targetRect(step: Tour.Step): RectF? {
        val target = (context as Activity).findViewById<View>(step.targetId) ?: return null
        if (target.visibility != View.VISIBLE || target.width == 0) return null
        val loc = IntArray(2); target.getLocationInWindow(loc)
        val mine = IntArray(2); getLocationInWindow(mine)
        val pad = 8 * d
        return RectF(
            loc[0] - mine[0] - pad, loc[1] - mine[1] - pad,
            loc[0] - mine[0] + target.width + pad, loc[1] - mine[1] + target.height + pad
        )
    }

    private fun advance() {
        var nextIndex = index + 1
        var rect: RectF? = null
        while (nextIndex < steps.size) {
            rect = targetRect(steps[nextIndex])
            if (rect != null) break
            nextIndex++
        }
        if (rect == null) { finish(); return }
        index = nextIndex
        val step = steps[index]
        title.text = step.title
        body.text = step.body
        next.text = if (index == steps.lastIndex) "Done" else "Next"
        skip.visibility = if (index == steps.lastIndex) View.GONE else View.VISIBLE
        dotViews.forEachIndexed { i, v ->
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (i == index) theme.btnAccentBg else theme.divider)
            }
        }
        placeCard(rect)
        animator?.cancel()
        val from = RectF(hole)
        if (from.isEmpty) { hole.set(rect); invalidate(); return }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                hole.set(
                    from.left + (rect.left - from.left) * t, from.top + (rect.top - from.top) * t,
                    from.right + (rect.right - from.right) * t, from.bottom + (rect.bottom - from.bottom) * t
                )
                invalidate()
            }
            start()
        }
    }

    // Card goes under the highlighted control when that sits in the top half, above it otherwise
    private fun placeCard(rect: RectF) {
        card.measure(
            MeasureSpec.makeMeasureSpec(width - dp(40), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST)
        )
        val lp = card.layoutParams as LayoutParams
        val gap = dp(16)
        lp.gravity = Gravity.TOP
        lp.topMargin = if (rect.centerY() < height / 2f) (rect.bottom + gap).toInt()
                       else (rect.top - gap - card.measuredHeight).toInt().coerceAtLeast(dp(8))
        card.layoutParams = lp
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        if (!hole.isEmpty) {
            val r = 18 * d
            canvas.drawRoundRect(hole, r, r, clearPaint)
            canvas.drawRoundRect(hole, r, r, ringPaint)
        }
    }

    // Everything outside the card advances; the highlighted control itself stays inert
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) advance()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun finish() {
        animator?.cancel()
        animate().alpha(0f).setDuration(180).withEndAction { onFinished(this) }.start()
    }
}
