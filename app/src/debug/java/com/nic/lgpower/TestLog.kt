package com.nic.lgpower

import android.app.Activity
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * Structured test log for the phone lab (contract: .plans/phonelab-app-log-schema.md).
 * The release source set carries an empty object with the same signatures.
 */
object TestLog {
    private const val TAG = "LgpTest"

    @Volatile var enabled = true

    private var activity: WeakReference<Activity>? = null

    init {
        emit("start", arrayOf("wall", System.currentTimeMillis(), "pid", Process.myPid(), "build", "debug"))
    }

    private fun emit(ev: String, kv: Array<out Any?>) {
        if (!enabled) return
        Log.i(TAG, TestLogJson.line("k", SystemClock.uptimeMillis(), ev, kv))
    }

    fun touch(e: MotionEvent) {
        if (!enabled) return
        val a = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> "down"
            MotionEvent.ACTION_UP -> "up"
            MotionEvent.ACTION_CANCEL -> "cancel"
            else -> return
        }
        emit("touch", arrayOf("a", a, "x", e.rawX.toInt(), "y", e.rawY.toInt(), "et", e.eventTime))
    }

    fun key(sid: String, n: Int, st: String, name: String) =
        emit("key", arrayOf("sid", sid, "n", n, "st", st, "name", name))

    fun move(kind: String, sid: String, n: Int, st: String, dx: Int, dy: Int) =
        emit(kind, arrayOf("sid", sid, "n", n, "st", st, "dx", dx, "dy", dy))

    fun click(sid: String, n: Int, st: String) = emit("click", arrayOf("sid", sid, "n", n, "st", st))

    fun flush(sid: String, queued: Int, written: Int) =
        emit("flush", arrayOf("sid", sid, "q", queued, "w", written))

    fun req(sid: String, id: String, uri: String) = emit("req", arrayOf("sid", sid, "id", id, "uri", uri))

    fun res(sid: String, id: String, ok: Boolean, err: String?) =
        emit("res", arrayOf("sid", sid, "id", id, "ok", ok, "err", TestLogJson.short(err)))

    fun sock(
        role: String, sid: String, st: String,
        code: Int? = null, reason: String? = null, deliberate: Boolean? = null,
    ) = emit(
        "sock",
        arrayOf("role", role, "sid", sid, "st", st, "code", code, "reason", TestLogJson.short(reason), "deliberate", deliberate),
    )

    fun resumed(a: Activity) {
        activity = WeakReference(a)
        emit("life", arrayOf("st", "resume"))
    }

    fun paused(a: Activity) {
        if (activity?.get() === a) activity = null
        emit("life", arrayOf("st", "pause"))
    }

    fun mark(id: String) = emit("mark", arrayOf("id", id, "wall", System.currentTimeMillis()))

    /** Runs on the main thread (the receiver's), where the view tree may be read. */
    fun widgets(): String {
        val out = JSONObject().put("app", "k").put("t", SystemClock.uptimeMillis()).put("ev", "widgets")
        val a = activity?.get()
        if (a == null || a.isFinishing) {
            out.put("ok", false).put("err", "no activity")
        } else {
            val dm = a.resources.displayMetrics
            out.put("ok", true).put("screen", JSONArray().put(dm.widthPixels).put(dm.heightPixels))
            out.put("w", collectWidgets(a))
        }
        val s = out.toString()
        if (enabled) Log.i(TAG, s)
        return s
    }

    private val names = linkedMapOf(
        "up" to R.id.btn_up, "down" to R.id.btn_down, "left" to R.id.btn_left, "right" to R.id.btn_right,
        "ok" to R.id.btn_ok, "back" to R.id.btn_back, "home" to R.id.btn_home, "mute" to R.id.btn_mute,
        "input" to R.id.btn_input, "power" to R.id.btn_power, "keyboard" to R.id.btn_keyboard,
        "settings" to R.id.btn_settings, "app_settings" to R.id.btn_app_settings,
        "touchpad" to R.id.btn_touchpad, "vol_up" to R.id.btn_volume_up, "vol_down" to R.id.btn_volume_down,
        "ch_up" to R.id.btn_np_ch_up, "ch_down" to R.id.btn_np_ch_down,
        "screen_off" to R.id.btn_screen_off, "numpad" to R.id.btn_numpad,
    )

    private val padNames = linkedMapOf(
        "pad" to R.id.touchpad_overlay, "pad_click" to R.id.btn_touchpad_click,
        "pad_back" to R.id.btn_touchpad_back, "pad_exit" to R.id.btn_touchpad_exit,
    )

    private fun collectWidgets(a: Activity): JSONObject {
        val w = JSONObject()
        val overlay = a.findViewById<View>(R.id.touchpad_overlay)
        val padOpen = overlay != null && overlay.visibility == View.VISIBLE
        for ((name, id) in if (padOpen) padNames else names) {
            val v = a.findViewById<View>(id) ?: continue
            if (!v.isShown || v.width == 0 || v.height == 0) continue
            val vis = android.graphics.Rect()
            if (!v.getGlobalVisibleRect(vis) || vis.isEmpty) continue
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            w.put(
                name,
                JSONObject()
                    .put("x", loc[0] + v.width / 2).put("y", loc[1] + v.height / 2)
                    .put("l", loc[0]).put("t", loc[1])
                    .put("r", loc[0] + v.width).put("b", loc[1] + v.height),
            )
        }
        return w
    }
}
