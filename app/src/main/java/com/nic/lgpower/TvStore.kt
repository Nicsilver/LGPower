package com.nic.lgpower

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Saved TVs. The flat `tv_ip` / `tv_mac` / `client_key` prefs stay the live
 * connection everything else reads, so switching TVs is a copy in and out of
 * this list: [syncFromLive] captures what pairing and Settings wrote for the
 * active TV, [switchTo] loads another one.
 */
object TvStore {

    data class Tv(val id: String, val name: String, val ip: String, val mac: String, val clientKey: String)

    private const val LIST = "tvs"
    private const val ACTIVE = "active_tv"

    fun list(prefs: SharedPreferences): List<Tv> {
        migrate(prefs)
        return read(prefs)
    }

    fun activeId(prefs: SharedPreferences): String? { migrate(prefs); return prefs.getString(ACTIVE, null) }

    fun active(prefs: SharedPreferences): Tv? = list(prefs).firstOrNull { it.id == activeId(prefs) }

    /** Display name for the remote's title. */
    fun activeName(prefs: SharedPreferences): String = active(prefs)?.name ?: "LG TV Remote"

    fun nextDefaultName(prefs: SharedPreferences): String {
        val n = list(prefs).size
        return if (n == 0) "LG TV" else "LG TV ${n + 1}"
    }

    /** Writes the live connection prefs into the active entry. */
    fun syncFromLive(prefs: SharedPreferences) {
        val id = prefs.getString(ACTIVE, null) ?: return
        val ip = prefs.getString("tv_ip", "") ?: ""
        if (ip.isBlank()) return
        write(prefs, read(prefs).map {
            if (it.id == id) it.copy(ip = ip, mac = prefs.getString("tv_mac", "") ?: "", clientKey = prefs.getString("client_key", "") ?: "")
            else it
        })
    }

    /** Adds the live connection prefs as a new TV and makes it the active one. */
    fun addFromLive(prefs: SharedPreferences, name: String): Tv {
        val tv = Tv(UUID.randomUUID().toString(), name.ifBlank { nextDefaultName(prefs) },
            prefs.getString("tv_ip", "") ?: "", prefs.getString("tv_mac", "") ?: "", prefs.getString("client_key", "") ?: "")
        write(prefs, read(prefs) + tv)
        prefs.edit().putString(ACTIVE, tv.id).apply()
        return tv
    }

    fun switchTo(prefs: SharedPreferences, id: String) {
        syncFromLive(prefs)
        val tv = read(prefs).firstOrNull { it.id == id } ?: return
        prefs.edit()
            .putString(ACTIVE, tv.id)
            .putString("tv_ip", tv.ip)
            .putString("tv_mac", tv.mac)
            .putString("client_key", tv.clientKey)
            .remove("last_volume").remove("last_muted").remove("last_brightness")
            .apply()
    }

    /** Clears the live prefs so a new pairing starts clean; the active entry keeps its copy. */
    fun beginAdd(prefs: SharedPreferences) {
        syncFromLive(prefs)
        prefs.edit().remove("tv_ip").remove("tv_mac").remove("client_key").apply()
    }

    /** Undo of [beginAdd] when the user backs out before pairing. */
    fun cancelAdd(prefs: SharedPreferences) {
        val id = prefs.getString(ACTIVE, null) ?: return
        val tv = read(prefs).firstOrNull { it.id == id } ?: return
        prefs.edit().putString("tv_ip", tv.ip).putString("tv_mac", tv.mac).putString("client_key", tv.clientKey).apply()
    }

    /** Edits from the TV detail screen; the live prefs follow when it is the active TV. */
    fun update(prefs: SharedPreferences, id: String, name: String, ip: String, mac: String) {
        write(prefs, read(prefs).map {
            if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }, ip = ip.ifBlank { it.ip }, mac = mac) else it
        })
        if (prefs.getString(ACTIVE, null) == id) {
            val e = prefs.edit().putString("tv_mac", mac)
            if (ip.isNotBlank()) e.putString("tv_ip", ip)
            e.apply()
        }
    }

    fun rename(prefs: SharedPreferences, id: String, name: String) {
        if (name.isBlank()) return
        write(prefs, read(prefs).map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    /** Removes a TV; if it was active, the first remaining one takes over, or the live prefs are cleared. */
    fun remove(prefs: SharedPreferences, id: String) {
        syncFromLive(prefs)
        val rest = read(prefs).filter { it.id != id }
        write(prefs, rest)
        if (prefs.getString(ACTIVE, null) == id) {
            val next = rest.firstOrNull()
            if (next != null) switchTo(prefs, next.id)
            else prefs.edit().remove(ACTIVE).remove("tv_ip").remove("tv_mac").remove("client_key").apply()
        }
    }

    // Installs from before saved TVs existed have only the flat prefs
    private fun migrate(prefs: SharedPreferences) {
        if (prefs.contains(LIST)) return
        val ip = prefs.getString("tv_ip", "") ?: ""
        if (ip.isBlank()) { write(prefs, emptyList()); return }
        val tv = Tv(UUID.randomUUID().toString(), "LG TV", ip, prefs.getString("tv_mac", "") ?: "", prefs.getString("client_key", "") ?: "")
        write(prefs, listOf(tv))
        prefs.edit().putString(ACTIVE, tv.id).apply()
    }

    private fun read(prefs: SharedPreferences): List<Tv> = runCatching {
        val arr = JSONArray(prefs.getString(LIST, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Tv(o.getString("id"), o.getString("name"), o.optString("ip"), o.optString("mac"), o.optString("key"))
        }
    }.getOrDefault(emptyList())

    private fun write(prefs: SharedPreferences, tvs: List<Tv>) {
        val arr = JSONArray()
        tvs.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("ip", it.ip).put("mac", it.mac).put("key", it.clientKey)) }
        prefs.edit().putString(LIST, arr.toString()).apply()
    }
}
