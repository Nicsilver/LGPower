package com.nic.lgpower

import android.content.SharedPreferences

/** What the pill on the right of the d-pad does. */
enum class RightPill(val key: String, val label: String) {
    BRIGHTNESS("brightness", "Brightness"),
    CHANNEL("channel", "Channel buttons");

    companion object {
        private const val PREF = "right_pill"

        fun get(prefs: SharedPreferences): RightPill =
            when (prefs.getString(PREF, null)) {
                CHANNEL.key -> CHANNEL
                BRIGHTNESS.key, "brightness_slider", "brightness_buttons" -> BRIGHTNESS
                // Pre-1.35 installs stored a channel switch
                else -> if (prefs.getBoolean("right_pill_channel", false)) CHANNEL else BRIGHTNESS
            }

        fun set(prefs: SharedPreferences, mode: RightPill) =
            prefs.edit().putString(PREF, mode.key).apply()
    }
}
