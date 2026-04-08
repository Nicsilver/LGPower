package com.nic.lgpower

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
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

        val editIp          = findViewById<EditText>(R.id.edit_tv_ip)
        val discoverSpinner = findViewById<ProgressBar>(R.id.discover_spinner)
        tvShortcutsSummary  = findViewById(R.id.tv_shortcuts_summary)
        appsGrid            = findViewById(R.id.apps_grid)

        val currentIp = prefs.getString("tv_ip", WebOsClient.DEFAULT_TV_IP) ?: WebOsClient.DEFAULT_TV_IP
        editIp.setText(currentIp)
        editIp.setSelection(editIp.text.length)

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

    override fun onPause() {
        super.onPause()
        val ip = findViewById<EditText>(R.id.edit_tv_ip).text.toString().trim()
        if (ip.isNotEmpty()) prefs.edit().putString("tv_ip", ip).apply()
    }

    private fun updateSummary(chosen: List<WebOsClient.TvApp>) {
        tvShortcutsSummary.text = "Tap to select · Long-press to reorder · ${chosen.size}/4 selected"
    }
}
