package com.nic.lgpower

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("webos", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val editIp = findViewById<EditText>(R.id.edit_tv_ip)
        val currentIp = prefs.getString("tv_ip", WebOsClient.DEFAULT_TV_IP) ?: WebOsClient.DEFAULT_TV_IP
        editIp.setText(currentIp)
        editIp.setSelection(editIp.text.length)

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
