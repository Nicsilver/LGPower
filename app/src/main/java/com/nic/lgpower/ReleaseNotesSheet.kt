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
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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
    val match = LinearLayout.LayoutParams.MATCH_PARENT
    val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
    val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy")

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

    // The shared picker layout styles its title as a small section label; this sheet
    // wants a real heading
    dialog.findViewById<TextView>(R.id.dialog_picker_title).apply {
        text = title
        textSize = 24f
        letterSpacing = -0.015f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(theme.primaryText)
        (layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = dp(14)
    }

    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    releases.forEachIndexed { i, release ->
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), if (i == 0) 0 else dp(14), dp(4), dp(8))
        }
        header.addView(TextView(this).apply {
            text = release.name
            textSize = 22f
            letterSpacing = -0.02f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(theme.primaryText)
        })
        if (i == 0) header.addView(TextView(this).apply {
            text = "LATEST"
            textSize = 10f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(theme.btnAccentText)
            background = GradientDrawable().apply {
                setColor(theme.btnAccentBg)
                cornerRadius = 6 * d
            }
            setPadding(dp(7), dp(3), dp(7), dp(3))
            layoutParams = LinearLayout.LayoutParams(wrap, wrap).also { it.marginStart = dp(10) }
        })
        header.addView(TextView(this).apply {
            text = LocalDate.parse(release.date).format(dateFormat)
            textSize = 13f
            setTextColor(theme.secondaryText)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, wrap, 1f)
        })
        content.addView(header)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(theme.surfaceBg)
                cornerRadius = 14 * d
            }
            clipToOutline = true
        }
        release.notes.forEachIndexed { j, note ->
            if (j > 0) card.addView(View(this).apply {
                setBackgroundColor(theme.divider)
                layoutParams = LinearLayout.LayoutParams(match, 1).also { it.marginStart = dp(16) }
            })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            row.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(theme.secondaryText)
                }
                layoutParams = LinearLayout.LayoutParams(dp(5), dp(5)).also {
                    it.topMargin = dp(8); it.marginStart = dp(4); it.marginEnd = dp(15)
                }
            })
            row.addView(TextView(this).apply {
                text = note
                textSize = 15f
                setLineSpacing(0f, 1.25f)
                setTextColor(theme.primaryText)
                layoutParams = LinearLayout.LayoutParams(0, wrap, 1f)
            })
            card.addView(row)
        }
        content.addView(card)
    }

    // Cap the sheet so the full history scrolls instead of filling the screen
    val maxHeight = (resources.displayMetrics.heightPixels * 0.68).toInt()
    val scroll = object : ScrollView(this) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
    }.apply {
        isVerticalScrollBarEnabled = false
        addView(content, LinearLayout.LayoutParams(match, wrap))
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
        layoutParams = LinearLayout.LayoutParams(match, dp(46)).also { it.topMargin = dp(14) }
        setOnClickListener { dialog.dismiss() }
    }

    dialog.findViewById<LinearLayout>(R.id.inputs_container).apply {
        addView(scroll)
        addView(button)
    }
    if (onDismiss != null) dialog.setOnDismissListener { onDismiss() }
    dialog.show()
}
