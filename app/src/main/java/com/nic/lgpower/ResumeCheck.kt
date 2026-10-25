package com.nic.lgpower

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

internal enum class ResumeStep { PROBE, RESET }

/**
 * What the remote does with the shared connections when it comes back to the front.
 *
 * Resetting every time cost a full handshake on the first key (one to five seconds on a slow
 * Wi-Fi). The command socket is the only one the TV answers on, so one request has to come back
 * on it before it and the pointer beside it are trusted; traffic seen a moment ago proves
 * nothing, since frames buffered while the app was frozen look like fresh ones. A socket that is
 * not registered and open has nothing worth keeping.
 */
internal fun resumeStep(commandReady: Boolean): ResumeStep =
    if (commandReady) ResumeStep.PROBE else ResumeStep.RESET

/**
 * Holds key presses while a resume check decides whether the old sockets still work. A key sent
 * on a half-open pointer is lost without any error, so it waits for the verdict instead; on a
 * live link that is one round trip, usually over before the first tap.
 */
internal class ResumeGate {
    @Volatile private var current: CountDownLatch? = null

    @Synchronized fun begin(): CountDownLatch = CountDownLatch(1).also { current = it }

    fun end(check: CountDownLatch) {
        check.countDown()
        synchronized(this) { if (current === check) current = null }
    }

    /** Waits for the running check, if any, but never past [maxMs] so a stuck one can't hold keys. */
    fun await(maxMs: Long) {
        current?.await(maxMs, TimeUnit.MILLISECONDS)
    }

    val isOpen get() = current == null

    /** Runs [send] now on an open gate, else on [executor] once the verdict is in, so the caller never blocks. */
    fun runAfter(maxMs: Long, executor: Executor, send: () -> Unit) {
        if (isOpen) send() else executor.execute { await(maxMs); send() }
    }
}

/**
 * True when a reply lands on [answered] within [maxMs]. A socket that dies meanwhile ends the wait
 * early instead of at the budget.
 */
internal fun awaitReply(
    answered: CountDownLatch,
    alive: () -> Boolean,
    maxMs: Long,
    now: () -> Long = System::currentTimeMillis,
    pollMs: Long = 50L,
): Boolean {
    val until = now() + maxMs
    while (alive()) {
        val left = until - now()
        if (left <= 0) return false
        if (answered.await(minOf(left, pollMs), TimeUnit.MILLISECONDS)) return true
    }
    return false
}

/**
 * Runs the resume probe with the stale mark already set: a TV that resets the socket kills it
 * before the verdict, and the power watcher must read that as a replaced socket, not standby.
 * Only a reply clears the mark.
 */
internal inline fun probeCommand(markStale: (Boolean) -> Unit, probe: () -> Boolean): Boolean {
    markStale(true)
    return probe().also { if (it) markStale(false) }
}

/**
 * The pointer has no ping and the TV never writes on it, so a close sent while the phone dozed is
 * never heard; the only sign is how long nothing went over it. A dead one stays put because it
 * carries the retry pause.
 */
internal fun shouldReplaceIdlePointer(dead: Boolean, idleMs: Long, maxIdleMs: Long) = !dead && idleMs > maxIdleMs

/** A pointer opened since the check began is not the one it looked at and is left alone. */
internal fun <T : Any> sameSession(shared: T, seen: T?): Boolean = seen != null && shared === seen
