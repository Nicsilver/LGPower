package com.nic.lgpower

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldTrackerTest {
    private val holds = HoldTracker()

    @Test fun tickRunsWhileHeldAndNothingNewerIsQueued() {
        val hold = holds.begin()
        val tick = holds.tick(hold)!!
        assertTrue(holds.shouldRun(hold, tick))
    }

    @Test fun ticksQueuedBehindASlowPressOnlyRunTheNewest() {
        val hold = holds.begin()
        val first = holds.tick(hold)!!
        val second = holds.tick(hold)!!
        assertFalse(holds.shouldRun(hold, first))
        assertTrue(holds.shouldRun(hold, second))
    }

    @Test fun releasingDropsEveryQueuedTick() {
        val hold = holds.begin()
        val first = holds.tick(hold)!!
        val second = holds.tick(hold)!!
        holds.end(hold)
        assertFalse(holds.shouldRun(hold, first))
        assertFalse(holds.shouldRun(hold, second))
        assertNull(holds.tick(hold))
    }

    @Test fun separateHoldsDoNotAffectEachOther() {
        val a = holds.begin()
        val b = holds.begin()
        val tickA = holds.tick(a)!!
        val tickB = holds.tick(b)!!
        holds.end(a)
        assertFalse(holds.shouldRun(a, tickA))
        assertTrue(holds.shouldRun(b, tickB))
        assertEquals(2L, holds.tick(b))
    }
}
