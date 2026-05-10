package com.nic.lgpower

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SetupActivity : AppCompatActivity() {

    private val client by lazy { WebOsClient(this) }
    private var stopPairing: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        applyTheme()
        startDiscovery()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPairing?.invoke()
    }

    // ── Discovery ─────────────────────────────────────────────────────────────

    private fun startDiscovery() {
        showScreen(Screen.SEARCHING)
        Thread {
            val found = TvDiscovery.discover(this)
            runOnUiThread { showTvList(found) }
        }.start()
    }

    private fun showTvList(ips: List<String>) {
        showScreen(Screen.TV_LIST)
        val theme = ThemeManager.getActiveTheme(this)
        val d = resources.displayMetrics.density

        val label = findViewById<TextView>(R.id.tv_list_label)
        label.setTextColor(theme.sectionLabel)

        val container = findViewById<LinearLayout>(R.id.tv_items_container)
        val noTvs = findViewById<View>(R.id.no_tvs_layout)

        if (ips.isEmpty()) {
            label.text = ""
            noTvs.visibility = View.VISIBLE
            applyButton(
                findViewById(R.id.btn_search_again), theme,
                accent = false
            )
            findViewById<View>(R.id.btn_search_again).setOnClickListener { startDiscovery() }
            return
        }

        label.text = if (ips.size == 1) "TV FOUND" else "TVS FOUND"
        noTvs.visibility = View.GONE

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(theme.surfaceBg)
                cornerRadius = 14 * d
            }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        ips.forEachIndexed { i, ip ->
            if (i > 0) {
                card.addView(View(this).apply {
                    setBackgroundColor(theme.divider)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).also { it.marginStart = (16 * d).toInt() }
                })
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true; isFocusable = true
                setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (60 * d).toInt()
                )
                setOnClickListener { selectTv(ip) }
            }
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@SetupActivity).apply {
                    text = "LG TV"
                    textSize = 16f
                    setTextColor(theme.primaryText)
                })
                addView(TextView(this@SetupActivity).apply {
                    text = ip
                    textSize = 13f
                    setTextColor(theme.secondaryText)
                })
            })
            card.addView(row)
        }

        container.removeAllViews()
        container.addView(card)
    }

    // ── Pairing ───────────────────────────────────────────────────────────────

    private fun selectTv(ip: String) {
        client.saveTvIp(ip)
        showPairingScreen(ip, connecting = true)
        stopPairing = client.watchForPairing(
            onPromptShown = {
                runOnUiThread { showPairingScreen(ip, connecting = false) }
            },
            onPaired = {
                // Grab MAC in background while success screen shows
                Thread {
                    val mac = client.getMacFromDevice()
                    if (mac != null) client.saveTvMac(mac)
                }.start()
                runOnUiThread { showSuccess(ip) }
            }
        )
    }

    private fun showPairingScreen(ip: String, connecting: Boolean) {
        showScreen(Screen.PAIRING)
        val theme = ThemeManager.getActiveTheme(this)
        val d = resources.displayMetrics.density

        val card = findViewById<LinearLayout>(R.id.pairing_card)
        card.background = GradientDrawable().apply {
            setColor(theme.surfaceBg)
            cornerRadius = 16 * d
        }

        findViewById<TextView>(R.id.pairing_ip).apply {
            text = ip
            setTextColor(theme.primaryText)
        }
        findViewById<TextView>(R.id.pairing_message).apply {
            text = if (connecting) "Connecting…"
                   else "Accept the pairing prompt\non your TV to continue"
            setTextColor(theme.secondaryText)
        }
    }

    private fun showSuccess(ip: String) {
        stopPairing = null
        val theme = ThemeManager.getActiveTheme(this)

        findViewById<TextView>(R.id.pairing_message).apply {
            text = "Connected!"
            setTextColor(theme.btnAccentBg)
        }
        findViewById<ProgressBar>(R.id.pairing_spinner).visibility = View.GONE

        window.decorView.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 1200)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private enum class Screen { SEARCHING, TV_LIST, PAIRING }

    private fun showScreen(s: Screen) {
        findViewById<View>(R.id.screen_searching).visibility = if (s == Screen.SEARCHING) View.VISIBLE else View.GONE
        findViewById<View>(R.id.screen_tv_list).visibility   = if (s == Screen.TV_LIST)   View.VISIBLE else View.GONE
        findViewById<View>(R.id.screen_pairing).visibility   = if (s == Screen.PAIRING)   View.VISIBLE else View.GONE
    }

    private fun applyButton(btn: View, theme: ThemeConfig, accent: Boolean) {
        val d = resources.displayMetrics.density
        (btn as? android.widget.Button)?.apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10 * d
                setColor(if (accent) theme.btnAccentBg else 0)
                if (!accent) setStroke((1f * d).toInt(), theme.btnGhostBorder)
            }
            setTextColor(if (accent) theme.btnAccentText else theme.secondaryText)
        }
    }

    @Suppress("DEPRECATION")
    private fun applyTheme() {
        val theme = ThemeManager.getActiveTheme(this)
        ThemeManager.applyToRoot(findViewById(R.id.setup_root), theme)
        window.statusBarColor = theme.windowBg
        window.navigationBarColor = theme.windowBg

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val flags = window.decorView.systemUiVisibility
            window.decorView.systemUiVisibility = if (theme.statusBarLightIcons)
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            else
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }

        findViewById<TextView>(R.id.setup_title).setTextColor(theme.primaryText)
        findViewById<TextView>(R.id.setup_subtitle).setTextColor(theme.secondaryText)
        findViewById<TextView>(R.id.search_label).setTextColor(theme.secondaryText)
        findViewById<TextView>(R.id.no_tvs_label).setTextColor(theme.secondaryText)
    }
}
