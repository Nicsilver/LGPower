package com.nic.lgpower

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
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
 * Walkthrough that dims the whole screen except the control being explained, with a
 * card next to it. Runs on top of the activity's window so nothing underneath moves.
 * The remote's tour hands over to a second leg inside Settings.
 */
object Tour {
    const val PREF_MAIN = "tour_pending"
    const val PREF_SETTINGS = "tour_settings_pending"

    /** [parentOf] highlights the target's parent row instead of the view itself. */
    data class Step(val targetIds: List<Int>, val title: String, val body: String, val parentOf: Boolean = false) {
        constructor(targetId: Int, title: String, body: String, parentOf: Boolean = false) :
            this(listOf(targetId), title, body, parentOf)
    }

    // Ordered top to bottom on each screen so the ring only ever travels one way
    val mainSteps = listOf(
        Step(R.id.tv_main_title, "Your TVs",
            "The current TV. Tap it to switch to another saved TV or to add one."),
        Step(R.id.btn_app_settings, "Settings",
            "Shortcuts, themes and the rest live behind the gear. The tour ends in there."),
        Step(R.id.shortcuts_row, "App shortcuts",
            "These launch apps on the TV. You pick them in Settings, up to eight."),
        Step(listOf(R.id.btn_power, R.id.status_dot), "Power",
            "Turns the TV on from standby over the network, or with the phone's IR blaster if it has one. The dot in the corner shows whether the TV is on."),
        Step(R.id.btn_touchpad, "Touchpad",
            "Tap and drag straight from this button to move the pointer. Hold it for a moment to lock the touchpad open; Back closes it."),
        Step(R.id.btn_keyboard, "Keyboard",
            "Type on the phone, send to the TV. Works in the TV's search and browser; YouTube and Netflix only accept their own on-screen keyboard."),
        Step(R.id.volume_pill, "Volume",
            "Tap the ends to step, or drag anywhere on the pill to slide. The phone's volume keys work here too."),
        Step(R.id.btn_numpad, "Numpad and more keys",
            "Channel numbers, Guide, Info, subtitles, an OK key and the media keys live here. Next opens Settings."),
    )

    val settingsSteps = listOf(
        Step(R.id.group_connection, "Saved TVs",
            "Every TV you have paired. Tap one to rename it or change its address, or add another."),
        Step(R.id.group_shortcuts, "Pick your shortcuts",
            "Load the app list from the TV, then tap up to eight apps. Long-press one and drag to reorder them."),
        Step(R.id.group_appearance, "Themes",
            "Pick a theme, or create your own with a few colours."),
    )
}

fun Activity.showSpotlightTour(steps: List<Tour.Step>, lastLabel: String = "Done", onDone: (() -> Unit)? = null) {
    val decor = window.decorView as ViewGroup
    val view = SpotlightView(this, steps, lastLabel) { v, completed ->
        decor.removeView(v)
        if (completed) onDone?.invoke()
    }
    decor.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    view.requestFocus()
}

private class SpotlightView(
    context: Context,
    private val steps: List<Tour.Step>,
    private val lastLabel: String,
    private val onFinished: (SpotlightView, Boolean) -> Unit
) : FrameLayout(context) {

    private val theme = ThemeManager.getActiveTheme(context)
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val scrimPaint = Paint().apply { color = 0xC4000000.toInt() }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0x66FFFFFF
    }
    private var holes: List<RectF> = emptyList()
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
    private fun ghostButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label; textSize = 13f; setTextColor(theme.secondaryText)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { onClick() }
    }
    private val back = ghostButton("Back") { go(index - 1) }
    private val skip = ghostButton("Skip") { finish(completed = false) }
    private val next = TextView(context).apply {
        textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(theme.btnAccentText)
        background = GradientDrawable().apply { cornerRadius = 99 * d; setColor(theme.btnAccentBg) }
        setPadding(dp(18), dp(8), dp(18), dp(8))
        setOnClickListener { go(index + 1) }
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
            addView(back)
            addView(skip)
            addView(next, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        }
        card.addView(footer)
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(20); rightMargin = dp(20)
        })
        alpha = 0f
        animate().alpha(1f).setDuration(220).start()
        post { go(0) }
    }

    private fun targetViews(step: Tour.Step): List<View> = step.targetIds.mapNotNull { id ->
        val v = (context as Activity).findViewById<View>(id) ?: return@mapNotNull null
        val t = if (step.parentOf) (v.parent as? View ?: v) else v
        t.takeIf { it.visibility == View.VISIBLE && it.width > 0 }
    }

    private fun rectOf(target: View): RectF {
        val loc = IntArray(2); target.getLocationInWindow(loc)
        val mine = IntArray(2); getLocationInWindow(mine)
        val pad = 8 * d
        val r = RectF(
            loc[0] - mine[0] - pad, loc[1] - mine[1] - pad,
            loc[0] - mine[0] + target.width + pad, loc[1] - mine[1] + target.height + pad
        )
        // Tiny targets (the status dot) still need a hole a finger can find
        val min = 40 * d
        if (r.width() < min) r.inset((r.width() - min) / 2, 0f)
        if (r.height() < min) r.inset(0f, (r.height() - min) / 2)
        return r
    }

    private fun go(to: Int) {
        if (to < 0) return
        if (to >= steps.size) { finish(completed = true); return }
        val targets = targetViews(steps[to])
        if (targets.isEmpty()) { go(if (to > index) to + 1 else to - 1); return }
        // A target further down a scrolling screen has to be brought on screen first;
        // the scroll is immediate, the layout catches up on the next frame
        val bounds = Rect(0, 0, width, height)
        val offScreen = targets.any { t ->
            val loc = IntArray(2); t.getLocationInWindow(loc)
            loc[1] < dp(80) || loc[1] + t.height > height - dp(80)
        }
        if (offScreen) {
            targets.first().requestRectangleOnScreen(Rect(0, -dp(120), targets.first().width, targets.first().height + dp(220)), true)
            postDelayed({ show(to, targets) }, 60)
        } else show(to, targets)
    }

    private fun show(to: Int, targets: List<View>) {
        index = to
        val step = steps[index]
        title.text = step.title
        body.text = step.body
        next.text = if (index == steps.lastIndex) lastLabel else "Next"
        back.visibility = if (index == 0) View.GONE else View.VISIBLE
        skip.visibility = if (index == steps.lastIndex) View.GONE else View.VISIBLE
        dotViews.forEachIndexed { i, v ->
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (i == index) theme.btnAccentBg else theme.divider)
            }
        }
        val rects = targets.map { rectOf(it) }
        val union = RectF(rects.first()).also { u -> rects.forEach { u.union(it) } }
        placeCard(union)

        animator?.cancel()
        val from = holes
        if (from.isEmpty()) { holes = rects; invalidate(); return }
        // Every hole glides from the nearest previous one, so a single ring appears to travel
        val starts = rects.map { r -> from.minByOrNull { f -> (f.centerX() - r.centerX()) * (f.centerX() - r.centerX()) + (f.centerY() - r.centerY()) * (f.centerY() - r.centerY()) } ?: r }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                holes = rects.mapIndexed { i, r ->
                    val s = starts[i]
                    RectF(s.left + (r.left - s.left) * t, s.top + (r.top - s.top) * t,
                          s.right + (r.right - s.right) * t, s.bottom + (r.bottom - s.bottom) * t)
                }
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
        val r = 18 * d
        holes.forEach { h ->
            canvas.drawRoundRect(h, r, r, clearPaint)
            canvas.drawRoundRect(h, r, r, ringPaint)
        }
    }

    // The dimmed area advances; the highlighted control is shown, not usable, so a tap
    // on it just nudges the ring instead of jumping ahead
    private var downInHole = false
    private fun inHole(x: Float, y: Float) = holes.any { it.contains(x, y) }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> downInHole = inHole(event.x, event.y)
            MotionEvent.ACTION_UP -> if (downInHole || inHole(event.x, event.y)) pulse() else go(index + 1)
        }
        return true
    }

    private fun pulse() {
        ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 260
            addUpdateListener { a ->
                ringPaint.strokeWidth = (1.5f + 2.5f * (a.animatedValue as Float)) * d
                ringPaint.alpha = (0x66 + (0x99 * (a.animatedValue as Float)).toInt()).coerceAtMost(255)
                invalidate()
            }
            start()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(completed = false); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun finish(completed: Boolean) {
        animator?.cancel()
        animate().alpha(0f).setDuration(180).withEndAction { onFinished(this, completed) }.start()
    }
}
