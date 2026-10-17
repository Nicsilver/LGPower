package com.nic.lgpower

import com.nic.lgpower.PointerQueue.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerQueueTest {
    private var now = 10_000L
    private val queue = PointerQueue({ now }, maxFrames = 4, staleMs = 1_500)

    private val key = "type:button\nname:UP\n\n"
    private val click = "type:click\n\n"
    private val scroll = "type:scroll\ndx:0\ndy:-1\n\n"

    @Test fun aKeyPressNeverGoesStale() {
        queue.add(Kind.KEY, key)
        now += 5_000
        assertEquals(listOf(key), queue.drain())
    }

    @Test fun staleMovesAndScrollsAreDropped() {
        queue.addMove(3, 4)
        queue.add(Kind.SCROLL, scroll)
        now += 1_501
        assertTrue(queue.drain().isEmpty())
    }

    @Test fun freshMovesAndScrollsSurvive() {
        queue.addMove(3, 4)
        queue.add(Kind.SCROLL, scroll)
        now += 1_500
        assertEquals(listOf(PointerQueue.moveFrame(3, 4), scroll), queue.drain())
    }

    @Test fun aClickBehindADroppedMoveIsDropped() {
        queue.addMove(3, 4)
        now += 100
        queue.add(Kind.CLICK, click)
        now += 2_000
        assertTrue(queue.drain().isEmpty())
    }

    @Test fun aClickWithNoMovesBeforeItSurvivesAnyDelay() {
        queue.add(Kind.CLICK, click)
        now += 5_000
        assertEquals(listOf(click), queue.drain())
    }

    @Test fun aClickAfterFreshMovesSurvives() {
        queue.addMove(1, 1)
        queue.add(Kind.CLICK, click)
        now += 400
        assertEquals(listOf(PointerQueue.moveFrame(1, 1), click), queue.drain())
    }

    @Test fun foldingMovesRefreshesTheirAge() {
        queue.addMove(1, 0)
        now += 1_400
        queue.addMove(2, 0)
        now += 1_400
        assertEquals(listOf(PointerQueue.moveFrame(3, 0)), queue.drain())
    }

    @Test fun movesOnlyFoldWhenNothingCameBetween() {
        queue.addMove(1, 0)
        queue.add(Kind.KEY, key)
        queue.addMove(2, 0)
        assertEquals(listOf(PointerQueue.moveFrame(1, 0), key, PointerQueue.moveFrame(2, 0)), queue.drain())
    }

    @Test fun overflowEvictsAMoveBeforeAKeyAndTaintsLaterClicks() {
        queue.add(Kind.KEY, key)
        queue.addMove(1, 0)
        queue.add(Kind.KEY, key)
        queue.add(Kind.KEY, key)
        queue.add(Kind.CLICK, click)
        assertEquals(listOf(key, key, key), queue.drain())
    }

    @Test fun overflowOfOnlyKeysDropsTheOldest() {
        repeat(5) { queue.add(Kind.KEY, "k$it") }
        assertEquals(listOf("k1", "k2", "k3", "k4"), queue.drain())
    }

    @Test fun evictingTheMergedMoveStopsFurtherFolding() {
        repeat(3) { queue.add(Kind.KEY, key) }
        queue.addMove(1, 0)
        queue.add(Kind.KEY, "last")
        queue.addMove(5, 0)
        assertEquals(listOf(key, key, "last", PointerQueue.moveFrame(5, 0)), queue.drain())
    }

    @Test fun drainEmptiesTheQueueAndForgetsDrops() {
        queue.addMove(1, 0)
        now += 2_000
        queue.drain()
        queue.add(Kind.CLICK, click)
        assertEquals(listOf(click), queue.drain())
        assertEquals(0, queue.size)
    }
}
