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
            // The shared session may be a socket left half-open while the phone slept; a dead one
            // would report no power state in time and send a wake to a TV that is already on
            client.resetConnection()
            val stop = client.watchPower { }
            // A slow Wi-Fi can take longer than 3 s to register, and silence in that time would
            // otherwise read as "off" and send a wake to a TV that is already on
            val presence = try { client.awaitPresence(3_000, whileConnectingMs = 8_000) } finally { stop() }
            val wakeOrIr = {
                when {
                    macList(client.tvMac).isNotEmpty() -> wake(client)
                    hasIr -> runCatching { irManager!!.transmit(38000, LGPowerWidget.LG_POWER_PATTERN) }
                    else -> toast("Can't reach the TV. Set its MAC address in Settings to wake it over the network")
                }
            }
            when {
                presence is WebOsClient.Presence.NeedsPairing ->
                    toast("Accept pairing on your TV, then tap again")
                presence is WebOsClient.Presence.Reported && presence.isOn -> {
                    val r = client.turnOff()
                    if (r is WebOsClient.Result.Error) toast("Error: ${r.message}")
                }
                // No answer yet but the session is still registering or registered: the TV is
                // most likely on. If turning it off fails after all, fall back to waking it.
                presence is WebOsClient.Presence.Unknown && client.commandMaybeUp() ->
                    if (client.turnOff() is WebOsClient.Result.Error) wakeOrIr()
                else -> wakeOrIr()
            }
            runOnUiThread { finish() }
        }.start()
    }

    // Same cadence as MainActivity.wakeTv: ~3 s from Quick Start standby, 15-20 s from cold
    private fun wake(client: WebOsClient) {
        client.sendWakeOnLan()
        repeat(25) {
            if (client.afterWake() is WebOsClient.Result.Success) {
                client.turnOnScreen()
                return
            }
            Thread.sleep(1_000)
        }
        toast("TV didn't answer. On the TV, check Turn on via Wi-Fi and Quick Start+ (Always Ready)")
    }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
}
