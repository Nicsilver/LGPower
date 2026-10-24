package com.nic.lgpower

import org.json.JSONObject
import java.util.Locale

/** The last few session-death lines of this process, so a pasted report shows how the connection behaved. */
internal class DeathLog(private val capacity: Int = 10, private val clock: () -> Long = System::currentTimeMillis) {
    class Entry(val atMs: Long, val line: String)

    private val entries = ArrayDeque<Entry>()

    @Synchronized fun add(line: String) {
        entries.addLast(Entry(clock(), line))
        while (entries.size > capacity) entries.removeFirst()
    }

    @Synchronized fun recent(): List<Entry> = entries.toList()

    @Synchronized fun clear() = entries.clear()

    companion object { val shared = DeathLog() }
}

class WifiSnapshot(val frequencyMhz: Int, val rssiDbm: Int, val linkMbps: Int)

/** What the timed TV probe found. [failure] is set when the TV part could not run to the end. */
internal class TvProbe(
    val failure: String? = null,
    val model: String? = null,
    val software: String? = null,
    val commandOpenMs: Double? = null,
    val roundTripsMs: List<Double> = emptyList(),
    val roundTripFailures: Int = 0,
    val pointerOpenMs: Double? = null,
    val pointerFailure: String? = null,
    val frameSendMs: List<Double> = emptyList(),
)

internal class ReportInputs(
    val appName: String,
    val versionName: String,
    val versionCode: Int,
    val phone: String,
    val os: String,
    val wifi: WifiSnapshot?,
    val lockLine: String,
    val tv: TvProbe,
    val deaths: List<DeathLog.Entry>,
    val nowMs: Long,
)

/** Report wording is shared with the Flutter app's report so the two read the same. */
internal object ConnReport {
    fun bandOf(mhz: Int): String = when (mhz) {
        in 2400..2500 -> "2.4 GHz"
        in 4900..5924 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        else -> "unknown band ($mhz MHz)"
    }

    private fun fmt(v: Double): String =
        String.format(Locale.US, if (v >= 10) "%.0f" else if (v >= 1) "%.1f" else "%.2f", v)

    fun spread(values: List<Double>): String {
        if (values.isEmpty()) return "no samples"
        val s = values.sorted()
        val median = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        return "min ${fmt(s.first())} / median ${fmt(median)} / max ${fmt(s.last())} ms"
    }

    private fun payloadOf(frame: String?): JSONObject? =
        frame?.let { runCatching { JSONObject(it).optJSONObject("payload") }.getOrNull() }

    fun tvModel(frame: String?, failure: String?): String {
        val status = KeyboardReport.statusOf(frame, failure)
        if (status != "ok") return "unavailable ($status)"
        return payloadOf(frame)?.optString("modelName")?.takeIf { it.isNotEmpty() } ?: "unavailable (not reported)"
    }

    fun tvSoftware(frame: String?, failure: String?): String {
        val status = KeyboardReport.statusOf(frame, failure)
        if (status != "ok") return "unavailable ($status)"
        val p = payloadOf(frame) ?: return "unavailable (not reported)"
        val version = listOf("major_ver", "minor_ver").map { p.optString(it) }.filter { it.isNotEmpty() }.joinToString(".")
        val head = listOf(p.optString("product_name"), p.optString("sw_type").lowercase(Locale.ROOT), version)
            .filter { it.isNotEmpty() }.joinToString(" ")
        val model = p.optString("model_name").takeIf { it.isNotEmpty() }
        return when {
            head.isEmpty() && model == null -> "unavailable (not reported)"
            model == null -> head
            else -> "$head ($model)"
        }
    }

    fun render(r: ReportInputs): String = buildString {
        appendLine("LG Power connection report")
        appendLine("App: ${r.appName} ${r.versionName} (versionCode ${r.versionCode})")
        appendLine("Phone: ${r.phone}")
        appendLine("OS: ${r.os}")
        appendLine(
            r.wifi?.let { "Wi-Fi: ${bandOf(it.frequencyMhz)}, RSSI ${it.rssiDbm} dBm, link speed ${it.linkMbps} Mbps" }
                ?: "Wi-Fi: not connected"
        )
        appendLine("Wi-Fi lock: ${r.lockLine}")
        val tv = r.tv
        appendLine("TV model: ${tv.model ?: "unavailable"}")
        appendLine("TV software: ${tv.software ?: "unavailable"}")
        if (tv.failure != null) appendLine("TV test failed: ${tv.failure}")
        tv.commandOpenMs?.let { appendLine("Command connection: opened and registered in ${fmt(it)} ms") }
        if (tv.roundTripsMs.isNotEmpty() || tv.roundTripFailures > 0) {
            val failed = if (tv.roundTripFailures > 0) ", ${tv.roundTripFailures} failed" else ""
            appendLine("Command round trips (${tv.roundTripsMs.size}$failed): ${spread(tv.roundTripsMs)}")
        }
        tv.pointerOpenMs?.let { appendLine("Pointer socket: opened in ${fmt(it)} ms") }
        if (tv.pointerFailure != null) appendLine("Pointer test failed: ${tv.pointerFailure}")
        if (tv.frameSendMs.isNotEmpty()) appendLine("Pointer frame sends (${tv.frameSendMs.size}): ${spread(tv.frameSendMs)}")
        if (r.deaths.isEmpty()) {
            append("Session deaths this run: none")
        } else {
            appendLine("Session deaths this run (newest last):")
            append(r.deaths.joinToString("\n") { "- ${(r.nowMs - it.atMs).coerceAtLeast(0) / 1000}s ago, ${it.line}" })
        }
    }

    /** The one place the report text leaves the app, so addresses are stripped here and nowhere else. */
    fun finish(r: ReportInputs): String = scrubAddresses(render(r))
}
