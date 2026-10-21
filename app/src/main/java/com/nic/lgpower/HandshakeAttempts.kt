package com.nic.lgpower

/**
 * Which registration socket of one pointer session is the live one. A socket that never
 * opened is replaced once; whatever the abandoned one does afterwards (a late open, a
 * failure from being cancelled) is told apart by its number and must not touch the session.
 */
internal class HandshakeAttempts(private val maxAttempts: Int = 2) {
    private var current = 1
    private var opened = false

    @Synchronized fun number() = current

    @Synchronized fun isCurrent(attempt: Int) = attempt == current

    /** False when [attempt] was abandoned: its socket has to be closed, not used. */
    @Synchronized fun markOpened(attempt: Int): Boolean {
        if (attempt != current) return false
        opened = true
        return true
    }

    /** A failure before the upgrade finished says nothing definite about the TV, so one more try is allowed. */
    @Synchronized fun mayRetry(attempt: Int) = attempt == current && !opened && current < maxAttempts

    /** The number of the replacement attempt, or null if [attempt] is not the live unopened one or none is left. */
    @Synchronized fun retry(attempt: Int): Int? {
        if (!mayRetry(attempt)) return null
        current++
        return current
    }
}
