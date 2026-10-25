package com.nic.lgpower

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ResumeCheckTest {
    @Test fun aSocketThatIsNotRegisteredIsReset() {
        assertEquals(ResumeStep.RESET, resumeStep(commandReady = false))
    }

    // Frames buffered while the app was frozen can look like fresh traffic, so only a reply counts
    @Test fun aRegisteredSocketIsAlwaysAskedFirst() {
        assertEquals(ResumeStep.PROBE, resumeStep(commandReady = true))
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
        assertTrue(gate.isOpen)
        val done = CountDownLatch(1)
        val key = Thread { gate.await(60_000); done.countDown() }
        key.start()
        assertTrue(done.await(10, TimeUnit.SECONDS))
    }

    @Test fun aStuckCheckHoldsAKeyOnlyForItsBudget() {
        val gate = ResumeGate()
        gate.begin()
        val t0 = System.nanoTime()
        gate.await(200)
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 150)
    }

    @Test fun aHeldTapGoesOutOnceAfterTheVerdictAndNeverBefore() {
        val gate = ResumeGate()
        val check = gate.begin()
        val pool = Executors.newCachedThreadPool()
        val sent = AtomicInteger()
        val sentLatch = CountDownLatch(1)
        gate.runAfter(60_000, pool) { sent.incrementAndGet(); sentLatch.countDown() }
        assertFalse(sentLatch.await(200, TimeUnit.MILLISECONDS))
        assertEquals(0, sent.get())
        gate.end(check)
        assertTrue(sentLatch.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(1, sent.get())
    }

    @Test fun aTapOnAnOpenGateGoesOutOnTheCallersThread() {
        val gate = ResumeGate()
        val ranOn = AtomicReference<Thread>()
        gate.runAfter(1_000, Executor { error("must not be queued") }) { ranOn.set(Thread.currentThread()) }
        assertSame(Thread.currentThread(), ranOn.get())
    }

    // The gate is what keeps a press off a socket about to be replaced; the client needs a Context
    // to run, so this pins the call sites instead
    @Test fun everyPathThatSendsOnTheSharedSocketsWaitsOnTheGate() {
        val src = File("src/main/java/com/nic/lgpower/WebOsClient.kt").readText()
        for (fn in listOf("fun pressKey(", "private fun execute(", "private fun lunaRequest(")) {
            val start = src.indexOf(fn)
            assertTrue("$fn not found", start >= 0)
            assertTrue("$fn does not wait on resumeGate", src.substring(start, start + 250).contains("resumeGate.await("))
        }
        val tap = src.indexOf("private fun afterResumeCheck(")
        assertTrue(src.substring(tap, tap + 300).contains("resumeGate.runAfter("))
        assertTrue(src.contains("fun pointerClick() = afterResumeCheck"))
        assertTrue(src.contains("fun pointerKey(keyCode: String) = afterResumeCheck"))
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

    private class FakeClock(private val stepMs: Long) {
        var t = 0L
        fun now(): Long { t += stepMs; return t }
    }

    @Test fun aReplyBeforeTheBudgetCounts() {
        val answered = CountDownLatch(1)
        val clock = FakeClock(10)
        var calls = 0
        // Arrives on the fifth poll, well inside the budget
        val alive = { if (++calls == 5) answered.countDown(); true }
        assertTrue(awaitReply(answered, alive, 1_000, clock::now, pollMs = 1))
    }

    @Test fun aReplyAfterTheBudgetDoesNot() {
        val answered = CountDownLatch(1)
        val clock = FakeClock(100)
        var calls = 0
        val alive = { if (++calls == 50) answered.countDown(); true }
        assertFalse(awaitReply(answered, alive, 1_000, clock::now, pollMs = 1))
        assertEquals(1L, answered.count)
    }

    @Test fun aSocketThatDiesMidProbeEndsTheWaitEarly() {
        val answered = CountDownLatch(1)
        val clock = FakeClock(10)
        var calls = 0
        val alive = { ++calls < 4 }
        assertFalse(awaitReply(answered, alive, 1_000, clock::now, pollMs = 1))
        assertTrue("stopped at the budget instead of at the death", clock.t < 1_000)
    }

    @Test fun theStaleMarkIsSetBeforeTheProbeRuns() {
        var mark = false
        var markedWhileProbing = false
        probeCommand({ mark = it }) { markedWhileProbing = mark; false }
        assertTrue(markedWhileProbing)
        assertTrue("a silent socket stays marked", mark)
    }

    @Test fun onlyAReplyClearsTheStaleMark() {
        var mark = false
        assertTrue(probeCommand({ mark = it }) { true })
        assertFalse(mark)
    }

    @Test fun aSocketThatResetsMidProbeKeepsTheMarkForTheWatcher() {
        val marks = mutableListOf<Boolean>()
        // The watcher reads the flag from another thread the moment the socket dies
        var seenByWatcher: Boolean? = null
        var mark = false
        probeCommand({ mark = it; marks.add(it) }) { seenByWatcher = mark; false }
        assertEquals(true, seenByWatcher)
        assertEquals(listOf(true), marks)
    }

    @Test fun anIdlePointerIsReplacedButADeadOrBusyOneIsNot() {
        assertTrue(shouldReplaceIdlePointer(dead = false, idleMs = 30_001, maxIdleMs = 30_000))
        assertFalse(shouldReplaceIdlePointer(dead = false, idleMs = 30_000, maxIdleMs = 30_000))
        assertFalse(shouldReplaceIdlePointer(dead = false, idleMs = 5_000, maxIdleMs = 30_000))
        assertFalse(shouldReplaceIdlePointer(dead = true, idleMs = 600_000, maxIdleMs = 30_000))
    }

    @Test fun onlyThePointerTheCheckSawIsDropped() {
        val seen = Any()
        val openedMeanwhile = Any()
        assertTrue(sameSession(seen, seen))
        assertFalse(sameSession(openedMeanwhile, seen))
        assertFalse(sameSession(openedMeanwhile, null))
    }
}
