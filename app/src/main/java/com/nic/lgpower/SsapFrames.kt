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

enum class KeyboardFocus { UNKNOWN, FIELD, NO_FIELD }

/**
 * Reads a registerRemoteKeyboard frame. The first reply omits currentWidget when nothing is
 * focused, and later pushes carry it with focus true or false.
 */
fun keyboardFocusOf(frame: JSONObject): KeyboardFocus {
    if (frame.optString("type") == "error") return KeyboardFocus.UNKNOWN
    val payload = frame.optJSONObject("payload") ?: return KeyboardFocus.UNKNOWN
    if (!payload.optBoolean("returnValue", true)) return KeyboardFocus.UNKNOWN
    val widget = payload.optJSONObject("currentWidget")
    return if (widget?.optBoolean("focus", false) == true) KeyboardFocus.FIELD else KeyboardFocus.NO_FIELD
}
