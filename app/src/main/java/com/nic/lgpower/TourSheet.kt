package com.nic.lgpower

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Three-card walkthrough of the parts of the remote nobody finds on their own.
 * Shown once after the first pairing and on demand from Settings › About.
 */
object Tour {
    data class Card(val icon: Int, val title: String, val body: String)

    val cards = listOf(
        Card(R.drawable.ic_apps, "App shortcuts",
            "The pills at the top launch apps on the TV. Pick up to eight under Settings › Load apps from TV, long-press one there to reorder."),
        Card(R.drawable.ic_touchpad, "Touchpad",
            "Tap the Touchpad button and drag straight away to move the pointer, no waiting. Hold the button for a moment to lock the touchpad open; Back closes it."),
        Card(R.drawable.ic_media, "More keys",
            "123 opens the numpad with Guide, Info, subtitles, an OK key and the media keys. Settings can swap the media and colour keys around."),
        Card(R.drawable.ic_remote, "More than one TV",
            "Tap the name at the top to switch between saved TVs or add another. Long-press a TV there to rename or remove it."),
    )
}

fun Activity.showTourSheet(onDone: (() -> Unit)? = null) {
    val theme = ThemeManager.getActiveTheme(this)
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val cards = Tour.cards
    var index = 0

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
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(18)
        }
    })
    val icon = ImageView(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(theme.circleBtnBg) }
        scaleType = ImageView.ScaleType.CENTER
        imageTintList = android.content.res.ColorStateList.valueOf(theme.circleBtnIconTint)
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { bottomMargin = dp(14) }
    }
    content.addView(icon)
    val title = TextView(this).apply {
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(theme.primaryText)
    }
    content.addView(title)
    val body = TextView(this).apply {
        textSize = 13f
        setTextColor(theme.secondaryText)
        setPadding(0, dp(8), 0, dp(18))
        // Fixed height keeps the button from jumping as card text length changes
        minHeight = dp(76)
    }
    content.addView(body)

    val dots = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(16) }
    }
    val dotViews = cards.map {
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginStart = dp(4); marginEnd = dp(4) }
            dots.addView(this)
        }
    }
    content.addView(dots)

    val button = TextView(this).apply {
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(theme.btnAccentText)
        background = GradientDrawable().apply { cornerRadius = 11 * d; setColor(theme.btnAccentBg) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46))
        isClickable = true; isFocusable = true
    }
    content.addView(button)

    fun render() {
        val card = cards[index]
        icon.setImageResource(card.icon)
        // Colour-dot glyphs carry their own colours; tinting would flatten them
        icon.imageTintList = if (card.icon == R.drawable.ic_colors) null
            else android.content.res.ColorStateList.valueOf(theme.circleBtnIconTint)
        title.text = card.title
        body.text = card.body
        dotViews.forEachIndexed { i, v ->
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (i == index) theme.btnAccentBg else theme.divider)
            }
        }
        button.text = if (index == cards.lastIndex) "Done" else "Next"
    }
    button.setOnClickListener {
        if (index < cards.lastIndex) { index++; render() }
        else { dialog.dismiss(); onDone?.invoke() }
    }
    render()

    dialog.setContentView(content)
    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.BOTTOM)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(0.55f)
        attributes = attributes.also { it.windowAnimations = android.R.style.Animation_InputMethod }
    }
    dialog.setOnCancelListener { onDone?.invoke() }
    dialog.show()
}
