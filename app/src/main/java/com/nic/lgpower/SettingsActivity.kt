package com.nic.lgpower

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("webos", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val editIp = findViewById<EditText>(R.id.edit_tv_ip)
        val spinner = findViewById<ProgressBar>(R.id.discover_spinner)
        val currentIp = prefs.getString("tv_ip", WebOsClient.DEFAULT_TV_IP) ?: WebOsClient.DEFAULT_TV_IP
        editIp.setText(currentIp)
        editIp.setSelection(editIp.text.length)

        findViewById<Button>(R.id.btn_discover).setOnClickListener {
            spinner.visibility = View.VISIBLE
            it.isEnabled = false
            Thread {
                val found = TvDiscovery.discover(this)
                runOnUiThread {
                    spinner.visibility = View.GONE
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

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            val ip = editIp.text.toString().trim()
            if (ip.isNotEmpty()) {
                prefs.edit().putString("tv_ip", ip).apply()
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
