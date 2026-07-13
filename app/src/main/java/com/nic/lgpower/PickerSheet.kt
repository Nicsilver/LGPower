package com.nic.lgpower

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

fun Activity.showPickerSheet(
    title: String,
    items: List<Triple<String, String, Boolean>>,
    onLongPress: ((String) -> Unit)? = null,
    onSelect: (String) -> Unit
) {
    val dialog = Dialog(this)
    dialog.setContentView(R.layout.dialog_input_picker)
    val theme = ThemeManager.getActiveTheme(this)
    val d = resources.displayMetrics.density

    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.BOTTOM)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(0.5f)
        attributes = attributes.also { it.windowAnimations = android.R.style.Animation_InputMethod }
    }

    val root = dialog.findViewById<LinearLayout>(R.id.picker_root)
    root.background = GradientDrawable().apply {
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
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    items.forEachIndexed { i, (id, label, isActive) ->
        if (i > 0) {
            card.addView(android.view.View(this).apply {
                setBackgroundColor(theme.divider)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    .also { it.marginStart = (16 * d).toInt() }
            })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            setPadding((16*d).toInt(), 0, (16*d).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52*d).toInt())
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(theme.primaryText)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (isActive) {
            row.addView(TextView(this).apply {
                text = "✓"
                textSize = 18f
                setTextColor(theme.btnAccentBg)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
        }
        row.setOnClickListener { dialog.dismiss(); onSelect(id) }
        if (onLongPress != null) row.setOnLongClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            dialog.dismiss(); onLongPress(id); true
        }
        card.addView(row)
    }

    dialog.findViewById<LinearLayout>(R.id.inputs_container).addView(card)
    dialog.show()
}
