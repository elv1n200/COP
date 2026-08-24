package cop.module.impl.dungeon.huds

import cop.api.abobaui.dsl.px
import cop.api.abobaui.elements.impl.Text.Companion.shadow
import cop.api.abobaui.elements.impl.Text.Companion.textSupplied
import cop.api.skyblock.dungeon.Blessing
import cop.api.skyblock.dungeon.Dungeon
import cop.module.Module

/** Read-only HUD over blessing levels COP already tracks from the tab footer.
 * Concept reference: NoammAddons 26.1.2 `BlessingDisplay`. */
object BlessingHud : Module(
    "Blessing HUD",
    desc = "Displays the active dungeon blessing levels.",
) {
    private val showPower by switch("Power", true)
    private val showLife by switch("Life", true)
    private val showWisdom by switch("Wisdom", true)
    private val showStone by switch("Stone", true)
    private val showTime by switch("Time", true)

    @Suppress("unused")
    private val blessingHud by textHud("Blessings") {
        visibleIf { preview || Dungeon.inDungeons && Blessing.entries.any { enabled(it) && it.current > 0 } }
        column {
            Blessing.entries.forEachIndexed { index, blessing ->
                val row = textSupplied(
                    supplier = {
                        val value = if (preview) index + 1 else blessing.current
                        "${blessingColour(blessing)}${blessing.displayString}: §f$value"
                    },
                    colour = colour,
                    font = font,
                    size = 18.px,
                )
                row.shadow = shadow
                row.visibleIf { preview || enabled(blessing) && blessing.current > 0 }
            }
        }
    }.setting()

    private fun enabled(blessing: Blessing): Boolean = when (blessing) {
        Blessing.POWER -> showPower
        Blessing.LIFE -> showLife
        Blessing.WISDOM -> showWisdom
        Blessing.STONE -> showStone
        Blessing.TIME -> showTime
    }

    private fun blessingColour(blessing: Blessing): String = when (blessing) {
        Blessing.POWER -> "§c"
        Blessing.LIFE -> "§a"
        Blessing.WISDOM -> "§b"
        Blessing.STONE -> "§7"
        Blessing.TIME -> "§5"
    }
}
