package cop.utils.skyblock

import cop.utils.lerpAngle
import cop.utils.romanToInt
import cop.utils.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PriceAndNumberLogicTest {
    @Test
    fun `formats prices at each compact notation boundary`() {
        val cases = mapOf(
            0.0 to "0",
            1_341.09 to "1,341",
            999_999.99 to "999,999",
            1_000_000.0 to "1.00M",
            12_345_678.0 to "12.35M",
            1_000_000_000.0 to "1.00B",
            1_000_000_000_000.0 to "1.00T",
            -1_250_000.0 to "-1.25M",
        )

        cases.forEach { (price, expected) ->
            assertEquals(expected, PriceClient.formatPrice(price), "price=$price")
        }
    }

    @Test
    fun `parses decimal and roman dungeon tiers`() {
        assertEquals(7, romanToInt("7"))
        assertEquals(4, romanToInt("IV"))
        assertEquals(9, romanToInt("IX"))
        assertEquals(1_994, romanToInt("MCMXCIV"))
    }

    @Test
    fun `rounding rejects invalid precision`() {
        assertEquals(12.35, 12.346.round(2).toDouble())
        assertFailsWith<IllegalArgumentException> { 1.0.round(-1) }
    }

    @Test
    fun `angle interpolation follows the shortest wrapped path and clamps its factor`() {
        assertEquals(360.0, 0.5.lerpAngle(350.0, 10.0))
        assertEquals(0.0, 0.5.lerpAngle(10.0, 350.0))
        assertEquals(370.0, 2.0.lerpAngle(350.0, 10.0))
        assertEquals(350.0, (-1.0).lerpAngle(350.0, 10.0))
    }
}
