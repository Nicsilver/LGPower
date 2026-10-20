package com.nic.lgpower

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardFramesTest {
    private val denied = JSONObject("""{"type":"error","id":"c3","error":"401 insufficient permissions","payload":{}}""")

    @Test fun errorTextComesFromTopLevelWhenPayloadIsEmpty() =
        assertEquals("401 insufficient permissions", frameErrorText(denied))

    @Test fun payloadErrorTextWins() =
        assertEquals("Must subscribe", frameErrorText(JSONObject(
            """{"type":"response","payload":{"returnValue":false,"errorText":"Must subscribe"},"error":"500"}""")))

    @Test fun noTextAnywhereIsNull() =
        assertNull(frameErrorText(JSONObject("""{"type":"error","payload":{}}""")))

    @Test fun permissionErrorsAreRecognised() {
        assertTrue(isPermissionError("401 insufficient permissions"))
        assertTrue(isPermissionError("401 insufficient permissions (not registered)"))
        assertTrue(isPermissionError("Insufficient permissions"))
        assertFalse(isPermissionError("500 Application error"))
        assertFalse(isPermissionError("Timeout. Is the TV on and reachable?"))
    }

    @Test fun focusFollowsCurrentWidget() {
        fun focus(json: String) = keyboardFocusOf(JSONObject(json))
        assertEquals(KeyboardFocus.FIELD, focus(
            """{"type":"response","payload":{"returnValue":true,"currentWidget":{"focus":true,"contentType":"text"}}}"""))
        assertEquals(KeyboardFocus.NO_FIELD, focus(
            """{"type":"response","payload":{"returnValue":true,"currentWidget":{"focus":false}}}"""))
        assertEquals(KeyboardFocus.UNKNOWN, focus(
            """{"type":"response","payload":{"returnValue":true,"subscribed":true}}"""))
        assertEquals(KeyboardFocus.UNKNOWN, focus(
            """{"type":"response","payload":{"returnValue":true,"currentWidget":{"contentType":"text"}}}"""))
        assertEquals(KeyboardFocus.UNKNOWN, focus("""{"type":"error","error":"401 insufficient permissions","payload":{}}"""))
        assertEquals(KeyboardFocus.UNKNOWN, focus("""{"type":"response","payload":{"returnValue":false}}"""))
    }

    @Test fun strayErrorOnAGoodResponseIsNotAFailure() {
        fun failed(json: String) = responseFailed(JSONObject(json))
        assertFalse(failed("""{"type":"response","error":"500 stray","payload":{"returnValue":true}}"""))
        assertTrue(failed("""{"type":"response","error":"500 bad","payload":{}}"""))
        assertTrue(failed("""{"type":"response","payload":{"returnValue":false}}"""))
        assertFalse(failed("""{"type":"response","payload":{"volume":5}}"""))
        assertEquals("ok", KeyboardReport.statusOf(
            """{"type":"response","error":"500 stray","payload":{"returnValue":true}}""", null))
        assertEquals("error: 401 insufficient permissions", KeyboardReport.statusOf(denied.toString(), null))
    }

    @Test fun summaryUsesSharedWording() {
        assertEquals("OK", KeyboardReport.summaryStatus("""{"type":"response","payload":{"returnValue":true}}""", null))
        assertEquals("ERROR 401 insufficient permissions", KeyboardReport.summaryStatus(denied.toString(), null))
    }

    @Test fun reportRedactsPairingKeyAndMacs() {
        val out = KeyboardReport.redact(
            """{"payload":{"client-key":"abc123","device_id":"AA:BB:CC:DD:EE:FF","model_name":"HE_DTV"}} wired 20:3d:bd:de:49:12""")
        assertFalse(out.contains("abc123"))
        assertFalse(out.contains("AA:BB"))
        assertFalse(out.contains("20:3d"))
        assertTrue(out.contains("HE_DTV"))
        assertFalse(KeyboardReport.redact("failed to connect to /192.168.1.157").contains("192.168"))
    }

    @Test fun longFramesAreTrimmed() {
        val out = KeyboardReport.trim("x".repeat(1000), 100)
        assertTrue(out.startsWith("x".repeat(100)))
        assertTrue(out.contains("900 more"))
        assertEquals("short", KeyboardReport.trim("short", 100))
    }

    @Test fun statusSaysWhatTheTvAnswered() {
        assertEquals("ok", KeyboardReport.statusOf("""{"type":"response","payload":{"returnValue":true}}""", null))
        assertEquals("error: 401 insufficient permissions", KeyboardReport.statusOf(denied.toString(), null))
        assertEquals("refused: Must subscribe", KeyboardReport.statusOf(
            """{"type":"response","payload":{"returnValue":false,"errorText":"Must subscribe"}}""", null))
        assertEquals("no reply (No reply within 6s)", KeyboardReport.statusOf(null, "No reply within 6s"))
    }
}
