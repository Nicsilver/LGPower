package com.nic.lgpower

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnReportTest {
    @Test fun bandFromFrequency() {
        assertEquals("2.4 GHz", ConnReport.bandOf(2437))
        assertEquals("5 GHz", ConnReport.bandOf(5180))
        assertEquals("5 GHz", ConnReport.bandOf(5885))
        assertEquals("6 GHz", ConnReport.bandOf(5955))
        assertEquals("6 GHz", ConnReport.bandOf(7115))
        assertEquals("unknown band (900 MHz)", ConnReport.bandOf(900))
    }

    @Test fun spreadUsesMedianOfEvenAndOddCounts() {
        assertEquals("min 4.0 / median 6.0 / max 20 ms", ConnReport.spread(listOf(20.0, 4.0, 6.0)))
        assertEquals("min 1.0 / median 2.5 / max 4.0 ms", ConnReport.spread(listOf(4.0, 1.0, 3.0, 2.0)))
        assertEquals("min 0.02 / median 0.03 / max 0.30 ms", ConnReport.spread(listOf(0.03, 0.02, 0.3)))
        assertEquals("no samples", ConnReport.spread(emptyList()))
    }

    @Test fun tvModelAndSoftwareFromFrames() {
        val system = """{"type":"response","payload":{"returnValue":true,"modelName":"OLED65C4PUA"}}"""
        val software = """{"type":"response","payload":{"returnValue":true,"product_name":"webOS","model_name":"HE_DTV_W24P_AFADATAA","sw_type":"FIRMWARE","major_ver":"04","minor_ver":"40.43"}}"""
        assertEquals("OLED65C4PUA", ConnReport.tvModel(system, null))
        assertEquals("webOS firmware 04.40.43 (HE_DTV_W24P_AFADATAA)", ConnReport.tvSoftware(software, null))
    }

    @Test fun refusedOrMissingTvCallsSayUnavailable() {
        val refused = """{"type":"error","error":"401 insufficient permissions"}"""
        assertEquals("unavailable (error: 401 insufficient permissions)", ConnReport.tvSoftware(refused, null))
        assertEquals("unavailable (no reply (No reply within 4s))", ConnReport.tvModel(null, "No reply within 4s"))
    }

    private fun inputs(tv: TvProbe, wifi: WifiSnapshot? = WifiSnapshot(5180, -52, 866), deaths: List<DeathLog.Entry> = emptyList()) =
        ReportInputs("LG Power (Kotlin)", "1.39.1", 46, "Google Pixel 8", "Android 14 (API 34)", wifi,
            "low-latency, held during the test", tv, deaths, nowMs = 100_000)

    @Test fun fullReportHasEveryItemOnItsOwnLine() {
        val tv = TvProbe(null, "OLED65C4PUA", "webOS firmware 04.40.43", 183.0, List(10) { 6.0 + it }, 0, 96.0, null, List(10) { 0.02 })
        val lines = ConnReport.finish(inputs(tv, deaths = listOf(DeathLog.Entry(58_000, "cmd session died: cause=own close age=1ms sinceRx=never pointer=none")))).lines()
        assertEquals("LG Power connection report", lines[0])
        assertEquals("App: LG Power (Kotlin) 1.39.1 (versionCode 46)", lines[1])
        assertTrue(lines.contains("Wi-Fi: 5 GHz, RSSI -52 dBm, link speed 866 Mbps"))
        assertTrue(lines.contains("Command connection: opened and registered in 183 ms"))
        assertTrue(lines.contains("Command round trips (10): min 6.0 / median 11 / max 15 ms"))
        assertTrue(lines.contains("Pointer frame sends (10): min 0.02 / median 0.02 / max 0.02 ms"))
        assertTrue(lines.any { it == "- 42s ago, cmd session died: cause=own close age=1ms sinceRx=never pointer=none" })
    }

    @Test fun reportKeepsTimingsIntactButScrubsAddresses() {
        val tv = TvProbe("Can't connect to 192.168.1.157:3001 or fe80::1:2:3", commandOpenMs = null)
        val text = ConnReport.finish(inputs(tv, wifi = null))
        assertFalse(text.contains("192.168"))
        assertFalse(text.contains("fe80"))
        assertTrue(text.contains("TV test failed: Can't connect to <ip> or <ip>"))
        assertTrue(text.contains("Wi-Fi: not connected"))
    }

    @Test fun ordinaryReportLinesSurviveScrubbing() {
        val tv = TvProbe(null, "OLED65C4PUA", "webOS firmware 04.40.43 (HE_DTV_W24P_AFADATAA)", 12.0, listOf(5.0), 2, 9.0, null, listOf(0.1))
        val raw = ConnReport.render(inputs(tv))
        assertEquals(raw, ConnReport.finish(inputs(tv)))
    }

    @Test fun tvFailureStillLeavesPhoneAndWifiParts() {
        val text = ConnReport.finish(inputs(TvProbe(failure = "No TV is set up")))
        assertTrue(text.contains("Phone: Google Pixel 8"))
        assertTrue(text.contains("Wi-Fi: 5 GHz"))
        assertTrue(text.contains("TV model: unavailable"))
        assertTrue(text.contains("TV test failed: No TV is set up"))
        assertTrue(text.endsWith("Session deaths this run: none"))
    }

    @Test fun deathLogKeepsOnlyTheNewestTen() {
        var now = 0L
        val log = DeathLog(10) { now++ }
        repeat(14) { log.add("line $it") }
        val kept = log.recent().map { it.line }
        assertEquals(10, kept.size)
        assertEquals("line 4", kept.first())
        assertEquals("line 13", kept.last())
    }
}
