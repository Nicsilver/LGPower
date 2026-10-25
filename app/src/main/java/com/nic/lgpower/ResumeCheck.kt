package com.nic.lgpower

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal enum class ResumeStep { KEEP, PROBE, RESET }

/**
 * What the remote does with the shared connections when it comes back to the front.
 *
 * Resetting every time cost a full handshake on the first key (one to five seconds on a slow
 * Wi-Fi). The command socket is the only one the TV answers on, so it vouches for the pointer
 * beside it: something heard from the TV moments ago is enough, otherwise one request has to come
 * back before the old sockets are trusted. A socket that is not registered and open has nothing
 * worth keeping.
 */
internal fun resumeStep(commandReady: Boolean, sinceRxMs: Long?, freshMs: Long): ResumeStep = when {
    !commandReady -> ResumeStep.RESET
    sinceRxMs != null && sinceRxMs < freshMs -> ResumeStep.KEEP
    else -> ResumeStep.PROBE
}

/**
 * Holds key presses while a resume check decides whether the old sockets still work. A key sent
 * on a half-open pointer is lost without any error, so it waits for the verdict instead; on a
 * live link that is one round trip, usually over before the first tap.
 */
internal class ResumeGate {
    @Volatile private var current: CountDownLatch? = null

    fun begin(): CountDownLatch = CountDownLatch(1).also { current = it }

    fun end(check: CountDownLatch) {
        check.countDown()
        synchronized(this) { if (current === check) current = null }
    }

    /** Waits for the running check, if any, but never past [maxMs] so a stuck one can't hold keys. */
    fun await(maxMs: Long) {
        current?.await(maxMs, TimeUnit.MILLISECONDS)
    }

    val isOpen get() = current == null
}
