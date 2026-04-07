package com.nic.lgpower

import android.app.Activity
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast

class ScreenOffActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        Thread {
            val result = WebOsClient(this).turnOffScreen()
            runOnUiThread {
                // Only surface actionable feedback — silent on success
                when (result) {
                    is WebOsClient.Result.NeedsPairing ->
                        Toast.makeText(this, "Accept pairing on your TV, then tap again", Toast.LENGTH_LONG).show()
                    is WebOsClient.Result.Error ->
                        Toast.makeText(this, "Error: ${result.message}", Toast.LENGTH_SHORT).show()
                    else -> {}
                }
                finish()
            }
        }.start()
    }
}
