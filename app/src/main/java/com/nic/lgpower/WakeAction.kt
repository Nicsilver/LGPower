package com.nic.lgpower

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * What the Power tap does once a network-woken TV answers. Wake-on-LAN brings most
 * sets up on an input rather than where they were, so the default lands on Home.
 * Saved per TV because inputs and shortcuts differ between sets.
 */
object WakeAction {

    const val HOME = "home"
    const val STAY = "stay"
    const val INPUT = "input"
    const val APP = "app"

    data class Action(val kind: String, val id: String = "", val label: String = "") {
        val display: String get() = when (kind) {
            HOME -> "Home screen"
            STAY -> "Leave as is"
            else -> label
        }
    }

    private fun key(prefs: SharedPreferences) =
        TvStore.activeId(prefs)?.let { "wake_action_$it" } ?: "wake_action"

    fun get(prefs: SharedPreferences): Action {
        val json = prefs.getString(key(prefs), null) ?: return Action(HOME)
        return runCatching {
            val o = JSONObject(json)
            Action(o.getString("kind"), o.optString("id"), o.optString("label"))
        }.getOrDefault(Action(HOME))
    }

    fun set(prefs: SharedPreferences, a: Action) {
        val o = JSONObject().put("kind", a.kind).put("id", a.id).put("label", a.label)
        prefs.edit().putString(key(prefs), o.toString()).apply()
    }
}
