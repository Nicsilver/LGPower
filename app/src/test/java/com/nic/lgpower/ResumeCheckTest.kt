package com.nic.lgpower

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ResumeCheckTest {
    @Test fun aSocketThatIsNotRegisteredIsReset() {
        assertEquals(ResumeStep.RESET, resumeStep(commandReady = false, sinceRxMs = 10, freshMs = 2_000))
        assertEquals(ResumeStep.RESET, resumeStep(commandReady = false, sinceRxMs = null, freshMs = 2_000))
    }

    @Test fun aReplyMomentsAgoKeepsTheSocketsWithoutAsking() {
        assertEquals(ResumeStep.KEEP, resumeStep(commandReady = true, sinceRxMs = 1_999, freshMs = 2_000))
    }

    @Test fun anIdleOrSilentSocketIsAskedFirst() {
        assertEquals(ResumeStep.PROBE, resumeStep(commandReady = true, sinceRxMs = 2_000, freshMs = 2_000))
        assertEquals(ResumeStep.PROBE, resumeStep(commandReady = true, sinceRxMs = 600_000, freshMs = 2_000))
        assertEquals(ResumeStep.PROBE, resumeStep(commandReady = true, sinceRxMs = null, freshMs = 2_000))
    }

    @Test fun aKeyWaitsForTheVerdict() {
        val gate = ResumeGate()
        val check = gate.begin()
        assertFalse(gate.isOpen)
        val releasedAt = AtomicLong()
        val pressed = CountDownLatch(1)
        val key = Thread { gate.await(5_000); releasedAt.set(System.nanoTime()); pressed.countDown() }
        key.start()
        Thread.sleep(150)
        assertEquals(1, pressed.count)
        val endedAt = System.nanoTime()
        gate.end(check)
        assertTrue(pressed.await(2, TimeUnit.SECONDS))
        assertTrue(releasedAt.get() >= endedAt)
        assertTrue(gate.isOpen)
    }

    @Test fun aKeyAfterTheVerdictDoesNotWait() {
        val gate = ResumeGate()
        gate.end(gate.begin())
        val t0 = System.nanoTime()
        gate.await(5_000)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 100)
    }

    @Test fun aStuckCheckHoldsAKeyOnlyForItsBudget() {
        val gate = ResumeGate()
        gate.begin()
        val t0 = System.nanoTime()
        gate.await(200)
        val waited = (System.nanoTime() - t0) / 1_000_000
        assertTrue(waited in 150..1_000)
    }

    @Test fun anOlderCheckEndingLeavesANewerOneClosed() {
        val gate = ResumeGate()
        val first = gate.begin()
        val second = gate.begin()
        gate.end(first)
        assertFalse(gate.isOpen)
        gate.end(second)
        assertTrue(gate.isOpen)
    }
}
