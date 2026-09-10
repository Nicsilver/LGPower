package com.nic.lgpower

import android.app.Activity
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast

/**
 * Home-screen Power widget trampoline. Mirrors the in-app Power tap: ask the TV what
 * state it is in, turn it off over Wi-Fi if it is on, otherwise wake it with WoL and
 * poke it until webOS answers. IR is only the last resort (no MAC to wake with), so the
 * widget keeps working on phones without a blaster.
 */
class PowerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        val client = WebOsClient(this)
        val irManager = getSystemService(CONSUMER_IR_SERVICE) as? ConsumerIrManager
        val hasIr = irManager?.hasIrEmitter() == true
        Thread {
            val stop = client.watchPower { }
            val presence = try { client.awaitPresence(3_000) } finally { stop() }
            when {
                presence is WebOsClient.Presence.NeedsPairing ->
                    toast("Accept pairing on your TV, then tap again")
                presence is WebOsClient.Presence.Reported && presence.isOn -> {
                    val r = client.turnOff()
                    if (r is WebOsClient.Result.Error) toast("Error: ${r.message}")
                }
                client.tvMac.isNotEmpty() -> wake(client)
                hasIr -> runCatching { irManager!!.transmit(38000, LGPowerWidget.LG_POWER_PATTERN) }
                else -> toast("Can't reach the TV. Set its MAC address in Settings to wake it over the network")
            }
            runOnUiThread { finish() }
        }.start()
    }

    // Same cadence as MainActivity.wakeTv: ~3 s from Quick Start standby, 15-20 s from cold
    private fun wake(client: WebOsClient) {
        client.sendWakeOnLan()
        repeat(25) {
            if (client.goHome() is WebOsClient.Result.Success) {
                client.turnOnScreen()
                return
            }
            Thread.sleep(1_000)
        }
        toast("TV didn't wake up. Is network standby on?")
    }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
}
