package cop.module.impl.dungeon

import cop.api.abobaui.constraints.impl.positions.Centre
import cop.api.abobaui.dsl.at
import cop.api.abobaui.dsl.minus
import cop.api.abobaui.dsl.px
import cop.api.colour.Colour
import cop.api.colour.colour
import cop.api.skyblock.Location.inSkyblock
import cop.api.skyblock.SkyblockPlayer
import cop.api.skyblock.dungeon.Dungeon.inBoss
import cop.api.skyblock.dungeon.Dungeon.inDungeons
import cop.module.Module
import cop.utils.ui.textPair

object InvincibilityTimer : Module(
    "Invincibility Timer",
    desc = "Gives visual information about your invincibility times."
) {
    private val dungeonOnly by switch("Dungeons only", desc = "Active in dungeons only.")
    private val bossOnly by switch("Boss only", desc = "Active in boss room only.")
//    private val serverTicks by BooleanSetting("Use server ticks", desc = "Uses server ticks instead of real time.")
    val mageReduction by switch("Mage reduction", desc = "Accounts for mage cooldown reduction.")
    val cataLevel by slider("Catacombs level", 0, 0, 50, desc = "Catacombs level for Bonzo's mask ability.")

    private val hud by textHud("Invincibility timer", Colour.PINK, toggleable = false) {
        visibleIf { this@InvincibilityTimer.enabled && inSkyblock && (!bossOnly || inBoss) && (!dungeonOnly || inDungeons || bossOnly) }
        column {
            SkyblockPlayer.InvincibilityType.entries.forEach { type ->
                val (col, time) = type.getTime()
                row(gap = 1.px) {
                    text(
                        string = "◼",
                        font = font,
                        size = 18.px,
                        colour = colour { if (type.shouldDot()) colour.rgb else Colour.TRANSPARENT.rgb },
                        pos = at(y = Centre - 2.px),
                    )
                    textPair(
                        string = "${type.displayName}:",
                        supplier = { time() },
                        labelColour = colour,
                        valueColour = col(),
                        shadow = shadow,
                        font = font
                    )
                }
            }
        }
    }.withSettings(::dungeonOnly, ::bossOnly, ::mageReduction, ::cataLevel).setting()
}