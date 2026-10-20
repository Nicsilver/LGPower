package com.nic.lgpower

import org.json.JSONObject

/**
 * Error text of an SSAP frame. Most failures carry it in payload.errorText, but a permission
 * failure puts it in the top-level `error` and sends an empty payload.
 */
fun frameErrorText(frame: JSONObject): String? =
    frame.optJSONObject("payload")?.optString("errorText")?.takeIf { it.isNotEmpty() }
        ?: frame.optString("error").takeIf { it.isNotEmpty() }

fun isPermissionError(message: String) =
    message.trimStart().startsWith("401") || message.contains("insufficient permission", ignoreCase = true)

/**
 * A response frame fails when the payload says returnValue false, or when it carries a top-level
 * `error` without returnValue true: a TV that adds a stray error to a good reply must not break the call.
 * Real failures (401s included) arrive as type "error" and are handled before this.
 */
fun responseFailed(frame: JSONObject): Boolean {
    val payload = frame.optJSONObject("payload")
    if (payload?.has("returnValue") == true) return !payload.optBoolean("returnValue", true)
    return frame.optString("error").isNotEmpty()
}

enum class KeyboardFocus { UNKNOWN, FIELD, NO_FIELD }

/**
 * Reads a registerRemoteKeyboard frame. Only an explicit focus:false counts as "no field": the TV
 * omits currentWidget (or its keys) when its state is not valid, and LG's IME is closed source, so
 * a missing focus key is unknown rather than a reason to show a hint.
 */
fun keyboardFocusOf(frame: JSONObject): KeyboardFocus {
    if (frame.optString("type") == "error") return KeyboardFocus.UNKNOWN
    val payload = frame.optJSONObject("payload") ?: return KeyboardFocus.UNKNOWN
    if (!payload.optBoolean("returnValue", true)) return KeyboardFocus.UNKNOWN
    val widget = payload.optJSONObject("currentWidget") ?: return KeyboardFocus.UNKNOWN
    if (!widget.has("focus")) return KeyboardFocus.UNKNOWN
    return if (widget.optBoolean("focus")) KeyboardFocus.FIELD else KeyboardFocus.NO_FIELD
}
