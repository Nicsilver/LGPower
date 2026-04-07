package com.nic.lgpower

import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val client by lazy { WebOsClient(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Power — IR toggle (works whether TV is on or off)
        findViewById<View>(R.id.btn_power).setOnClickListener {
            val irManager = getSystemService(CONSUMER_IR_SERVICE) as? ConsumerIrManager
            if (irManager?.hasIrEmitter() == true) {
                runCatching { irManager.transmit(38000, LGPowerWidget.LG_POWER_PATTERN) }
            }
        }

        // Screen Off — WiFi
        findViewById<View>(R.id.btn_screen_off).setOnClickListener {
            sendCommand { client.turnOffScreen() }
        }

        // D-pad — all WiFi
        findViewById<View>(R.id.btn_up).setOnClickListener    { sendCommand { client.pressUp() } }
        findViewById<View>(R.id.btn_down).setOnClickListener  { sendCommand { client.pressDown() } }
        findViewById<View>(R.id.btn_left).setOnClickListener  { sendCommand { client.pressLeft() } }
        findViewById<View>(R.id.btn_right).setOnClickListener { sendCommand { client.pressRight() } }
        findViewById<View>(R.id.btn_ok).setOnClickListener    { sendCommand { client.pressEnter() } }

        // Back — short: BACK, long: HOME
        findViewById<View>(R.id.btn_back).setOnClickListener     { sendCommand { client.pressKey("BACK") } }
        findViewById<View>(R.id.btn_back).setOnLongClickListener { sendCommand { client.pressKey("HOME") }; true }

        // Settings — short: MENU (opens settings panel), long: HOME
        findViewById<View>(R.id.btn_settings).setOnClickListener     { sendCommand { client.pressKey("MENU") } }
        findViewById<View>(R.id.btn_settings).setOnLongClickListener { sendCommand { client.pressKey("HOME") }; true }

        // App shortcuts — WiFi
        findViewById<View>(R.id.btn_youtube).setOnClickListener { sendCommand { client.launchYouTube() } }
        findViewById<View>(R.id.btn_stremio).setOnClickListener { sendCommand { client.launchStremio() } }

        // Volume + Mute + Keyboard
        findViewById<View>(R.id.btn_volume_up).setOnClickListener   { sendCommand { client.volumeUp() } }
        findViewById<View>(R.id.btn_volume_down).setOnClickListener { sendCommand { client.volumeDown() } }
        findViewById<View>(R.id.btn_mute).setOnClickListener        { sendCommand { client.muteToggle() } }
        findViewById<View>(R.id.btn_keyboard).setOnClickListener    { sendCommand { client.showKeyboard() } }
    }

    private fun sendCommand(block: () -> WebOsClient.Result) {
        Thread {
            val result = block()
            runOnUiThread {
                when (result) {
                    is WebOsClient.Result.NeedsPairing ->
                        Toast.makeText(this, "Accept pairing on your TV, then try again", Toast.LENGTH_LONG).show()
                    is WebOsClient.Result.Error ->
                        Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                    else -> {}
                }
            }
        }.start()
    }

}
