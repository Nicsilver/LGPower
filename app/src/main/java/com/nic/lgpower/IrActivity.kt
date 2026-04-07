package com.nic.lgpower

import android.app.Activity
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator

class IrActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
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
