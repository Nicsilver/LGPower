package com.nic.lgpower

import android.content.SharedPreferences

/** What the pill on the right of the d-pad does. */
enum class RightPill(val key: String, val label: String) {
    BRIGHTNESS_SLIDER("brightness_slider", "Brightness slider"),
    BRIGHTNESS_BUTTONS("brightness_buttons", "Brightness buttons"),
    CHANNEL("channel", "Channel buttons");

    companion object {
        private const val PREF = "right_pill"

        fun get(prefs: SharedPreferences): RightPill {
            prefs.getString(PREF, null)?.let { key -> entries.firstOrNull { it.key == key }?.let { return it } }
            // Pre-1.35 installs stored two independent switches; channel used to win over brightness
            return when {
                prefs.getBoolean("right_pill_channel", false) -> CHANNEL
                !prefs.getBoolean("brightness_slider", true)  -> BRIGHTNESS_BUTTONS
                else -> BRIGHTNESS_SLIDER
            }
        }

        fun set(prefs: SharedPreferences, mode: RightPill) =
            prefs.edit().putString(PREF, mode.key).apply()
    }
}
