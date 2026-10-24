package com.nic.lgpower

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * Keeps the Wi-Fi radio out of power save while the remote is in front. Power save batches small
 * frames for up to a beacon interval, which shows up as touchpad stutter and slow key presses.
 * Android only honours the low-latency mode for a foreground app with the screen on.
 */
object LowLatencyWifi {
    private var lock: WifiManager.WifiLock? = null
    private var failureLogged = false
    private val holders = mutableSetOf<String>()

    private val mode get() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF

    val modeName get() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "low-latency" else "high-performance"

    @get:Synchronized val isHeld get() = lock?.isHeld == true

    /** Several screens share the one lock, so it is only dropped when the last holder lets go. Returns whether the lock is held afterwards; never throws, since the remote works fine without it. */
    @Synchronized
    fun acquire(context: Context, holder: String = "remote"): Boolean {
        holders.add(holder)
        try {
            val held = lock ?: (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createWifiLock(mode, "lgpower:remote")
                ?.also { it.setReferenceCounted(false); lock = it }
                ?: return false
            if (!held.isHeld) held.acquire()
            return held.isHeld
        } catch (e: Exception) {
            if (!failureLogged) { failureLogged = true; Log.w("LowLatencyWifi", "acquire failed: ${e.javaClass.simpleName}") }
            return false
        }
    }

    @Synchronized
    fun release(holder: String = "remote") {
        holders.remove(holder)
        if (holders.isNotEmpty()) return
        try {
            lock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            if (!failureLogged) { failureLogged = true; Log.w("LowLatencyWifi", "release failed: ${e.javaClass.simpleName}") }
        }
    }
}
