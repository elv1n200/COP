package cop.module.impl.dungeon.huds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WarpCooldownDataTest {
    @Test
    fun `timer never restarts while active and clamps at zero`() {
        val timer = CooldownTimer(30_000)
        timer.start(1_000)
        assertEquals(25_000, timer.remaining(6_000))

        timer.start(10_000)
        assertEquals(1_000, timer.remaining(30_000))
        assertEquals(0, timer.remaining(31_001))

        timer.start(40_000)
        assertEquals(30_000, timer.remaining(40_000))
        timer.clear()
        assertEquals(0, timer.remaining(40_000))
    }

    @Test
    fun `entry parser accepts decorated dungeon message only`() {
        assertTrue(
            DungeonEntryParser.isEntryMessage(
                "--------------------------------\n[MVP+] Elv1n entered MM Master Catacombs, Floor VII!\n--------------------------------",
            ),
        )
        assertFalse(DungeonEntryParser.isEntryMessage("Party > Elv1n: entered Master Catacombs, Floor VII!"))
        assertFalse(DungeonEntryParser.isEntryMessage("[MVP+] name-with-dash entered Master Catacombs, Floor VII!"))
    }
}
