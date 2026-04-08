package com.nic.lgpower

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("webos", MODE_PRIVATE) }
    private val client by lazy { WebOsClient(this) }

    private lateinit var tvShortcutsSummary: TextView
    private lateinit var appsContainer: LinearLayout
    private lateinit var btnApplyShortcuts: Button

    private var allApps: List<WebOsClient.TvApp> = emptyList()
    private val selectedIds = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val editIp = findViewById<EditText>(R.id.edit_tv_ip)
        val discoverSpinner = findViewById<ProgressBar>(R.id.discover_spinner)
        val currentIp = prefs.getString("tv_ip", WebOsClient.DEFAULT_TV_IP) ?: WebOsClient.DEFAULT_TV_IP
        editIp.setText(currentIp)
        editIp.setSelection(editIp.text.length)

        tvShortcutsSummary = findViewById(R.id.tv_shortcuts_summary)
        appsContainer = findViewById(R.id.apps_container)
        btnApplyShortcuts = findViewById(R.id.btn_apply_shortcuts)

        updateShortcutsSummary()

        // Discover TV IP
        findViewById<Button>(R.id.btn_discover).setOnClickListener {
            discoverSpinner.visibility = View.VISIBLE
            it.isEnabled = false
            Thread {
                val found = TvDiscovery.discover(this)
                runOnUiThread {
                    discoverSpinner.visibility = View.GONE
                    it.isEnabled = true
                    when {
                        found.isEmpty() ->
                            Toast.makeText(this, "No TV found", Toast.LENGTH_SHORT).show()
                        found.size == 1 -> {
                            editIp.setText(found[0])
                            Toast.makeText(this, "Found ${found[0]}", Toast.LENGTH_SHORT).show()
                        }
                        else ->
                            AlertDialog.Builder(this)
                                .setTitle("Select TV")
                                .setItems(found.toTypedArray()) { _, i -> editIp.setText(found[i]) }
                                .show()
                    }
                }
            }.start()
        }

        // Save IP
        findViewById<Button>(R.id.btn_save).setOnClickListener {
            val ip = editIp.text.toString().trim()
            if (ip.isNotEmpty()) {
                prefs.edit().putString("tv_ip", ip).apply()
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        // Load apps from TV
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
                    allApps = apps
                    populateAppList()
                    appsContainer.visibility = View.VISIBLE
                    btnApplyShortcuts.visibility = View.VISIBLE
                }
            }.start()
        }

        // Apply shortcut selection
        btnApplyShortcuts.setOnClickListener {
            val chosen = allApps.filter { it.id in selectedIds }
            if (chosen.size < 2) {
                Toast.makeText(this, "Select at least 2 apps", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            client.saveShortcuts(chosen)
            updateShortcutsSummary()
            Toast.makeText(this, "Shortcuts saved", Toast.LENGTH_SHORT).show()
            // Download and cache icons in the background
            Thread {
                chosen.forEach { app ->
                    if (!app.iconUrl.isNullOrEmpty()) client.cacheIcon(app.id, app.iconUrl)
                }
            }.start()
        }
    }

    private fun populateAppList() {
        appsContainer.removeAllViews()
        val currentIds = client.loadShortcuts().map { it.id }.toSet()
        selectedIds.clear()
        selectedIds.addAll(currentIds)

        allApps.forEach { app ->
            val cb = CheckBox(this).apply {
                text = app.title
                isChecked = app.id in selectedIds
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                setPadding(8, 16, 8, 16)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        if (selectedIds.size >= 4) {
                            isChecked = false
                            Toast.makeText(this@SettingsActivity, "Max 4 shortcuts", Toast.LENGTH_SHORT).show()
                        } else {
                            selectedIds.add(app.id)
                        }
                    } else {
                        selectedIds.remove(app.id)
                    }
                }
            }
            appsContainer.addView(cb)
        }
    }

    private fun updateShortcutsSummary() {
        val names = client.loadShortcuts().joinToString(", ") { it.title }
        tvShortcutsSummary.text = if (names.isEmpty()) "None configured" else names
    }
}
