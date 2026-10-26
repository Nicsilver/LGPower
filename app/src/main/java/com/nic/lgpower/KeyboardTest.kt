package com.nic.lgpower

import android.os.Build
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Collections
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Formatting helpers for the keyboard test report, kept apart from the socket code so they can be unit tested. */
object KeyboardReport {
    private val secretValue = Regex("(\"(?:client-key|device_id|deviceId|serialNumber)\"\\s*:\\s*\")[^\"]*(\")")
    private val mac = Regex("\\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\\b")
    private val ipv4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")

    // The report gets pasted into public threads, so nothing that identifies the TV or its pairing leaves the phone
    fun redact(text: String): String {
        val noSecrets = secretValue.replace(text) { "${it.groupValues[1]}redacted${it.groupValues[2]}" }
        return ipv4.replace(mac.replace(noSecrets, "xx:xx:xx:xx:xx:xx"), "x.x.x.x")
    }

    fun trim(text: String, max: Int = 700): String =
        if (text.length <= max) text else text.take(max) + "... (${text.length - max} more characters)"

    /** The summary wording shared with the Flutter app's report, so both can be compared line by line. */
    fun summaryStatus(frame: String?, failure: String?): String {
        val status = statusOf(frame, failure)
        return when {
            status == "ok" -> "OK"
            status.startsWith("error: ") -> "ERROR " + status.removePrefix("error: ")
            status.startsWith("refused: ") -> "ERROR " + status.removePrefix("refused: ")
            else -> "ERROR $status"
        }
    }

    fun statusOf(frame: String?, failure: String?): String {
        if (frame == null) return "no reply (${failure ?: "unknown"})"
        val json = runCatching { JSONObject(frame) }.getOrNull() ?: return "unreadable reply"
        val refused = responseFailed(json)
        return when {
            json.optString("type") == "error" -> "error: " + (frameErrorText(json) ?: "no text")
            refused -> "refused: " + (frameErrorText(json) ?: "returnValue false")
            else -> "ok"
        }
    }
}

/** Runs the keyboard probes against the active TV on the shared command session and writes the report. */
class KeyboardTest(
    private val client: WebOsClient,
    private val appVersion: String,
    private val prefs: android.content.SharedPreferences
) {

    class Report(private val head: List<String>, private val summary: List<String>, private val log: List<String>) {
        fun render(seen: Set<String>): String = buildString {
            head.forEach { appendLine(it) }
            appendLine()
            appendLine("SUMMARY")
            summary.forEach { appendLine(it) }
            appendLine()
            appendLine("LOG (seconds since start)")
            log.forEach { appendLine(it) }
            appendLine()
            appendLine("== Letters on the TV afterwards ==")
            appendLine("A and B were typed before the keyboard subscription, C during it, D through the notification route, then one character was deleted.")
            append("Visible in the TV text field: " +
                LETTERS.filter { it in seen }.joinToString(", ").ifEmpty { "none" })
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

    private fun call(label: String, uri: String, payload: JSONObject = JSONObject()): String {
        log.add("== $label ==")
        note("SEND $uri $payload")
        val r = client.rawRequest(uri, payload)
        if (r.frame != null) frameLine("RECV", r.frame) else note("NO REPLY: ${r.failure}")
        summary.add("$label: ${KeyboardReport.summaryStatus(r.frame, r.failure)}")
        return KeyboardReport.statusOf(r.frame, r.failure)
    }

    // Answers of the plain insertText calls; the verdict below follows from them alone
    private val typingAnswers = CopyOnWriteArrayList<String>()
    private fun typingCall(label: String, payload: JSONObject) {
        typingAnswers.add(call(label, "$IME/insertText", payload))
    }

    fun run(onProgress: (String) -> Unit): Report {
        val head = listOf(
            "Keyboard test report",
            "App: $appVersion",
            "Phone: ${Build.MANUFACTURER} ${Build.MODEL}",
            "OS: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Time: " + SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(started))
        )

        log.add("== Registration ==")
        val reg = client.registerReply()
        if (reg.frame != null) frameLine("REGISTER REPLY", reg.frame) else note("REGISTER FAILED: ${reg.failure}")
        val regPayload = payloadOf(reg.frame)
        val granted = listOf("permissions", "grantedPermissions", "scopes")
            .firstOrNull { regPayload?.has(it) == true }?.let { "$it=${regPayload?.opt(it)}" }
        note("granted permissions: ${granted ?: "not reported by the TV"}")

        onProgress("Reading TV info")
        call("getSystemInfo", "ssap://system/getSystemInfo")
        call("getSoftwareInformation", "ssap://com.webos.service.update/getCurrentSWInformation")
        summary.add("Software version the app stored for this TV: ${TypingSupport.softwareVersion(prefs) ?: "none (the TV did not report one)"}")

        onProgress("Checking the connection")
        call("control: getVolume", "ssap://audio/getVolume")
        call("control: getForegroundAppInfo", "ssap://com.webos.applicationManager/getForegroundAppInfo")

        onProgress("Typing without the keyboard subscription")
        typingCall("insertText \"A\" replace=false", JSONObject().put("text", "A").put("replace", false))
        typingCall("insertText \"B\" replace=0", JSONObject().put("text", "B").put("replace", 0))

        keyboardWindow(onProgress)
        recordVerdict()
        onProgress("Done")
        return Report(head, summary.toList(), log.toList())
    }

    /** Re-checks the per-TV "typing refused" mark from this run, so a TV is never stuck as unsupported. */
    private fun recordVerdict() {
        val refused = typingAnswers.isNotEmpty() && typingAnswers.all { isPermissionError(it.removePrefix("error: ").removePrefix("refused: ")) && it != "ok" }
        when {
            typingAnswers.any { it == "ok" } -> {
                TypingSupport.clear(prefs)
                summary.add("Typing verdict: the TV accepted text, the keyboard is on for this TV")
            }
            refused -> {
                TypingSupport.markRefused(prefs)
                summary.add("Typing verdict: the TV refused text with a permission error, the keyboard now offers a web search on the TV instead")
            }
            else -> summary.add("Typing verdict: unclear, nothing was changed")
        }
    }

    private fun notificationProbe(lengths: List<Int>) {
        log.add("== insertText \"D\" through the notification route ==")
        val before = synchronized(lengths) { lengths.lastOrNull() }
        val (alert, close) = client.rawNotificationInsert("D")
        alert.frame?.let { frameLine("ALERT", it) } ?: note("ALERT NO REPLY: ${alert.failure}")
        close?.frame?.let { frameLine("CLOSE", it) }
        Thread.sleep(2_000)
        val after = synchronized(lengths) { lengths.lastOrNull() }
        summary.add("insertText \"D\" through the notification route: alert ${KeyboardReport.summaryStatus(alert.frame, alert.failure)}" +
            (close?.let { ", close ${KeyboardReport.summaryStatus(it.frame, it.failure)}" } ?: "") +
            ", text length ${if (before == null || after == null) "not reported" else if (after > before) "grew" else "did not grow"}")
    }

    private fun keyboardWindow(onProgress: (String) -> Unit) {
        onProgress("Listening to the TV keyboard")
        // Measured from its own start, so a slow call earlier on does not eat into the listening time
        val window = System.nanoTime()
        fun waitUntil(ms: Long) {
            val left = ms - (System.nanoTime() - window) / 1_000_000
            if (left > 0) Thread.sleep(left)
        }
        log.add("== registerRemoteKeyboard (${LISTEN_MS / 1000} s) ==")

        // Written on the socket thread, read here
        val pushes = AtomicInteger()
        val sawFocus = AtomicBoolean(false)
        val firstFrame = AtomicReference<String?>()
        val subFailure = AtomicReference<String?>()
        val lengths = Collections.synchronizedList(ArrayList<Int>())
        note("SUBSCRIBE $KEYBOARD_URI {\"subscribe\":true}")
        val stop = client.rawSubscribe(
            KEYBOARD_URI, JSONObject().put("subscribe", true),
            onFail = { subFailure.set(it); note("NO SUBSCRIPTION: $it") }
        ) { text ->
            val n = pushes.incrementAndGet()
            firstFrame.compareAndSet(null, text)
            frameLine("PUSH #$n", text)
            runCatching { JSONObject(text) }.getOrNull()?.let { frame ->
                if (keyboardFocusOf(frame) == KeyboardFocus.FIELD) sawFocus.set(true)
                frame.optJSONObject("payload")?.optJSONObject("currentWidget")
                    ?.takeIf { it.has("surroundingTextLength") }?.optInt("surroundingTextLength")
                    ?.let { len -> synchronized(lengths) { if (lengths.lastOrNull() != len) lengths.add(len) } }
            }
        }

        try {
            waitUntil(LISTEN_MS * 2 / 10)
            typingCall("insertText \"C\" replace=false (subscribed)", JSONObject().put("text", "C").put("replace", false))
            waitUntil(LISTEN_MS * 3 / 10)
            notificationProbe(lengths)
            waitUntil(LISTEN_MS * 4 / 10)
            call("deleteCharacters count=1", "$IME/deleteCharacters", JSONObject().put("count", 1))
            waitUntil(LISTEN_MS * 6 / 10)
            call("sendEnterKey", "$IME/sendEnterKey")
            waitUntil(LISTEN_MS)
        } finally {
            stop?.invoke()
        }
        note("UNSUBSCRIBE registerRemoteKeyboard")

        if (stop == null) {
            summary.add("registerRemoteKeyboard: not sent (${subFailure.get() ?: "session not ready"})")
        } else {
            summary.add("registerRemoteKeyboard: ${pushes.get()} frame(s) in ${LISTEN_MS / 1000} s")
            firstFrame.get()?.let { summary.add("registerRemoteKeyboard first frame: ${KeyboardReport.statusOf(it, null)}") }
            summary.add("A focused text field was reported: ${if (sawFocus.get()) "yes" else "no"}")
            summary.add("Text length the TV reported over time: " + synchronized(lengths) {
                if (lengths.isEmpty()) "never reported" else lengths.joinToString(", ")
            })
        }
    }

    companion object {
        val LETTERS = listOf("A", "B", "C")
        private const val LISTEN_MS = 15_000L
        private const val IME = "ssap://com.webos.service.ime"
        private const val KEYBOARD_URI = "$IME/registerRemoteKeyboard"
    }
}
