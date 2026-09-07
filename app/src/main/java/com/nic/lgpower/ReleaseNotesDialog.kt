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

/** Centered dialog listing [releases]; serves both the after-update popup and the full history. */
fun Activity.showReleaseNotesDialog(
    title: String,
    releases: List<ReleaseNotes.Release>,
    buttonLabel: String,
    onDismiss: (() -> Unit)? = null
) {
    val theme = ThemeManager.getActiveTheme(this)
    val metrics = resources.displayMetrics
    val d = metrics.density
    fun dp(v: Int) = (v * d).toInt()
    val match = LinearLayout.LayoutParams.MATCH_PARENT
    val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
    val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy")

    // Nesting is inverted against the settings screen (surface card holding window-coloured
    // groups) so the dialog reads as one raised panel on the dimmed remote in both themes
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(theme.surfaceBg)
            cornerRadius = 24 * d
        }
        setPadding(dp(22), dp(24), dp(22), dp(20))
    }

    root.addView(TextView(this).apply {
        text = title
        textSize = 24f
        letterSpacing = -0.015f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(theme.primaryText)
        layoutParams = LinearLayout.LayoutParams(match, wrap).also { it.bottomMargin = dp(18) }
    })

    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    releases.forEachIndexed { i, release ->
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), if (i == 0) 0 else dp(18), dp(2), dp(8))
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
                setColor(theme.windowBg)
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
                setPadding(dp(16), dp(13), dp(16), dp(13))
            }
            row.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(theme.secondaryText)
                }
                layoutParams = LinearLayout.LayoutParams(dp(5), dp(5)).also {
                    it.topMargin = dp(8); it.marginStart = dp(2); it.marginEnd = dp(14)
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

    // Cap the list so the full history scrolls inside the dialog instead of filling the screen
    val maxHeight = (metrics.heightPixels * 0.62).toInt()
    val scroll = object : ScrollView(this) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
    }.apply {
        isVerticalScrollBarEnabled = false
        addView(content, LinearLayout.LayoutParams(match, wrap))
    }
    root.addView(scroll, LinearLayout.LayoutParams(match, wrap))

    val dialog = Dialog(this)
    root.addView(TextView(this).apply {
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
        layoutParams = LinearLayout.LayoutParams(match, dp(46)).also { it.topMargin = dp(18) }
        setOnClickListener { dialog.dismiss() }
    })

    dialog.setContentView(root)
    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        setLayout(metrics.widthPixels - dp(48), WindowManager.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.CENTER)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(0.6f)
    }
    if (onDismiss != null) dialog.setOnDismissListener { onDismiss() }
    dialog.show()
}
