package com.nic.lgpower

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

class ScreenOffActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            val result = WebOsClient(this).turnOffScreen()
            runOnUiThread {
                when (result) {
                    is WebOsClient.Result.Success ->
                        Toast.makeText(this, "Screen off!", Toast.LENGTH_SHORT).show()
                    is WebOsClient.Result.NeedsPairing ->
                        Toast.makeText(this, "Accept pairing on your TV, then tap again", Toast.LENGTH_LONG).show()
                    is WebOsClient.Result.Error ->
                        Toast.makeText(this, "Error: ${result.message}", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
        }.start()
    }
}
