package com.nic.lgpower

import android.os.Build
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Formatting helpers for the keyboard test report, kept apart from the socket code so they can be unit tested. */
object KeyboardReport {
    private val secretValue = Regex("(\"(?:client-key|device_id|deviceId|serialNumber)\"\\s*:\\s*\")[^\"]*(\")")
    private val mac = Regex("\\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\\b")

    // The report gets pasted into public threads, so nothing that identifies the TV or its pairing leaves the phone
    fun redact(text: String): String =
        mac.replace(secretValue.replace(text) { "${it.groupValues[1]}redacted${it.groupValues[2]}" }, "xx:xx:xx:xx:xx:xx")

    fun trim(text: String, max: Int = 700): String =
        if (text.length <= max) text else text.take(max) + "... (${text.length - max} more characters)"

    fun statusOf(frame: String?, failure: String?): String {
        if (frame == null) return "no reply (${failure ?: "unknown"})"
        val json = runCatching { JSONObject(frame) }.getOrNull() ?: return "unreadable reply"
        val refused = json.optJSONObject("payload")?.optBoolean("returnValue", true) == false
        return when {
            json.optString("type") == "error" -> "error: " + (frameErrorText(json) ?: "no text")
            refused -> "refused: " + (frameErrorText(json) ?: "returnValue false")
            else -> "ok"
        }
    }
}

/** Runs the keyboard probes against the active TV on the shared command session and writes the report. */
class KeyboardTest(private val client: WebOsClient, private val appVersion: String) {

    class Report(private val head: List<String>, private val summary: List<String>, private val log: List<String>) {
        fun render(userSaw: String?): String = buildString {
            head.forEach { appendLine(it) }
            appendLine()
            appendLine("SUMMARY")
            summary.forEach { appendLine(it) }
            appendLine("Text seen on the TV (answered by user): ${userSaw ?: "not answered"}")
            appendLine()
            appendLine("LOG (seconds since start)")
            log.forEach { appendLine(it) }
        }.trimEnd()
    }

    private val started = System.currentTimeMillis()
    private val log = CopyOnWriteArrayList<String>()
    private val summary = ArrayList<String>()

    private fun stamp(): String = "+%5.2fs".format(Locale.US, (System.currentTimeMillis() - started) / 1000.0)
    private fun note(line: String) { log.add("${stamp()} $line") }
    private fun frameLine(prefix: String, frame: String) =
        note("$prefix ${KeyboardReport.trim(KeyboardReport.redact(frame))}")

    private fun payloadOf(frame: String?): JSONObject? =
        frame?.let { runCatching { JSONObject(it) }.getOrNull() }?.optJSONObject("payload")

    private fun call(label: String, uri: String, payload: JSONObject = JSONObject()): JSONObject? {
        note("SEND $uri $payload")
        val r = client.rawRequest(uri, payload)
        if (r.frame != null) frameLine("RECV", r.frame) else note("NO REPLY: ${r.failure}")
        summary.add("$label: ${KeyboardReport.statusOf(r.frame, r.failure)}")
        return payloadOf(r.frame)
    }

    private fun JSONObject.text(key: String) = optString(key).takeIf { it.isNotEmpty() }

    fun run(onProgress: (String) -> Unit): Report {
        val head = listOf(
            "LG Power keyboard test",
            "App: $appVersion",
            "Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Started: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(started))
        )

        onProgress("Connecting to the TV")
        val reg = client.registerReply()
        if (reg.frame != null) frameLine("REGISTER REPLY", reg.frame) else note("REGISTER FAILED: ${reg.failure}")
        summary.add("Registration: " + if (reg.frame != null) "ok" else "failed (${reg.failure})")
        val regPayload = payloadOf(reg.frame)
        val granted = listOf("permissions", "grantedPermissions", "scopes")
            .firstOrNull { regPayload?.has(it) == true }?.let { "$it=${regPayload?.opt(it)}" }
        summary.add("Permissions in the register reply: ${granted ?: "not reported"}")

        onProgress("Reading TV info")
        val sys = call("getSystemInfo", "ssap://system/getSystemInfo")
        val sw = call("getCurrentSWInformation", "ssap://com.webos.service.update/getCurrentSWInformation")
        val tvInfo = listOfNotNull(
            sw?.text("model_name") ?: sys?.text("modelName"),
            sw?.text("product_name"),
            listOfNotNull(sw?.text("major_ver"), sw?.text("minor_ver")).joinToString(".").takeIf { it.isNotEmpty() },
            sw?.text("sw_type")
        )
        summary.add("TV: ${if (tvInfo.isEmpty()) "not reported" else tvInfo.joinToString(", ")}")

        onProgress("Checking the connection")
        call("getVolume (control)", "ssap://audio/getVolume")
        call("getForegroundAppInfo (control)", "ssap://com.webos.applicationManager/getForegroundAppInfo")

        var pushes = 0
        var sawFocus = false
        var firstFrame: String? = null
        var subFailure: String? = null
        val lengths = ArrayList<Int>()
        note("SUBSCRIBE $KEYBOARD_URI {\"subscribe\":true}")
        val stop = client.rawSubscribe(
            KEYBOARD_URI, JSONObject().put("subscribe", true),
            onFail = { subFailure = it; note("NO SUBSCRIPTION: $it") }
        ) { text ->
            pushes++
            if (firstFrame == null) firstFrame = text
            frameLine("PUSH", text)
            runCatching { JSONObject(text) }.getOrNull()?.let { frame ->
                if (keyboardFocusOf(frame) == KeyboardFocus.FIELD) sawFocus = true
                frame.optJSONObject("payload")?.optJSONObject("currentWidget")
                    ?.takeIf { it.has("surroundingTextLength") }?.optInt("surroundingTextLength")
                    ?.let { len -> if (lengths.lastOrNull() != len) lengths.add(len) }
            }
        }

        for (left in 15 downTo 1) {
            onProgress("Listening to the TV's keyboard focus, $left s")
            Thread.sleep(1000)
        }

        onProgress("Typing a test text")
        call("insertText, replace false", "$IME/insertText",
            JSONObject().put("text", "LGP1").put("replace", false))
        Thread.sleep(1500)
        call("insertText, replace 0", "$IME/insertText",
            JSONObject().put("text", "LGP2").put("replace", 0))
        Thread.sleep(1500)
        onProgress("Deleting a character and pressing Enter")
        call("deleteCharacters", "$IME/deleteCharacters", JSONObject().put("count", 1))
        Thread.sleep(1000)
        call("sendEnterKey", "$IME/sendEnterKey")
        Thread.sleep(1500)
        stop?.invoke()
        note("UNSUBSCRIBE registerRemoteKeyboard")

        val subStatus = when {
            stop == null -> "not opened (${subFailure ?: "unknown"})"
            firstFrame == null -> "no frames received"
            else -> KeyboardReport.statusOf(firstFrame, null)
        }
        // Keyboard results sit together, ahead of the typing calls they explain
        val at = summary.indexOfFirst { it.startsWith("insertText") }.coerceAtLeast(0)
        summary.add(at, "Text length the TV reported over time: " +
            if (lengths.isEmpty()) "never reported" else lengths.joinToString(", "))
        summary.add(at, "registerRemoteKeyboard: $subStatus; $pushes frame${if (pushes == 1) "" else "s"}; " +
            "a focused text field was reported: ${if (sawFocus) "yes" else "no"}")
        onProgress("Done")
        return Report(head, summary.toList(), log.toList())
    }

    private companion object {
        const val IME = "ssap://com.webos.service.ime"
        const val KEYBOARD_URI = "$IME/registerRemoteKeyboard"
    }
}
