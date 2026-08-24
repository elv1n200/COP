package cop.module.impl.mining

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommissionDataTest {
    @Test
    fun `commission block parses percentages and done`() {
        val result = CommissionParser.parse(
            listOf(
                "Profile: Apple",
                "Commissions:",
                " Mithril Miner: 62.5%",
                " Goblin Slayer: DONE",
                " Crystal Hollows",
            ),
        )

        assertEquals(
            listOf(
                CommissionEntry("Mithril Miner", 62.5f),
                CommissionEntry("Goblin Slayer", 100f),
            ),
            result,
        )
    }

    @Test
    fun `parser accepts comma decimals and caps malformed input`() {
        val lines = buildList {
            add("Commissions:")
            add(" Broken")
            add(" Titanium Miner: 12,5%")
            repeat(10) { add(" Commission $it: ${it * 10}%") }
        }

        val result = CommissionParser.parse(lines)
        assertEquals(5, result.size)
        assertEquals(CommissionEntry("Titanium Miner", 12.5f), result.first())
        assertTrue(CommissionParser.parse(listOf("No header here")).isEmpty())
    }
}
