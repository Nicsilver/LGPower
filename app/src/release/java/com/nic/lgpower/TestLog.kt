package com.nic.lgpower

import android.app.Activity
import android.view.MotionEvent

/** Release stand-in for the debug test log: same calls, empty bodies, no strings. */
@Suppress("UNUSED_PARAMETER")
object TestLog {
    fun touch(e: MotionEvent) {}
    fun key(sid: String, n: Int, st: String, name: String) {}
    fun move(kind: String, sid: String, n: Int, st: String, dx: Int, dy: Int) {}
    fun click(sid: String, n: Int, st: String) {}
    fun flush(sid: String, queued: Int, written: Int) {}
    fun req(sid: String, id: String, uri: String) {}
    fun res(sid: String, id: String, ok: Boolean, err: String?) {}
    fun sock(
        role: String, sid: String, st: String,
        code: Int? = null, reason: String? = null, deliberate: Boolean? = null,
    ) {}
    fun resumed(a: Activity) {}
    fun paused(a: Activity) {}
}
