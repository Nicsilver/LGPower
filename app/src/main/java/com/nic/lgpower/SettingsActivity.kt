package com.nic.lgpower

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class SettingsActivity : AppCompatActivity() {

    private val prefs  by lazy { getSharedPreferences("webos", MODE_PRIVATE) }
    private val client by lazy { WebOsClient(this) }

    private lateinit var tvShortcutsSummary: TextView
    private lateinit var appsGrid:           RecyclerView
    private lateinit var adapter:            AppGridAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        SystemBars.applyTo(this, findViewById(R.id.settings_root), ThemeManager.getActiveTheme(this).windowBg)
        applyTheme()

        findViewById<View>(R.id.row_add_tv).setOnClickListener {
            startActivity(android.content.Intent(this, SetupActivity::class.java)
                .putExtra(SetupActivity.EXTRA_ADD_TV, true))
        }

        // Controls toggles
        val switchKeepScreenOn     = findViewById<Switch>(R.id.switch_keep_screen_on)
        val switchMediaOnMain      = findViewById<Switch>(R.id.switch_media_on_main)
        switchMediaOnMain.isChecked = prefs.getBoolean("media_on_main", false)
        switchMediaOnMain.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("media_on_main", v).apply() }
        switchKeepScreenOn.isChecked     = prefs.getBoolean("keep_screen_on", false)
        val tvRightPill = findViewById<TextView>(R.id.tv_right_pill)
        tvRightPill.text = RightPill.get(prefs).label
        findViewById<View>(R.id.row_right_pill).setOnClickListener {
            val current = RightPill.get(prefs)
            showPickerSheet("Right side", RightPill.entries.map { Triple(it.key, it.label, it == current) }) { key ->
                val mode = RightPill.entries.first { it.key == key }
                RightPill.set(prefs, mode)
                tvRightPill.text = mode.label
            }
        }
        switchKeepScreenOn.setOnCheckedChangeListener { sw, v ->
            if (!v) { prefs.edit().putBoolean("keep_screen_on", false).apply(); return@setOnCheckedChangeListener }
            if (prefs.getBoolean("keep_screen_on", false)) return@setOnCheckedChangeListener
            // A static remote left on for hours is exactly what burns in an OLED phone
            showWarningSheet(
                chipText = "SCREEN STAYS ON",
                title = "Careful with OLED screens",
                body = "The remote will keep the screen awake for as long as it's open, even if you put the phone down. " +
                    "On an OLED phone that can burn the remote layout into the panel over time, and it drains the battery. " +
                    "Meant for a spare phone used as a dedicated remote.",
                buttonText = "Keep screen on",
                cancelClosesScreen = false,
                onAccept = { prefs.edit().putBoolean("keep_screen_on", true).apply() },
                onCancel = { sw.isChecked = false }
            )
        }
        tvShortcutsSummary  = findViewById(R.id.tv_shortcuts_summary)
        appsGrid            = findViewById(R.id.apps_grid)

        // Auto-save on every selection/reorder change
        adapter = AppGridAdapter(this, client) { chosen ->
            client.saveShortcuts(chosen)
            updateSummary(chosen)
            Thread {
                chosen.forEach { app ->
                    if (!app.iconUrl.isNullOrEmpty() && client.loadCachedIcon(app.id) == null)
                        client.cacheIcon(app.id, app.iconUrl)
                }
            }.start()
        }

        appsGrid.adapter = adapter
        appsGrid.layoutManager = GridLayoutManager(this, 4)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                val pos = vh.bindingAdapterPosition
                val drag = if (pos >= 0 && adapter.isSelected(pos))
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                else 0
                return makeMovementFlags(drag, 0)
            }
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val f = from.bindingAdapterPosition
                val t = to.bindingAdapterPosition
                if (f < 0 || t < 0 || t >= adapter.selectedCount) return false
                adapter.moveItem(f, t)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
        }).attachToRecyclerView(appsGrid)

        // Pre-populate grid with current shortcuts so user can reorder without loading all apps
        val current = client.loadShortcuts()
        if (current.isNotEmpty()) {
            adapter.setApps(current, current)
            appsGrid.visibility = View.VISIBLE
            updateSummary(current)
        } else {
            tvShortcutsSummary.text = "No shortcuts configured — load apps from TV to pick some"
        }

        findViewById<View>(R.id.row_service_remote).setOnClickListener {
            startActivity(android.content.Intent(this, ServiceRemoteActivity::class.java))
        }

        findViewById<TextView>(R.id.tv_app_version).text = BuildConfig.VERSION_NAME
        // The tour runs on the remote itself, so hand back to it
        findViewById<View>(R.id.row_tour).setOnClickListener {
            prefs.edit().putBoolean("tour_pending", true).apply()
            finish()
        }
        findViewById<View>(R.id.row_release_notes).setOnClickListener {
            showReleaseNotesDialog("Release notes", ReleaseNotes.all, "Close", markLatest = true)
        }

        // Theme picker
        refreshThemeLabel()
        findViewById<View>(R.id.row_theme).setOnClickListener { openThemePicker() }
        findViewById<View>(R.id.row_create_theme).setOnClickListener {
            openThemeEditor(baseId = ThemeManager.getActiveThemeId(this), editId = null)
        }

        // Load full app list from TV
        val loadAppsSpinner = findViewById<ProgressBar>(R.id.load_apps_spinner)
        findViewById<Button>(R.id.btn_load_apps).setOnClickListener { loadBtn ->
            loadAppsSpinner.visibility = View.VISIBLE
            loadBtn.isEnabled = false
            Thread {
                val (apps, error) = client.listApps()
                runOnUiThread {
                    loadAppsSpinner.visibility = View.GONE
                    loadBtn.isEnabled = true
                    if (error != null) {
                        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    if (apps.isEmpty()) {
                        Toast.makeText(this, "No apps returned by TV", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    adapter.setApps(apps, adapter.selectedApps.ifEmpty { client.loadShortcuts() })
                    appsGrid.visibility = View.VISIBLE
                    // Download missing icons in parallel
                    val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
                    apps.forEach { app ->
                        if (app.iconUrl.isNullOrEmpty()) return@forEach
                        if (client.loadCachedIcon(app.id) != null) return@forEach
                        pool.submit {
                            client.cacheIcon(app.id, app.iconUrl)
                            runOnUiThread { adapter.refreshItem(app.id) }
                        }
                    }
                    pool.shutdown()
                }
            }.start()
        }
    }

    private var pendingThemeRefresh = false

    private fun refreshThemeLabel() {
        val themes = ThemeManager.listThemes(this)
        val activeId = ThemeManager.getActiveThemeId(this)
        findViewById<TextView>(R.id.tv_current_theme).text =
            themes.firstOrNull { it.id == activeId }?.name ?: "Dark"
    }

    private fun openThemePicker() {
        val themes = ThemeManager.listThemes(this)
        val activeId = ThemeManager.getActiveThemeId(this)
        showPickerSheet(
            "Theme",
            themes.map { Triple(it.id, it.name, it.id == activeId) },
            onLongPress = { id -> showThemeActions(id) }
        ) { id ->
            ThemeManager.setActiveThemeId(this, id)
            recreate()
        }
    }

    /** Long-press a theme to duplicate it, or edit/delete it if it's custom. */
    private fun showThemeActions(id: String) {
        val theme = ThemeManager.listThemes(this).firstOrNull { it.id == id } ?: return
        val actions = buildList {
            add(Triple("duplicate", "Duplicate", false))
            if (theme.editable) {
                add(Triple("edit", "Edit", false))
                add(Triple("delete", "Delete", false))
            }
        }
        showPickerSheet(theme.name, actions) { action ->
            when (action) {
                "duplicate" -> openThemeEditor(baseId = id, editId = null)
                "edit"      -> openThemeEditor(baseId = id, editId = id)
                "delete"    -> {
                    ThemeManager.deleteCustom(this, id)
                    recreate()
                }
            }
        }
    }

    private fun openThemeEditor(baseId: String, editId: String?) {
        pendingThemeRefresh = true
        startActivity(android.content.Intent(this, ThemeEditorActivity::class.java).apply {
            putExtra(ThemeEditorActivity.EXTRA_BASE_ID, baseId)
            if (editId != null) putExtra(ThemeEditorActivity.EXTRA_EDIT_ID, editId)
        })
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the editor: a theme may have been created, edited,
        // or made active — rebuild this screen so it reflects the change.
        if (pendingThemeRefresh) {
            pendingThemeRefresh = false
            recreate()
            return
        }
        refreshTvRows()
    }

    @Suppress("DEPRECATION")
    private fun applyTheme() {
        val theme = ThemeManager.getActiveTheme(this)
        ThemeManager.applyToRoot(findViewById(R.id.settings_root), theme)
        window.statusBarColor = theme.windowBg
        window.navigationBarColor = theme.windowBg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val flags = window.decorView.systemUiVisibility
            window.decorView.systemUiVisibility = if (theme.statusBarLightIcons)
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            else
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
        val trackCsl = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(theme.switchTrackOn, theme.switchTrackOff)
        )
        val thumbCsl = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(theme.switchThumbOn, theme.switchThumbOff)
        )
        listOf(R.id.switch_keep_screen_on, R.id.switch_media_on_main)
            .forEach { id ->
                val sw = findViewById<Switch>(id) ?: return@forEach
                sw.trackTintList = trackCsl
                sw.thumbTintList = thumbCsl
            }
        val dp = resources.displayMetrics.density
        val ghostBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * dp
            setColor(0)
            setStroke((1f * dp).toInt(), theme.btnGhostBorder)
        }
        val accentBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * dp
            setColor(theme.btnAccentBg)
        }
        val chevronTint = if (theme.statusBarLightIcons) 0xFFAAAAAA.toInt() else 0xFF555555.toInt()
        findViewById<Button>(R.id.btn_load_apps)
            ?.compoundDrawableTintList = ColorStateList.valueOf(chevronTint)
    }

    private fun refreshTvRows() {
        val theme = ThemeManager.getActiveTheme(this)
        val d = resources.displayMetrics.density
        val container = findViewById<android.widget.LinearLayout>(R.id.tvs_container)
        container.removeAllViews()
        val activeId = TvStore.activeId(prefs)
        TvStore.list(prefs).forEach { tv ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((16 * d).toInt(), 0, (12 * d).toInt(), 0)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (56 * d).toInt())
                isClickable = true; isFocusable = true
                val out = android.util.TypedValue()
                theme.let { getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true) }
                setBackgroundResource(out.resourceId)
                setOnClickListener {
                    startActivity(android.content.Intent(this@SettingsActivity, TvDetailActivity::class.java)
                        .putExtra(TvDetailActivity.EXTRA_TV_ID, tv.id))
                }
            }
            val text = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@SettingsActivity).apply {
                    this.text = tv.name; textSize = 15f; setTextColor(theme.primaryText)
                })
                addView(TextView(this@SettingsActivity).apply {
                    this.text = tv.ip.ifBlank { "No address" }; textSize = 12f; setTextColor(theme.secondaryText)
                })
            }
            row.addView(text)
            if (tv.id == activeId) row.addView(TextView(this).apply {
                this.text = "In use"; textSize = 12f; setTextColor(theme.btnAccentBg)
                setPadding(0, 0, (8 * d).toInt(), 0)
            })
            row.addView(android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = ColorStateList.valueOf(if (theme.statusBarLightIcons) 0xFFAAAAAA.toInt() else 0xFF555555.toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams((16 * d).toInt(), (16 * d).toInt())
            })
            container.addView(row)
            container.addView(View(this).apply {
                setBackgroundColor(theme.divider)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1).also { it.marginStart = (16 * d).toInt() }
            })
        }
    }

    private fun updateSummary(chosen: List<WebOsClient.TvApp>) {
        tvShortcutsSummary.text = "Tap to select · Long-press to reorder · ${chosen.size}/8 selected"
    }
}
