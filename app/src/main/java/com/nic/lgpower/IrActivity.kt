package com.nic.lgpower

import android.app.Activity
import android.hardware.ConsumerIrManager
import android.os.Bundle

class IrActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val irManager = getSystemService(CONSUMER_IR_SERVICE) as? ConsumerIrManager
        if (irManager?.hasIrEmitter() == true) {
            val pattern = intent.getIntArrayExtra(EXTRA_PATTERN) ?: LGPowerWidget.LG_POWER_PATTERN
            runCatching { irManager.transmit(38000, pattern) }
        }
        finish()
    }

    companion object {
        const val EXTRA_PATTERN = "extra_pattern"
    }
}
