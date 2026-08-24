package cop.module.settings.impl

import kotlin.test.Test
import kotlin.test.assertEquals

class ChoiceComponentResetTest {
    @Test
    fun `selector reset restores declared default`() {
        val selector = SelectorComponent("Mode", "Fast", listOf("Safe", "Fast", "Expert"))
        selector.selected = "Expert"

        selector.reset()

        assertEquals("Fast", selector.selected)
    }

    @Test
    fun `segmented reset restores declared default`() {
        val segmented = SegmentedComponent("Style", "Card", listOf("Minimal", "Card"))
        segmented.index = 0

        segmented.reset()

        assertEquals("Card", segmented.selected)
    }
}
