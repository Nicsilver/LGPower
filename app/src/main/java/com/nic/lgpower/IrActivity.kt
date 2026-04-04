package com.nic.lgpower

import android.app.Activity
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.widget.Toast

class IrActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sendIr()
        finish()
    }

    private fun sendIr() {
        val irManager = getSystemService(CONSUMER_IR_SERVICE) as? ConsumerIrManager
        if (irManager == null || !irManager.hasIrEmitter()) {
            Toast.makeText(this, "No IR blaster found", Toast.LENGTH_SHORT).show()
            return
        }
        val pattern = intent.getIntArrayExtra(EXTRA_PATTERN) ?: LGPowerWidget.LG_POWER_PATTERN
        try {
            irManager.transmit(38000, pattern)
        } catch (e: Exception) {
            Toast.makeText(this, "IR failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_PATTERN = "extra_pattern"
    }
}
