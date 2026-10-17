package com.nic.lgpower

/**
 * Key presses run one at a time and each can wait out a connect deadline, so taps queued
 * behind a failed one would otherwise each wait their own full timeout. A press that was
 * queued before the latest failure is skipped instead of retried; one tapped afterwards runs.
 */
class KeyFailGate(private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile private var failedAt = Long.MIN_VALUE

    fun now() = clock()

    /** Runs [block] unless a press failed after [queuedAt]; null means it was skipped. */
    fun <T> run(queuedAt: Long, failed: (T) -> Boolean, block: () -> T): T? {
        if (failedAt >= queuedAt) return null
        return block().also { if (failed(it)) failedAt = clock() }
    }
}
