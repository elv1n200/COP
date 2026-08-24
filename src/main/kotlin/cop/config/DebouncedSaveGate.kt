package cop.config

import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe debounce state for config writes.
 *
 * Keeping the timing decision separate from Minecraft makes the behaviour
 * deterministic to test and lets callers use a monotonic clock.
 */
internal class DebouncedSaveGate(
    private val delayMillis: Long,
) {
    init {
        require(delayMillis >= 0L) { "delayMillis must not be negative" }
    }

    private val deadlineMillis = AtomicLong(NONE)

    /** Request one write after the most recent change has settled. */
    fun request(nowMillis: Long) {
        deadlineMillis.set(saturatingAdd(nowMillis, delayMillis))
    }

    /**
     * Claims a pending write once its deadline has passed. A successful claim
     * consumes it, so repeated ticks cannot write the same snapshot twice.
     */
    fun takeIfDue(nowMillis: Long): Boolean {
        while (true) {
            val deadline = deadlineMillis.get()
            if (deadline == NONE || nowMillis < deadline) return false
            if (deadlineMillis.compareAndSet(deadline, NONE)) return true
        }
    }

    /** An explicit save supersedes any delayed request. */
    fun clear() {
        deadlineMillis.set(NONE)
    }

    private fun saturatingAdd(value: Long, increment: Long): Long =
        if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
