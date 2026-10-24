package com.nic.lgpower

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TestLogJsonTest {
    @Test
    fun fieldsKeepOrderAndTypes() {
        val s = TestLogJson.line("k", 123456789L, "key", arrayOf("sid", "ptr3", "n", 7, "st", "sent", "name", "UP"))
        assertEquals("{\"app\":\"k\",\"t\":123456789,\"ev\":\"key\",\"sid\":\"ptr3\",\"n\":7,\"st\":\"sent\",\"name\":\"UP\"}", s)
    }

    @Test
    fun nullFieldsAreLeftOutAndBooleansStayBooleans() {
        val s = TestLogJson.line("k", 5L, "res", arrayOf("ok", false, "err", null, "id", "c1"))
        assertEquals("{\"app\":\"k\",\"t\":5,\"ev\":\"res\",\"ok\":false,\"id\":\"c1\"}", s)
    }

    @Test
    fun stringsAreEscapedAndParseBack() {
        val nasty = "a\"b\\c\nd\u0001"
        val o = JSONObject(TestLogJson.line("k", 1L, "sock", arrayOf("reason", nasty)))
        assertEquals(nasty, o.getString("reason"))
        assertFalse(TestLogJson.line("k", 1L, "x", arrayOf("r", "a\nb")).contains('\n'))
    }

    @Test
    fun shortTrimsAndFlattens() {
        assertEquals(80, TestLogJson.short("x".repeat(200))!!.length)
        assertFalse(TestLogJson.short("a\nb")!!.contains('\n'))
        assertEquals(null, TestLogJson.short(null))
    }
}
