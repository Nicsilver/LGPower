package com.nic.lgpower

import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.ConsumerIrManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * IR-only replica of LG's factory service remote (105-201M / MKJ39170828).
 * Service menus don't reliably react to network (SSAP) input, so every button
 * here goes out over the IR blaster — the phone must point at the TV.
 *
 * Codes verified against a capture of the real service remote: same NEC
 * address (0x04) as all other LG remote traffic, service entries are just
 * high command bytes (IN-START 0xFB, EZ-ADJUST 0xFF, POWER-ONLY 0xFE,
 * IN-STOP 0xFA).
 */
class ServiceRemoteActivity : AppCompatActivity() {

    private var irManager: ConsumerIrManager? = null

    // Amber marks service-grade keys in every theme, like red marks danger
    private val amber = 0xFFE8A33D.toInt()

    // NEC address 0x04 encoded (0x20DF prefix); CC/DD from the command byte
    private fun lgNecPattern(cmd: Int): IntArray {
        val rev = { b: Int -> Integer.reverse(b and 0xFF) ushr 24 }
        val code = (0x20DFL shl 16) or (rev(cmd).toLong() shl 8) or rev(cmd.inv()).toLong()
        return LGPowerWidget.buildNecPattern(code)
    }

    private fun sendIr(view: View, cmd: Int) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val ir = irManager
        if (ir == null || !ir.hasIrEmitter()) {
            Toast.makeText(this, "No IR blaster on this phone", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { ir.transmit(38000, lgNecPattern(cmd)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        irManager = getSystemService(CONSUMER_IR_SERVICE) as? ConsumerIrManager
        val theme = ThemeManager.getActiveTheme(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val mono = Typeface.MONOSPACE
        val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val tileBg = ColorUtil.mix(theme.windowBg, theme.surfaceBg, 0.55f)
        val tileBorder = ColorUtil.mix(theme.surfaceBg, theme.primaryText, 0.12f)
        val amberBorder = ColorUtil.withAlpha(amber, 0x55)
        val amberTileBg = ColorUtil.mix(theme.windowBg, amber, 0.07f)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.windowBg)
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }

        fun label(text: String) = root.addView(TextView(this).apply {
            this.text = text
            textSize = 10.5f
            letterSpacing = 0.14f
            typeface = mono
            setTextColor(theme.sectionLabel)
            setPadding(dp(2), dp(18), dp(2), dp(7))
        })

        fun tile(labelText: String, cmd: Int, service: Boolean = false, onTap: ((View) -> Unit)? = null) =
            TextView(this).apply {
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
                setOnClickListener { onTap?.invoke(it) ?: sendIr(it, cmd) }
            }

        fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f) }

        fun row(vararg tiles: View) = root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            tiles.forEachIndexed { i, t ->
                if (i > 0) (t.layoutParams as LinearLayout.LayoutParams).marginStart = dp(6)
                addView(t)
            }
        })

        root.addView(TextView(this).apply {
            text = "SERVICE MODE"
            textSize = 18f
            letterSpacing = 0.04f
            typeface = monoBold
            setTextColor(theme.primaryText)
        })
        root.addView(TextView(this).apply {
            text = "NEC 0x04 over IR · aim at the TV"
            textSize = 10.5f
            typeface = mono
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

        label("FACTORY ENTRY")
        row(tile("IN-START", 0xFB, service = true), tile("EZ-ADJUST", 0xFF, service = true))
        row(
            tile("POWER-ONLY", 0xFE, service = true) { v ->
                confirmSheet(v, 0xFE, "POWER-ONLY",
                    "Locks the TV into power-only mode: it stops responding to everything " +
                    "except the power button, and the only way back out is through the " +
                    "service menu. Don't send this unless you know why you need it.")
            },
            tile("IN-STOP", 0xFA, service = true) { v ->
                confirmSheet(v, 0xFA, "IN-STOP",
                    "FACTORY RESETS THE TV. Instantly, with no prompt on the TV.\n\n" +
                    "This is the production line's 'prepare for shipment' command. It wipes " +
                    "all settings, accounts, apps and pairings and restarts the TV into " +
                    "out-of-box setup. Panel calibration survives, nothing else does.")
            }
        )

        label("PASSWORD · 0413 / 0000")
        row(tile("1", 0x11), tile("2", 0x12), tile("3", 0x13))
        row(tile("4", 0x14), tile("5", 0x15), tile("6", 0x16))
        row(tile("7", 0x17), tile("8", 0x18), tile("9", 0x19))
        row(spacer(), tile("0", 0x10), spacer())

        label("NAV")
        row(tile("BACK", 0x28), tile("▲", 0x40), tile("EXIT", 0x5B))
        row(tile("◀", 0x07), tile("OK", 0x44), tile("▶", 0x06))
        row(tile("PWR", 0x08), tile("▼", 0x41), spacer())

        root.addView(TextView(this).apply {
            text = "MKJ39170828 REPLICA · IR ONLY"
            textSize = 9f
            letterSpacing = 0.1f
            typeface = mono
            gravity = Gravity.CENTER
            setTextColor(ColorUtil.withAlpha(theme.sectionLabel, 0x99))
            setPadding(0, dp(14), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(theme.windowBg)
            isVerticalScrollBarEnabled = false
            addView(root)
        })

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

        if (irManager?.hasIrEmitter() != true) {
            warningSheet(
                chipText = "NO IR BLASTER",
                title = "This phone can't transmit",
                body = "The service remote sends raw IR and this phone has no IR emitter. " +
                       "It needs a phone with a built-in IR blaster.",
                buttonText = "Got it",
                cancelClosesScreen = true
            )
        } else {
            warningSheet(
                chipText = "FACTORY CONTROLS",
                title = "This can wreck your TV",
                body = "The TV obeys these instantly and never asks first. Wrong EZ-Adjust " +
                       "values (panel, white balance) can permanently ruin the picture, " +
                       "wrong IN-START values can leave the TV misconfigured, and IN-STOP " +
                       "factory resets it on the spot.\n\n" +
                       "Note every value down before changing it. If you're just curious: " +
                       "look, don't touch.",
                buttonText = "I understand the risks",
                cancelClosesScreen = true
            )
        }
    }

    private fun confirmSheet(origin: View, cmd: Int, title: String, body: String) {
        origin.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        warningSheet(
            chipText = "CONFIRM",
            title = title,
            body = body,
            buttonText = "Send $title",
            cancelClosesScreen = false
        ) { sendIr(origin, cmd) }
    }

    /** Bottom sheet in the app's picker style: dimmed screen, 20dp top radius, one accent action. */
    private fun warningSheet(
        chipText: String,
        title: String,
        body: String,
        buttonText: String,
        cancelClosesScreen: Boolean,
        onAccept: (() -> Unit)? = null
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
        if (cancelClosesScreen) dialog.setOnCancelListener { finish() }
        dialog.show()
    }
}
