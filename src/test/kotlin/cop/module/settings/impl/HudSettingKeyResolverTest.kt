package cop.module.settings.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HudSettingKeyResolverTest {
    private val settings = listOf(
        HudSettingKey("Normal", "Normal"),
        HudSettingKey("Normal", "Normal door"),
        HudSettingKey("Wither", "Wither door"),
        HudSettingKey("Border", "Border"),
        HudSettingKey("Border", "Icon border"),
    )

    @Test
    fun `legacy format routes duplicate display names to last writer`() {
        val legacy = usesLegacyHudSettingKeys(setOf("Normal", "Wither", "Border"), settings)

        assertTrue(legacy)
        assertEquals(1, resolveHudSettingIndex("Normal", settings, legacy))
        assertEquals(2, resolveHudSettingIndex("Wither", settings, legacy))
        assertEquals(4, resolveHudSettingIndex("Border", settings, legacy))
    }

    @Test
    fun `stable format keeps room door and icon settings distinct`() {
        val saved = setOf("Normal", "Normal door", "Wither door", "Border", "Icon border")
        val legacy = usesLegacyHudSettingKeys(saved, settings)

        assertFalse(legacy)
        assertEquals(0, resolveHudSettingIndex("Normal", settings, legacy))
        assertEquals(1, resolveHudSettingIndex("Normal door", settings, legacy))
        assertEquals(3, resolveHudSettingIndex("Border", settings, legacy))
        assertEquals(4, resolveHudSettingIndex("Icon border", settings, legacy))
    }

    @Test
    fun `unknown keys are ignored`() {
        assertNull(resolveHudSettingIndex("missing", settings, legacy = false))
        assertNull(resolveHudSettingIndex("missing", settings, legacy = true))
    }
}
