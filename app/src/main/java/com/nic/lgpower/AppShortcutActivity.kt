package com.nic.lgpower

import android.app.Activity
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast

class AppShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appId = intent.getStringExtra("app_id") ?: run { finish(); return }
        val title = intent.getStringExtra("app_title") ?: appId

        getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))

        Thread {
            val result = WebOsClient(this).launchApp(appId)
            runOnUiThread {
                when (result) {
                    is WebOsClient.Result.NeedsPairing ->
                        Toast.makeText(this, "Accept pairing on your TV, then tap again", Toast.LENGTH_LONG).show()
                    is WebOsClient.Result.Error ->
                        Toast.makeText(this, "Error launching $title: ${result.message}", Toast.LENGTH_SHORT).show()
                    else -> {}
                }
                finish()
            }
        }.start()
    }
}
