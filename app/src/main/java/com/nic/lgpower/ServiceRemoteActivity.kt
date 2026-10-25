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
import java.util.concurrent.Executors

/**
 * Replica of LG's factory service remote (105-201M / MKJ39170828).
 * The IR keys go out over the blaster, so the phone must point at the TV.
 * IN-START and EZ-ADJUST can also be opened over Wi-Fi through the launcher,
 * which is the only way in on a phone without a blaster; there the digit and
 * nav tiles ride the pointer socket instead. POWER-ONLY and IN-STOP stay IR-only
 * (a reset or a locked TV is not something to fire from a network call).
 *
 * Codes verified against a capture of the real service remote: same NEC
 * address (0x04) as all other LG remote traffic, service entries are just
 * high command bytes (IN-START 0xFB, EZ-ADJUST 0xFF, POWER-ONLY 0xFE,
 * IN-STOP 0xFA).
 */
class ServiceRemoteActivity : AppCompatActivity() {

    private var irManager: ConsumerIrManager? = null
    private val hasIr get() = irManager?.hasIrEmitter() == true
    private val client by lazy { WebOsClient(this) }

    // One thread so digits typed in a row reach the TV in the order they were tapped
    private val wifiExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "service-remote-wifi").apply { isDaemon = true }
    }

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

    private fun toastLong(text: String) {
        runOnUiThread { if (!isFinishing) Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    }

    private fun sendKey(view: View, irCmd: Int, tvKey: String?) {
        if (hasIr || tvKey == null) { sendIr(view, irCmd); return }
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        wifiExecutor.execute {
            when (val r = client.pressKey(tvKey)) {
                is WebOsClient.Result.NeedsPairing -> toastLong(PAIRING_TEXT)
                is WebOsClient.Result.Error -> toastLong(r.message)
                else -> {}
            }
        }
    }

    private fun openOverWifi(view: View, menu: WebOsClient.ServiceMenu) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        wifiExecutor.execute {
            val problem = when (val r = client.launchServiceMenu(menu)) {
                is WebOsClient.Result.NeedsPairing -> PAIRING_TEXT
                is WebOsClient.Result.Error ->
                    if (UNREACHABLE.any { r.message.startsWith(it, ignoreCase = true) }) UNREACHABLE_TEXT
                    else refusedText()
                is WebOsClient.Result.Success -> if (menuIsUp()) null else refusedText()
            }
            if (problem != null) toastLong(problem)
        }
    }

    private fun closeOverWifi(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        wifiExecutor.execute {
            when (val r = client.closeServiceMenu()) {
                is WebOsClient.Result.NeedsPairing -> toastLong(PAIRING_TEXT)
                is WebOsClient.Result.Error -> toastLong(r.message)
                else -> {}
            }
        }
    }

    // A TV that accepts the launch but never shows the menu still answers success, so the
    // foreground app is the check. A reply that can't be read counts as open: a false
    // "didn't open" while the menu is on screen is worse than missing a silent ignore.
    private fun menuIsUp(): Boolean {
        repeat(4) {
            Thread.sleep(600)
            val id = client.foregroundAppId() ?: return true
            if (id == WebOsClient.FACTORY_APP_ID) return true
        }
        return false
    }

    private fun refusedText() =
        "Your TV didn't open the service menu over Wi-Fi." + if (hasIr) " Try IR." else ""

    override fun onDestroy() {
        wifiExecutor.shutdown()
        super.onDestroy()
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

        fun tile(
            labelText: String, cmd: Int, service: Boolean = false, key: String? = null,
            onTap: ((View) -> Unit)? = null
        ) =
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
                setOnClickListener { onTap?.invoke(it) ?: sendKey(it, cmd, key) }
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
            text = if (hasIr) "Wi-Fi launch · NEC 0x04 over IR, aim at the TV" else "Over Wi-Fi · the TV must be on"
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

        label("OVER WI-FI")
        row(
            tile("IN-START", 0, service = true) { openOverWifi(it, WebOsClient.ServiceMenu.IN_START) },
            tile("EZ-ADJUST", 0, service = true) { openOverWifi(it, WebOsClient.ServiceMenu.EZ_ADJUST) },
            tile("CLOSE MENU", 0) { closeOverWifi(it) }
        )

        if (hasIr) {
            label("FACTORY ENTRY · IR")
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
        }

        label("PASSWORD · 0413 / 0000")
        row(tile("1", 0x11, key = "1"), tile("2", 0x12, key = "2"), tile("3", 0x13, key = "3"))
        row(tile("4", 0x14, key = "4"), tile("5", 0x15, key = "5"), tile("6", 0x16, key = "6"))
        row(tile("7", 0x17, key = "7"), tile("8", 0x18, key = "8"), tile("9", 0x19, key = "9"))
        row(spacer(), tile("0", 0x10, key = "0"), spacer())

        label("NAV")
        row(tile("BACK", 0x28, key = "BACK"), tile("▲", 0x40, key = "UP"), tile("EXIT", 0x5B, key = "EXIT"))
        row(tile("◀", 0x07, key = "LEFT"), tile("OK", 0x44, key = "ENTER"), tile("▶", 0x06, key = "RIGHT"))
        row(if (hasIr) tile("PWR", 0x08) else spacer(), tile("▼", 0x41, key = "DOWN"), spacer())

        root.addView(TextView(this).apply {
            text = if (hasIr) "KEYS OVER IR · AIM THE PHONE AT THE TV" else "KEYS OVER WI-FI · THE TV MUST BE ON"
            textSize = 9f
            letterSpacing = 0.1f
            typeface = mono
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

        if (!hasIr) {
            warningSheet(
                chipText = "FACTORY CONTROLS",
                title = "This can wreck your TV",
                body = "Service menu settings can break your TV. Only change what you know.\n\n" +
                       "Wrong EZ-Adjust values (panel, white balance) can permanently ruin the " +
                       "picture, and wrong IN-START values can leave the TV misconfigured. " +
                       "Note every value down before changing it. If you're just curious: " +
                       "look, don't touch.",
                buttonText = "I understand the risks",
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

    private companion object {
        const val PAIRING_TEXT = "Accept pairing on your TV, then try again"
        const val UNREACHABLE_TEXT = "Can't reach the TV. Check it is on and on this Wi-Fi."

        // What WebOsClient says about a TV it never spoke to, as opposed to one that said no
        val UNREACHABLE = listOf(
            "No TV IP", "Timeout", "Can't connect", "Not connected",
            "Connection", "Session closed", "Lost the connection"
        )
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
    ) = showWarningSheet(chipText, title, body, buttonText, cancelClosesScreen, onAccept)
}
