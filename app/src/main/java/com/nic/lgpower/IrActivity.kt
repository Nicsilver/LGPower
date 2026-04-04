package com.nic.lgpower

import android.app.Activity
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.widget.Toast

class IrActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sendLgPower()
        finish()
    }

    private fun sendLgPower() {
        val irManager = getSystemService(CONSUMER_IR_SERVICE) as? ConsumerIrManager
        if (irManager == null || !irManager.hasIrEmitter()) {
            Toast.makeText(this, "No IR blaster found", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            irManager.transmit(38000, LGPowerWidget.LG_POWER_PATTERN)
            Toast.makeText(this, "IR sent!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "IR failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
