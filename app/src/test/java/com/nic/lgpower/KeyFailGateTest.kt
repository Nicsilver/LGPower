package com.nic.lgpower

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyFailGateTest {
    private var time = 1_000L
    private val gate = KeyFailGate { time }
    private val failed: (String) -> Boolean = { it == "fail" }

    @Test fun pressesQueuedBeforeAFailureAreSkipped() {
        val first = gate.now()
        time += 10
        val second = gate.now()
        time += 10
        val third = gate.now()
        time += 6_000

        assertEquals("fail", gate.run(first, failed) { "fail" })
        var ran = 0
        assertNull(gate.run(second, failed) { ran++; "ok" })
        assertNull(gate.run(third, failed) { ran++; "ok" })
        assertEquals(0, ran)
    }

    @Test fun aPressTappedAfterTheFailureStillRuns() {
        val first = gate.now()
        time += 6_000
        gate.run(first, failed) { "fail" }
        time += 500
        assertEquals("ok", gate.run(gate.now(), failed) { "ok" })
    }

    @Test fun successDoesNotSkipLaterPresses() {
        val a = gate.now()
        val b = gate.now()
        assertEquals("ok", gate.run(a, failed) { "ok" })
        assertEquals("ok", gate.run(b, failed) { "ok" })
    }
}
