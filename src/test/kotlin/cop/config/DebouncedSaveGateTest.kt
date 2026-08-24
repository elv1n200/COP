package cop.config

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebouncedSaveGateTest {
    @Test
    fun `request is not due before debounce deadline`() {
        val gate = DebouncedSaveGate(delayMillis = 350L)

        gate.request(nowMillis = 1_000L)

        assertFalse(gate.takeIfDue(nowMillis = 1_349L))
        assertTrue(gate.takeIfDue(nowMillis = 1_350L))
        assertFalse(gate.takeIfDue(nowMillis = 2_000L))
    }

    @Test
    fun `new edits postpone the pending write`() {
        val gate = DebouncedSaveGate(delayMillis = 350L)

        gate.request(nowMillis = 1_000L)
        gate.request(nowMillis = 1_300L)

        assertFalse(gate.takeIfDue(nowMillis = 1_350L))
        assertFalse(gate.takeIfDue(nowMillis = 1_649L))
        assertTrue(gate.takeIfDue(nowMillis = 1_650L))
    }

    @Test
    fun `explicit save clears a delayed request`() {
        val gate = DebouncedSaveGate(delayMillis = 350L)

        gate.request(nowMillis = 1_000L)
        gate.clear()

        assertFalse(gate.takeIfDue(nowMillis = 2_000L))
    }

    @Test
    fun `deadline calculation saturates instead of overflowing`() {
        val gate = DebouncedSaveGate(delayMillis = 350L)

        gate.request(nowMillis = Long.MAX_VALUE - 100L)

        assertFalse(gate.takeIfDue(nowMillis = Long.MAX_VALUE - 1L))
        assertTrue(gate.takeIfDue(nowMillis = Long.MAX_VALUE))
    }
}
