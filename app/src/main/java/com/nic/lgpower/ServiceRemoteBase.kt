package com.nic.lgpower

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Look shared by the two service pages: header, hazard stripe, tiles, key grid
 * and the risk sheet. The Wi-Fi and IR pages differ only in what the tiles send.
 */
abstract class ServiceRemoteBase : AppCompatActivity() {

    // Amber marks service-grade keys in every theme, like red marks danger
    protected val amber = 0xFFE8A33D.toInt()

    protected abstract val subtitleText: String
    protected abstract val footerText: String

    protected abstract fun build(page: Page)

    /** Shown on entry; dismissing it without accepting closes the page. */
    protected abstract fun showEntryWarning()

    /** One entry per key tile: ir is the NEC command byte, tv the pointer-socket key name. */
    enum class KeyTile(val ir: Int, val tv: String?) {
        D0(0x10, "0"), D1(0x11, "1"), D2(0x12, "2"), D3(0x13, "3"), D4(0x14, "4"),
        D5(0x15, "5"), D6(0x16, "6"), D7(0x17, "7"), D8(0x18, "8"), D9(0x19, "9"),
        BACK(0x28, "BACK"), UP(0x40, "UP"), EXIT(0x5B, "EXIT"),
        LEFT(0x07, "LEFT"), OK(0x44, "ENTER"), RIGHT(0x06, "RIGHT"),
        DOWN(0x41, "DOWN"), PWR(0x08, null)
    }

    inner class Page(private val root: LinearLayout) {
        private val theme = ThemeManager.getActiveTheme(this@ServiceRemoteBase)
        private val d = resources.displayMetrics.density
        private fun dp(v: Int) = (v * d).toInt()
        private val mono = Typeface.MONOSPACE
        private val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        private val tileBg = ColorUtil.mix(theme.windowBg, theme.surfaceBg, 0.55f)
        private val tileBorder = ColorUtil.mix(theme.surfaceBg, theme.primaryText, 0.12f)
        private val amberBorder = ColorUtil.withAlpha(amber, 0x55)
        private val amberTileBg = ColorUtil.mix(theme.windowBg, amber, 0.07f)

        fun label(text: String) = root.addView(TextView(this@ServiceRemoteBase).apply {
            this.text = text
            textSize = 10.5f
            letterSpacing = 0.14f
            typeface = mono
            setTextColor(theme.sectionLabel)
            setPadding(dp(2), dp(18), dp(2), dp(7))
        })

        fun tile(labelText: String, service: Boolean = false, onTap: (View) -> Unit) =
            TextView(this@ServiceRemoteBase).apply {
                text = labelText
                textSize = 13f
                letterSpacing = 0.05f
                typeface = if (service) monoBold else mono
                gravity = Gravity.CENTER
                setTextColor(if (service) amber else theme.primaryText)
                background = GradientDrawable().apply {
                    cornerRadius = 6 * d
                    setColor(if (service) amberTileBg else tileBg)
                    setStroke(dp(1), if (service) amberBorder else tileBorder)
                }
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
                setOnClickListener(onTap)
            }

        fun spacer() = View(this@ServiceRemoteBase).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
        }

        fun row(vararg tiles: View) = root.addView(LinearLayout(this@ServiceRemoteBase).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            tiles.forEachIndexed { i, t ->
                if (i > 0) (t.layoutParams as LinearLayout.LayoutParams).marginStart = dp(6)
                addView(t)
            }
        })

        /** Digits and nav are the same grid on both pages; only the send path differs. */
        fun keyGrid(withPower: Boolean, send: (View, KeyTile) -> Unit) {
            fun k(text: String, key: KeyTile) = tile(text) { send(it, key) }
            label("PASSWORD · 0413 / 0000")
            row(k("1", KeyTile.D1), k("2", KeyTile.D2), k("3", KeyTile.D3))
            row(k("4", KeyTile.D4), k("5", KeyTile.D5), k("6", KeyTile.D6))
            row(k("7", KeyTile.D7), k("8", KeyTile.D8), k("9", KeyTile.D9))
            row(spacer(), k("0", KeyTile.D0), spacer())

            label("NAV")
            row(k("BACK", KeyTile.BACK), k("▲", KeyTile.UP), k("EXIT", KeyTile.EXIT))
            row(k("◀︎", KeyTile.LEFT), k("OK", KeyTile.OK), k("▶︎", KeyTile.RIGHT))
            row(if (withPower) k("PWR", KeyTile.PWR) else spacer(), k("▼", KeyTile.DOWN), spacer())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val theme = ThemeManager.getActiveTheme(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.windowBg)
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "SERVICE MODE"
            textSize = 18f
            letterSpacing = 0.04f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(theme.primaryText)
        })
        root.addView(TextView(this).apply {
            text = subtitleText
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextColor(theme.sectionLabel)
            setPadding(0, dp(3), 0, dp(9))
        })

        root.addView(object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = amber; strokeWidth = 6 * d
            }
            override fun onDraw(canvas: Canvas) {
                // Lines overshoot the band and clip to it, so the stripes stay diagonal
                // instead of degenerating into ticks at this height
                val over = 60f
                var x = -over
                while (x < width + over) {
                    canvas.drawLine(x, height + over, x + height + 2 * over, -over, paint)
                    x += 12 * d
                }
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(5))
        })

        build(Page(root))

        root.addView(TextView(this).apply {
            text = footerText
            textSize = 9f
            letterSpacing = 0.1f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextColor(ColorUtil.withAlpha(theme.sectionLabel, 0x99))
            setPadding(0, dp(14), 0, 0)
        })

        val scroller = ScrollView(this).apply {
            setBackgroundColor(theme.windowBg)
            isVerticalScrollBarEnabled = false
            addView(root)
        }
        setContentView(scroller)
        SystemBars.applyTo(this, scroller, theme.windowBg)

        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = theme.windowBg
            window.navigationBarColor = theme.windowBg
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val flags = window.decorView.systemUiVisibility
                window.decorView.systemUiVisibility = if (theme.statusBarLightIcons)
                    flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                else
                    flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
        }

        showEntryWarning()
    }

    protected fun warningSheet(
        chipText: String,
        title: String,
        body: String,
        buttonText: String,
        cancelClosesScreen: Boolean,
        onAccept: (() -> Unit)? = null
    ) = showWarningSheet(chipText, title, body, buttonText, cancelClosesScreen, onAccept)

    protected fun haptic(view: View) = view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
}
