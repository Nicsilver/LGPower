package com.nic.lgpower

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypingSupportTest {
    @Test fun linksOpenAsTypedAndEverythingElseIsASearch() {
        assertEquals("https://example.com/a", TypingSupport.browserTarget("https://example.com/a"))
        assertEquals("https://example.com", TypingSupport.browserTarget("example.com"))
        assertEquals("https://www.google.com/search?q=cat+videos", TypingSupport.browserTarget("cat videos"))
        assertEquals("https://www.google.com/search?q=planet", TypingSupport.browserTarget(" planet "))
        assertEquals("https://www.google.com/search?q=hello+world.+ok", TypingSupport.browserTarget("hello world. ok"))
    }

    @Test fun versionComesFromWhateverTheTvReports() {
        assertEquals("webOSTV 24 4 30 24", TypingSupport.versionOf(JSONObject(
            """{"product_name":"webOSTV 24","major_ver":"4","minor_ver":"30","dre_ver":"24"}""")))
        assertEquals("43.21.59", TypingSupport.versionOf(JSONObject("""{"firmwareVersion":"43.21.59"}""")))
        assertNull(TypingSupport.versionOf(JSONObject("""{"returnValue":true}""")))
        assertNull(TypingSupport.versionOf(null))
    }

    @Test fun textLengthIsReadOnlyFromAWidgetThatHasIt() {
        fun len(json: String) = keyboardTextLengthOf(JSONObject(json))
        assertEquals(3, len("""{"type":"response","payload":{"currentWidget":{"focus":true,"surroundingTextLength":3}}}"""))
        assertNull(len("""{"type":"response","payload":{"currentWidget":{"focus":true,"hiddenText":true}}}"""))
        assertNull(len("""{"type":"error","error":"401 insufficient permissions","payload":{}}"""))
    }
}
