package com.nic.lgpower

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeAttemptsTest {
    @Test fun unopenedAttemptIsReplacedOnce() {
        val a = HandshakeAttempts()
        assertEquals(1, a.number())
        assertEquals(2, a.retry(1))
        assertNull(a.retry(2))
    }

    @Test fun lateOpenOfAbandonedAttemptIsRefused() {
        val a = HandshakeAttempts()
        a.retry(1)
        assertFalse(a.markOpened(1))
        assertFalse(a.isCurrent(1))
        assertTrue(a.markOpened(2))
    }

    @Test fun openedAttemptIsNeverReplaced() {
        val a = HandshakeAttempts()
        assertTrue(a.markOpened(1))
        assertFalse(a.mayRetry(1))
        assertNull(a.retry(1))
        assertTrue(a.isCurrent(1))
    }

    @Test fun staleAttemptCannotTriggerAnotherRetry() {
        val a = HandshakeAttempts(maxAttempts = 3)
        assertEquals(2, a.retry(1))
        assertNull(a.retry(1))
        assertEquals(3, a.retry(2))
    }
}
