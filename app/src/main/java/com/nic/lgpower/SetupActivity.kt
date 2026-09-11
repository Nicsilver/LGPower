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
    private val prefs by lazy { getSharedPreferences("webos", MODE_PRIVATE) }
    // Adding a second TV from the remote, as opposed to first-run setup
    private val addMode by lazy { intent.getBooleanExtra(EXTRA_ADD_TV, false) }
    private var paired = false
    // Fingerprints from discovery, and the one for the TV being paired (fetched for manual entries)
    private val udns = HashMap<String, String>()
    @Volatile private var pairedUdn: String? = null
    private var stopPairing: (() -> Unit)? = null
    private val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pairingTimeout: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        SystemBars.applyTo(this, findViewById(R.id.setup_root), ThemeManager.getActiveTheme(this).windowBg)
        applyTheme()
        // Settles the saved-TV list before pairing writes the live prefs, so a fresh
        // install is not mistaken for an upgrade with an unnamed TV
        TvStore.list(prefs)
        if (addMode) {
            TvStore.beginAdd(prefs)
            findViewById<TextView>(R.id.setup_subtitle).text = "Add another TV"
        }
        startDiscovery()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPairing?.invoke()
        pairingTimeout?.let { timeoutHandler.removeCallbacks(it) }
        if (addMode && !paired) TvStore.cancelAdd(prefs)
    }



    // ── Discovery ─────────────────────────────────────────────────────────────

    private fun startDiscovery() {
        showScreen(Screen.SEARCHING)
        Thread {
            val found = TvDiscovery.discoverDetailed(this)
            found.forEach { f -> f.udn?.let { udns[f.ip] = it } }
            runOnUiThread { showTvList(found.map { it.ip }) }
        }.start()
    }

    private fun showTvList(found: List<String>) {
        showScreen(Screen.TV_LIST)
        val theme = ThemeManager.getActiveTheme(this)
        val d = resources.displayMetrics.density

        val label = findViewById<TextView>(R.id.tv_list_label)
        label.setTextColor(theme.sectionLabel)

        val container = findViewById<LinearLayout>(R.id.tv_items_container)
        val noTvs = findViewById<View>(R.id.no_tvs_layout)

        setupManualEntry(theme, d)

        val ips = found
        if (ips.isEmpty()) {
            label.text = ""
            noTvs.visibility = View.VISIBLE
            container.removeAllViews()
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
            // A TV already in the list stays visible but greyed, so a second TV that
            // happens to share an address on another network can still be told apart
            val already = TvStore.match(prefs, ip, udns[ip])
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = already == null; isFocusable = already == null
                alpha = if (already == null) 1f else 0.45f
                setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (60 * d).toInt()
                )
                if (already == null) setOnClickListener { selectTv(ip) }
            }
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@SetupActivity).apply {
                    text = already?.name ?: "LG TV"
                    textSize = 16f
                    setTextColor(theme.primaryText)
                })
                addView(TextView(this@SetupActivity).apply {
                    text = if (already == null) ip else "$ip · already added"
                    textSize = 13f
                    setTextColor(theme.secondaryText)
                })
            })
            card.addView(row)
        }

        container.removeAllViews()
        container.addView(card)
    }

    private fun setupManualEntry(theme: ThemeConfig, d: Float) {
        val toggle = findViewById<android.widget.Button>(R.id.btn_manual_ip)
        val card = findViewById<LinearLayout>(R.id.manual_ip_card)
        val field = findViewById<android.widget.EditText>(R.id.edit_manual_ip)
        val connect = findViewById<android.widget.Button>(R.id.btn_manual_connect)

        applyButton(toggle, theme, accent = false)
        applyButton(connect, theme, accent = true)
        card.background = GradientDrawable().apply { setColor(theme.surfaceBg); cornerRadius = 14 * d }
        findViewById<TextView>(R.id.manual_ip_label).setTextColor(theme.sectionLabel)
        findViewById<TextView>(R.id.manual_ip_hint).setTextColor(theme.secondaryText)
        field.setTextColor(theme.primaryText)
        field.setHintTextColor(ColorUtil.withAlpha(theme.secondaryText, 0x78))
        field.background = GradientDrawable().apply {
            setColor(theme.windowBg); cornerRadius = 10 * d
            setStroke((1f * d).toInt(), theme.btnGhostBorder)
        }

        card.visibility = View.GONE
        toggle.visibility = View.VISIBLE
        toggle.setOnClickListener {
            toggle.visibility = View.GONE
            card.visibility = View.VISIBLE
            field.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        val go = {
            val ip = field.text.toString().trim()
            if (IPV4.matches(ip)) {
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(field.windowToken, 0)
                selectTv(ip)
            } else {
                field.error = "Enter a valid IPv4 address"
            }
        }
        connect.setOnClickListener { go() }
        field.setOnEditorActionListener { _, _, _ -> go(); true }
    }

    // ── Pairing ───────────────────────────────────────────────────────────────

    private fun selectTv(ip: String) {
        showPairingScreen(ip, connecting = true)
        // If the TV never answers the registration (off, unreachable, or webOS
        // rejecting the handshake), fail visibly instead of spinning forever
        pairingTimeout?.let { timeoutHandler.removeCallbacks(it) }
        pairingTimeout = Runnable {
            stopPairing?.invoke()
            stopPairing = null
            showPairingFailed(ip)
        }.also { timeoutHandler.postDelayed(it, 30_000) }

        stopPairing = client.watchForPairing(
            ip = ip,
            onPromptShown = {
                // Prompt is on the TV screen — the user may take a while, stop the clock
                pairingTimeout?.let { timeoutHandler.removeCallbacks(it) }
                runOnUiThread { showPairingScreen(ip, connecting = false) }
            },
            onPaired = {
                pairingTimeout?.let { timeoutHandler.removeCallbacks(it) }
                client.saveTvIp(ip)
                pairedUdn = udns[ip]
                // Grab MAC (and the fingerprint, if discovery did not have it) while the name step shows
                Thread {
                    val mac = client.getMacFromDevice()
                    if (mac != null) client.saveTvMac(mac)
                    if (pairedUdn == null) pairedUdn = TvDiscovery.fingerprint(this, ip)
                }.start()
                runOnUiThread { showSuccess(ip) }
            }
        )
    }

    private fun showPairingFailed(ip: String) {
        val theme = ThemeManager.getActiveTheme(this)
        findViewById<TextView>(R.id.pairing_message).apply {
            text = "Can't reach the TV.\nMake sure it's on, restart it,\nthen try again."
            setTextColor(theme.secondaryText)
        }
        findViewById<ProgressBar>(R.id.pairing_spinner).visibility = View.GONE
        val retry  = findViewById<android.widget.Button>(R.id.btn_pairing_retry)
        val search = findViewById<android.widget.Button>(R.id.btn_pairing_search)
        applyButton(retry, theme, accent = true)
        applyButton(search, theme, accent = false)
        retry.visibility = View.VISIBLE
        search.visibility = View.VISIBLE
        retry.setOnClickListener { selectTv(ip) }
        search.setOnClickListener { startDiscovery() }
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
        findViewById<ProgressBar>(R.id.pairing_spinner).visibility = View.VISIBLE
        findViewById<View>(R.id.btn_pairing_retry).visibility = View.GONE
        findViewById<View>(R.id.btn_pairing_search).visibility = View.GONE
    }

    private fun showSuccess(ip: String) {
        stopPairing = null
        paired = true
        val theme = ThemeManager.getActiveTheme(this)
        val d = resources.displayMetrics.density

        findViewById<TextView>(R.id.pairing_message).apply {
            text = "Connected!"
            setTextColor(theme.btnAccentBg)
        }
        findViewById<ProgressBar>(R.id.pairing_spinner).visibility = View.GONE

        val group = findViewById<LinearLayout>(R.id.name_group)
        val field = findViewById<android.widget.EditText>(R.id.edit_tv_name)
        val done  = findViewById<android.widget.Button>(R.id.btn_name_done)
        findViewById<TextView>(R.id.name_label).setTextColor(theme.sectionLabel)
        field.setTextColor(theme.primaryText)
        field.setHintTextColor(ColorUtil.withAlpha(theme.secondaryText, 0x78))
        field.background = GradientDrawable().apply {
            setColor(theme.windowBg); cornerRadius = 10 * d
            setStroke((1f * d).toInt(), theme.btnGhostBorder)
        }
        field.setText(TvStore.nextDefaultName(prefs))
        field.selectAll()
        applyButton(done, theme, accent = true)
        group.visibility = View.VISIBLE

        var finishing = false
        val finishSetup = {
            if (!finishing) {
                finishing = true
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(field.windowToken, 0)
                TvStore.addFromLive(prefs, field.text.toString(), pairedUdn ?: "")
                if (!addMode) prefs.edit().putBoolean(MainActivity.PREF_TOUR_PENDING, true).apply()
                done.isEnabled = false
                done.text = "Setting up…"
                // The new TV's shortcuts come from its own app list, so the remote opens populated
                Thread {
                    val (apps, _) = client.listApps()
                    val picked = client.pickDefaultShortcuts(apps)
                    if (picked.isNotEmpty()) {
                        client.saveShortcuts(picked)
                        picked.forEach { app -> app.iconUrl?.let { client.cacheIcon(app.id, it) } }
                    }
                    runOnUiThread {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                }.start()
            }
        }
        done.setOnClickListener { finishSetup() }
        field.setOnEditorActionListener { _, _, _ -> finishSetup(); true }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private enum class Screen { SEARCHING, TV_LIST, PAIRING }

    companion object {
        const val EXTRA_ADD_TV = "add_tv"
        private val IPV4 = Regex("""^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$""")
    }

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
