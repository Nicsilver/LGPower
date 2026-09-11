package com.nic.lgpower

import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Bottom sheet for "are you sure" moments: a red chip, a title, a body and one accent
 * button. Cancelling (back / tap outside) runs [onCancel], and closes the screen when
 * [cancelClosesScreen] is set.
 */
fun android.app.Activity.showWarningSheet(
    chipText: String,
    title: String,
    body: String,
    buttonText: String,
    cancelClosesScreen: Boolean,
    onAccept: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null
) {
    val theme = ThemeManager.getActiveTheme(this)
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    val dialog = Dialog(this)
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(10), dp(20), dp(20))
        background = GradientDrawable().apply {
            setColor(theme.surfaceBg)
            val r = 20 * d
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
    }
    content.addView(View(this).apply {
        background = GradientDrawable().apply { cornerRadius = 2 * d; setColor(theme.divider) }
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14)
        }
    })
    content.addView(TextView(this).apply {
        text = chipText
        textSize = 10f
        letterSpacing = 0.09f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(0xFFE05555.toInt())
        background = GradientDrawable().apply {
            cornerRadius = 99 * d
            setStroke(dp(1), 0x59E05555)
        }
        setPadding(dp(9), dp(2), dp(9), dp(2))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(9) }
    })
    content.addView(TextView(this).apply {
        text = title
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(theme.primaryText)
    })
    content.addView(TextView(this).apply {
        text = body
        textSize = 13f
        setTextColor(theme.secondaryText)
        setPadding(0, dp(8), 0, dp(16))
    })
    content.addView(TextView(this).apply {
        text = buttonText
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(theme.btnAccentText)
        background = GradientDrawable().apply { cornerRadius = 11 * d; setColor(theme.btnAccentBg) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46))
        isClickable = true; isFocusable = true
        setOnClickListener {
            dialog.dismiss()
            onAccept?.invoke()
        }
    })

    dialog.setContentView(content)
    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.BOTTOM)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(0.55f)
        attributes = attributes.also { it.windowAnimations = android.R.style.Animation_InputMethod }
    }
    dialog.setOnCancelListener { onCancel?.invoke(); if (cancelClosesScreen) finish() }
    dialog.show()
}
