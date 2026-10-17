package com.nic.lgpower

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A held button queues repeat ticks behind a slow connect or key press. Each tick carries its
 * hold's id and sequence number so that, once the finger lifts, the leftovers are dropped,
 * and while it is still down only the newest tick runs instead of all of them at once.
 */
class HoldTracker {
    private val ids = AtomicLong(0)
    private val latest = ConcurrentHashMap<Long, Long>()

    fun begin(): Long = ids.incrementAndGet().also { latest[it] = 0L }

    fun end(hold: Long) { latest.remove(hold) }

    /** Numbers a new tick of [hold]; null when the hold has already ended. */
    fun tick(hold: Long): Long? = latest.computeIfPresent(hold) { _, n -> n + 1 }

    fun shouldRun(hold: Long, tick: Long) = latest[hold] == tick
}
