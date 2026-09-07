package com.nic.lgpower

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Bottom sheet listing [releases]; serves both the after-update popup and the full history. */
fun Activity.showReleaseNotesSheet(
    title: String,
    releases: List<ReleaseNotes.Release>,
    buttonLabel: String,
    onDismiss: (() -> Unit)? = null
) {
    val dialog = Dialog(this)
    dialog.setContentView(R.layout.dialog_input_picker)
    val theme = ThemeManager.getActiveTheme(this)
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.BOTTOM)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(0.5f)
        attributes = attributes.also { it.windowAnimations = android.R.style.Animation_InputMethod }
    }

    dialog.findViewById<LinearLayout>(R.id.picker_root).background = GradientDrawable().apply {
        setColor(theme.windowBg)
        val r = 20 * d
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }

    dialog.findViewById<TextView>(R.id.dialog_picker_title).apply {
        text = title.uppercase()
        setTextColor(theme.sectionLabel)
    }

    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(theme.surfaceBg)
            cornerRadius = 14 * d
        }
        clipToOutline = true
        setPadding(0, dp(4), 0, dp(8))
    }

    releases.forEachIndexed { i, release ->
        if (i > 0) {
            card.addView(View(this).apply {
                setBackgroundColor(theme.divider)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    .also { it.marginStart = dp(16); it.topMargin = dp(8); it.bottomMargin = dp(4) }
            })
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        header.addView(TextView(this).apply {
            text = release.name
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(theme.primaryText)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = release.date
            textSize = 12f
            setTextColor(theme.secondaryText)
        })
        card.addView(header)
        release.notes.forEach { note ->
            card.addView(TextView(this).apply {
                text = "•  $note"
                textSize = 14f
                setLineSpacing(0f, 1.15f)
                setTextColor(theme.primaryText)
                setPadding(dp(16), 0, dp(16), dp(6))
            })
        }
    }

    // Cap the sheet so the full history scrolls instead of filling the screen
    val maxHeight = (resources.displayMetrics.heightPixels * 0.68).toInt()
    val scroll = object : ScrollView(this) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
    }.apply {
        isVerticalScrollBarEnabled = false
        addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    val button = TextView(this).apply {
        text = buttonLabel
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(theme.btnAccentText)
        background = GradientDrawable().apply {
            setColor(theme.btnAccentBg)
            cornerRadius = 12 * d
        }
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46))
            .also { it.topMargin = dp(12) }
        setOnClickListener { dialog.dismiss() }
    }

    dialog.findViewById<LinearLayout>(R.id.inputs_container).apply {
        addView(scroll)
        addView(button)
    }
    if (onDismiss != null) dialog.setOnDismissListener { onDismiss() }
    dialog.show()
}
