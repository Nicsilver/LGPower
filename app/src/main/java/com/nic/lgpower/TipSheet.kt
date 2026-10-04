package com.nic.lgpower

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

fun Activity.showTipSheet(options: List<TipOption>, onPick: (TipOption) -> Unit) {
    val theme = ThemeManager.getActiveTheme(this)
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    val dialog = Dialog(this)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(32))
        background = GradientDrawable().apply {
            setColor(theme.windowBg)
            val r = 20 * d
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
    }
    root.addView(TextView(this).apply {
        text = "LEAVE A TIP"
        textSize = 12f
        letterSpacing = 0.06f
        setTextColor(theme.sectionLabel)
        setPadding(dp(4), 0, dp(4), dp(8))
    })

    val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    options.forEachIndexed { i, option ->
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true; isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(ColorUtil.withAlpha(theme.primaryText, 0x2A)),
                GradientDrawable().apply { setColor(theme.surfaceBg); cornerRadius = 14 * d },
                GradientDrawable().apply { setColor(android.graphics.Color.WHITE); cornerRadius = 14 * d })
            layoutParams = LinearLayout.LayoutParams(0, dp(104), 1f).apply {
                if (i > 0) marginStart = dp(8)
            }
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                // Let the ripple show before the sheet goes away
                it.postDelayed({ dialog.dismiss(); onPick(option) }, 110)
            }
        }
        tile.addView(TextView(this).apply {
            text = option.label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(theme.secondaryText)
        })
        tile.addView(TextView(this).apply {
            text = option.price
            textSize = 22f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(theme.primaryText)
            setPadding(0, dp(8), 0, 0)
        })
        tiles.addView(tile)
    }
    root.addView(tiles)
    root.addView(TextView(this).apply {
        text = "Doesn't unlock anything. Thanks for thinking of it."
        textSize = 12f
        setTextColor(theme.secondaryText)
        setPadding(dp(4), dp(14), dp(4), 0)
    })

    dialog.setContentView(root)
    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.BOTTOM)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(0.54f)
        attributes = attributes.also { it.windowAnimations = android.R.style.Animation_InputMethod }
    }
    dialog.show()
}
