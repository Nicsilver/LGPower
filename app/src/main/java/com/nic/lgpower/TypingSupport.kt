package com.nic.lgpower

import android.content.SharedPreferences
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Whether a saved TV accepts typed text from this app, remembered per TV so the keyboard stops
 * offering something that cannot work. It is set only by a real permission refusal from that TV,
 * never from a version number, and a changed software version or a few days of age clears it.
 */
object TypingSupport {
    // A firmware update can fix the refusal, and a TV that never reports its version still gets a retry
    const val MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000

    private fun mark(prefs: SharedPreferences) =
        TvStore.activeId(prefs)?.let { "kbd_refused_$it" } ?: "kbd_refused"
    private fun version(prefs: SharedPreferences) =
        TvStore.activeId(prefs)?.let { "tv_sw_$it" } ?: "tv_sw"

    fun softwareVersion(prefs: SharedPreferences): String? = prefs.getString(version(prefs), null)

    fun isRefused(prefs: SharedPreferences, now: Long = System.currentTimeMillis()): Boolean {
        val stamp = prefs.getLong(mark(prefs), 0L)
        return stamp != 0L && now - stamp in 0..MAX_AGE_MS
    }

    fun markRefused(prefs: SharedPreferences, now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(mark(prefs), now).apply()
    }

    fun clear(prefs: SharedPreferences) {
        prefs.edit().remove(mark(prefs)).apply()
    }

    /** Stores the version the TV reports; a different one than before lifts the refusal. */
    fun noteVersion(prefs: SharedPreferences, reported: String) {
        val old = softwareVersion(prefs)
        val e = prefs.edit().putString(version(prefs), reported)
        if (old != null && old != reported) e.remove(mark(prefs))
        e.apply()
    }

    /** One comparable string from a getCurrentSWInformation or systemproperty reply payload, null when it holds none. */
    fun versionOf(payload: JSONObject?): String? {
        payload ?: return null
        val parts = listOf("product_name", "major_ver", "minor_ver", "dre_ver", "webos_release", "firmwareVersion", "sdkVersion")
            .mapNotNull { payload.optString(it).takeIf { v -> v.isNotEmpty() } }
        return parts.joinToString(" ").ifEmpty { null }
    }

    /** What the TV browser opens for typed text: a link as typed, anything else as a web search. */
    fun browserTarget(text: String): String {
        val t = text.trim()
        if (t.none { it.isWhitespace() }) {
            if (t.startsWith("http://") || t.startsWith("https://")) return t
            // The last label must be letters, so "3.14" or "v1.2" stay searches
            if (Regex("""^[\w-]+(\.[\w-]+)*\.[A-Za-z]{2,}(/\S*)?$""").matches(t)) return "https://$t"
        }
        return "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
    }
}
